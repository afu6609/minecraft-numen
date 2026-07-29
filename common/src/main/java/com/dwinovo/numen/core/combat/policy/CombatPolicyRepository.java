package com.dwinovo.numen.core.combat.policy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * World-local atomic JSON repository. Every read and mutation is explicitly
 * scoped by the companion UUID supplied by the caller.
 */
public final class CombatPolicyRepository {

    private static final int FILE_VERSION = 1;
    private static final int MAX_POLICIES_PER_OWNER = 32;
    private static final int MAX_VALIDATION_RECORDS = 512;
    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private StoreData cached;

    public CombatPolicyRepository(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    public synchronized CombatPolicySnapshot save(
            Owner owner, CombatPolicy draft) {
        requireOwner(owner);
        CombatPolicyValidation validation = CombatPolicyValidator.validate(draft);
        if (!validation.valid()) {
            recordValidation(owner, draft == null ? null : draft.id(), validation);
            throw new PolicyRejectedException(validation);
        }

        StoreData data = data();
        StoredPolicy previous = null;
        String id = draft.id();
        if (id != null) {
            previous = findOwned(data, owner.uuid(), id);
            if (previous == null) {
                throw new IllegalArgumentException(
                        "unknown combat policy " + id + " for this companion");
            }
        } else {
            long ownedCount = data.policies.stream()
                    .filter(policy -> owner.uuid().equals(policy.ownerUuid))
                    .count();
            if (ownedCount >= MAX_POLICIES_PER_OWNER) {
                throw new IllegalStateException(
                        "this companion already has the maximum "
                                + MAX_POLICIES_PER_OWNER + " combat policies");
            }
            id = newId(data, owner.uuid());
        }

        long now = System.currentTimeMillis();
        CombatPolicy policy = withId(draft, id);
        StoredPolicy stored = new StoredPolicy();
        stored.ownerUuid = owner.uuid();
        stored.ownerName = owner.name();
        stored.policy = policy;
        stored.state = CombatPolicyState.CANDIDATE;
        stored.active = false;
        stored.consecutiveSuccesses = 0;
        stored.totalSuccesses = previous == null ? 0 : previous.totalSuccesses;
        stored.totalFailures = previous == null ? 0 : previous.totalFailures;
        stored.createdAt = previous == null ? now : previous.createdAt;
        stored.updatedAt = now;
        stored.revision = previous == null ? 1 : increment(previous.revision);
        stored.lastOutcome = "saved; activation required";

        if (previous != null) {
            data.policies.remove(previous);
        }
        data.policies.add(stored);
        appendValidation(data, owner, id, validation);
        saveData(data);
        return snapshot(stored);
    }

    /**
     * Persist a structural parse rejection which occurred before a policy DTO
     * could be built.
     */
    public synchronized CombatPolicyValidationRecord recordValidation(
            Owner owner,
            String requestedPolicyId,
            CombatPolicyValidation validation) {
        requireOwner(owner);
        Objects.requireNonNull(validation, "validation");
        StoreData data = data();
        CombatPolicyValidationRecord result = appendValidation(
                data, owner, displayPolicyId(requestedPolicyId), validation);
        saveData(data);
        return result;
    }

    public synchronized List<CombatPolicySnapshot> status(
            Owner owner, String policyId) {
        requireOwner(owner);
        return data().policies.stream()
                .filter(policy -> owner.uuid().equals(policy.ownerUuid))
                .filter(policy -> policyId == null
                        || policyId.equals(policy.policy.id()))
                .sorted(Comparator.comparingLong(
                        (StoredPolicy policy) -> policy.updatedAt).reversed())
                .map(CombatPolicyRepository::snapshot)
                .toList();
    }

    public synchronized CombatPolicySnapshot activate(
            Owner owner, String policyId) {
        requireOwner(owner);
        StoredPolicy chosen = requireOwned(data(), owner, policyId);
        CombatPolicy.Binding binding = chosen.policy.binding();
        for (StoredPolicy policy : data().policies) {
            if (policy != chosen
                    && owner.uuid().equals(policy.ownerUuid)
                    && policy.active
                    && binding.equals(policy.policy.binding())) {
                policy.active = false;
                policy.updatedAt = System.currentTimeMillis();
                policy.revision = increment(policy.revision);
                policy.lastOutcome = "superseded by " + chosen.policy.id();
            }
        }
        chosen.active = true;
        if (chosen.state == CombatPolicyState.DISABLED) {
            chosen.state = CombatPolicyState.CANDIDATE;
            chosen.consecutiveSuccesses = 0;
        }
        chosen.updatedAt = System.currentTimeMillis();
        chosen.revision = increment(chosen.revision);
        chosen.lastOutcome = "activated";
        saveData(data());
        return snapshot(chosen);
    }

    public synchronized CombatPolicySnapshot abort(
            Owner owner, String policyId) {
        requireOwner(owner);
        StoredPolicy policy = requireOwned(data(), owner, policyId);
        policy.active = false;
        policy.state = CombatPolicyState.DISABLED;
        policy.consecutiveSuccesses = 0;
        policy.updatedAt = System.currentTimeMillis();
        policy.revision = increment(policy.revision);
        policy.lastOutcome = "aborted by policy owner";
        saveData(data());
        return snapshot(policy);
    }

    /**
     * Record one bounded execution result. Three consecutive successes promote
     * a candidate; any failure resets it to candidate.
     */
    public synchronized CombatPolicySnapshot recordOutcome(
            Owner owner,
            String policyId,
            boolean success,
            String detail) {
        requireOwner(owner);
        StoredPolicy policy = requireOwned(data(), owner, policyId);
        if (!policy.active || policy.state == CombatPolicyState.DISABLED) {
            throw new IllegalStateException(
                    "combat policy is not active for this companion");
        }
        if (success) {
            policy.totalSuccesses = increment(policy.totalSuccesses);
            policy.consecutiveSuccesses =
                    Math.min(3, policy.consecutiveSuccesses + 1);
            if (policy.consecutiveSuccesses >= 3) {
                policy.state = CombatPolicyState.TRUSTED;
            }
            policy.lastOutcome = outcome("success", detail);
        } else {
            policy.totalFailures = increment(policy.totalFailures);
            policy.consecutiveSuccesses = 0;
            policy.state = CombatPolicyState.CANDIDATE;
            // A failed candidate must not run in an unbounded retry loop.
            // Luna (or an administrator) must review and explicitly reactivate it.
            policy.active = false;
            policy.lastOutcome = outcome("failure", detail);
        }
        policy.updatedAt = System.currentTimeMillis();
        policy.revision = increment(policy.revision);
        saveData(data());
        return snapshot(policy);
    }

    public synchronized Optional<CombatPolicySnapshot> active(
            Owner owner, CombatPolicy.Binding binding) {
        requireOwner(owner);
        Objects.requireNonNull(binding, "binding");
        return data().policies.stream()
                .filter(policy -> owner.uuid().equals(policy.ownerUuid))
                .filter(policy -> policy.active)
                .filter(policy -> policy.state != CombatPolicyState.DISABLED)
                .filter(policy -> binding.equals(policy.policy.binding()))
                .filter(policy -> CombatPolicyValidator.validate(policy.policy).valid())
                .max(Comparator.comparingLong(policy -> policy.updatedAt))
                .map(CombatPolicyRepository::snapshot);
    }

    public synchronized List<CombatPolicyValidationRecord> validations(
            Owner owner, String policyId) {
        requireOwner(owner);
        return data().validations.stream()
                .filter(record -> owner.uuid().equals(record.ownerUuid))
                .filter(record -> policyId == null
                        || policyId.equals(record.policyId))
                .sorted(Comparator.comparingLong(
                        (StoredValidation record) -> record.timestamp).reversed())
                .map(CombatPolicyRepository::validationRecord)
                .toList();
    }

    private StoreData data() {
        if (cached != null) {
            return cached;
        }
        if (!Files.exists(file)) {
            cached = new StoreData();
            return cached;
        }
        try {
            StoreData loaded = GSON.fromJson(
                    Files.readString(file, StandardCharsets.UTF_8),
                    StoreData.class);
            if (loaded == null || loaded.version != FILE_VERSION) {
                throw new IllegalStateException(
                        "unsupported combat policy store version");
            }
            if (loaded.policies == null) {
                loaded.policies = new ArrayList<>();
            }
            if (loaded.validations == null) {
                loaded.validations = new ArrayList<>();
            }
            verifyLoaded(loaded);
            cached = loaded;
            return cached;
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException(
                    "could not load combat policy store " + file, error);
        }
    }

    private void saveData(StoreData data) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(
                    temp, GSON.toJson(data), StandardCharsets.UTF_8);
            try {
                Files.move(
                        temp,
                        file,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            cached = data;
        } catch (IOException error) {
            throw new IllegalStateException(
                    "could not save combat policy store " + file, error);
        }
    }

    private static void verifyLoaded(StoreData data) {
        Set<String> ownedIds = new HashSet<>();
        for (StoredPolicy stored : data.policies) {
            if (stored == null || stored.ownerUuid == null
                    || stored.policy == null || stored.policy.id() == null
                    || stored.state == null
                    || !CombatPolicyValidator.validate(stored.policy).valid()) {
                throw new IllegalStateException(
                        "combat policy store contains an invalid policy");
            }
            if (stored.active && stored.state == CombatPolicyState.DISABLED) {
                throw new IllegalStateException(
                        "disabled combat policy cannot be active");
            }
            if (!ownedIds.add(stored.ownerUuid + "\u0000" + stored.policy.id())) {
                throw new IllegalStateException(
                        "combat policy store contains a duplicate owned id");
            }
        }
    }

    private static StoredPolicy findOwned(
            StoreData data, String ownerUuid, String policyId) {
        return data.policies.stream()
                .filter(policy -> ownerUuid.equals(policy.ownerUuid))
                .filter(policy -> policyId.equals(policy.policy.id()))
                .findFirst()
                .orElse(null);
    }

    private static StoredPolicy requireOwned(
            StoreData data, Owner owner, String policyId) {
        if (policyId == null || policyId.isBlank()) {
            throw new IllegalArgumentException("policy_id is required");
        }
        StoredPolicy policy = findOwned(data, owner.uuid(), policyId);
        if (policy == null) {
            throw new IllegalArgumentException(
                    "unknown combat policy " + policyId + " for this companion");
        }
        return policy;
    }

    private static CombatPolicyValidationRecord appendValidation(
            StoreData data,
            Owner owner,
            String policyId,
            CombatPolicyValidation validation) {
        StoredValidation stored = new StoredValidation();
        stored.ownerUuid = owner.uuid();
        stored.policyId = displayPolicyId(policyId);
        stored.timestamp = System.currentTimeMillis();
        stored.valid = validation.valid();
        stored.totalTicks = validation.totalTicks();
        stored.issues = new ArrayList<>(validation.issues());
        data.validations.add(stored);
        while (data.validations.size() > MAX_VALIDATION_RECORDS) {
            data.validations.remove(0);
        }
        return validationRecord(stored);
    }

    private static CombatPolicySnapshot snapshot(StoredPolicy stored) {
        return new CombatPolicySnapshot(
                stored.policy,
                stored.state,
                stored.active,
                stored.consecutiveSuccesses,
                stored.totalSuccesses,
                stored.totalFailures,
                stored.createdAt,
                stored.updatedAt,
                stored.revision,
                stored.lastOutcome);
    }

    private static CombatPolicyValidationRecord validationRecord(
            StoredValidation stored) {
        return new CombatPolicyValidationRecord(
                stored.policyId,
                stored.timestamp,
                stored.valid,
                stored.totalTicks,
                stored.issues);
    }

    private static CombatPolicy withId(CombatPolicy draft, String id) {
        return new CombatPolicy(
                id,
                draft.name(),
                draft.binding(),
                List.copyOf(draft.steps()));
    }

    private static String newId(StoreData data, String ownerUuid) {
        String id;
        do {
            id = "combat-" + Long.toString(System.currentTimeMillis(), 36)
                    + "-" + UUID.randomUUID().toString().substring(0, 8);
        } while (findOwned(data, ownerUuid, id) != null);
        return id;
    }

    private static String displayPolicyId(String policyId) {
        if (policyId == null || policyId.isBlank()) {
            return "(new)";
        }
        StringBuilder clean = new StringBuilder(Math.min(96, policyId.length()));
        for (int index = 0; index < policyId.length() && clean.length() < 96; index++) {
            char value = policyId.charAt(index);
            if ((value >= 'a' && value <= 'z')
                    || (value >= 'A' && value <= 'Z')
                    || (value >= '0' && value <= '9')
                    || value == '_' || value == '-' || value == '.'
                    || value == ':' || value == '/') {
                clean.append(value);
            } else {
                clean.append('_');
            }
        }
        return clean.length() == 0 ? "(invalid)" : clean.toString();
    }

    private static String outcome(String kind, String detail) {
        if (detail == null || detail.isBlank()) {
            return kind;
        }
        String clean = detail.replaceAll("\\p{Cntrl}", " ").trim();
        if (clean.length() > 240) {
            clean = clean.substring(0, 240);
        }
        return kind + ": " + clean;
    }

    private static long increment(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1;
    }

    private static void requireOwner(Owner owner) {
        if (owner == null || owner.uuid() == null || owner.uuid().isBlank()) {
            throw new IllegalArgumentException("companion owner is required");
        }
    }

    public record Owner(String uuid, String name) {
        public Owner {
            name = name == null ? "" : name;
        }
    }

    public static final class PolicyRejectedException
            extends IllegalArgumentException {
        private final CombatPolicyValidation validation;

        private PolicyRejectedException(CombatPolicyValidation validation) {
            super("combat policy failed validation");
            this.validation = validation;
        }

        public CombatPolicyValidation validation() {
            return validation;
        }
    }

    private static final class StoreData {
        int version = FILE_VERSION;
        List<StoredPolicy> policies = new ArrayList<>();
        List<StoredValidation> validations = new ArrayList<>();
    }

    private static final class StoredPolicy {
        String ownerUuid;
        String ownerName;
        CombatPolicy policy;
        CombatPolicyState state;
        boolean active;
        int consecutiveSuccesses;
        long totalSuccesses;
        long totalFailures;
        long createdAt;
        long updatedAt;
        long revision;
        String lastOutcome;
    }

    private static final class StoredValidation {
        String ownerUuid;
        String policyId;
        long timestamp;
        boolean valid;
        int totalTicks;
        List<CombatPolicyValidation.Issue> issues = new ArrayList<>();
    }
}
