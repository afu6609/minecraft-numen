package com.dwinovo.numen.core.combat.policy;

import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * Body-scoped facade used by tools and the trusted per-tick executor.
 *
 * <p>This API returns declarations only. It does not interpret a policy,
 * execute a command, or mutate the world.</p>
 */
public final class CombatPolicyRuntime {

    public static final String STORE_FILE_NAME = "numen-combat-policies.json";

    private static final Map<MinecraftServer, CombatPolicyRepository> REPOSITORIES =
            new WeakHashMap<>();

    private CombatPolicyRuntime() {
    }

    public static CombatPolicySnapshot save(
            NumenPlayer companion, CombatPolicy policy) {
        return repository(companion).save(owner(companion), policy);
    }

    public static CombatPolicyValidationRecord recordValidation(
            NumenPlayer companion,
            String requestedPolicyId,
            CombatPolicyValidation validation) {
        return repository(companion).recordValidation(
                owner(companion), requestedPolicyId, validation);
    }

    public static List<CombatPolicySnapshot> policies(
            NumenPlayer companion, String policyId) {
        return repository(companion).status(owner(companion), policyId);
    }

    public static CombatPolicySnapshot activate(
            NumenPlayer companion, String policyId) {
        return repository(companion).activate(owner(companion), policyId);
    }

    public static CombatPolicySnapshot abort(
            NumenPlayer companion, String policyId) {
        return repository(companion).abort(owner(companion), policyId);
    }

    /**
     * Exact binding lookup for the server-side executor. Adapter/schema
     * mismatches intentionally return no policy instead of applying an old
     * strategy to a changed mob implementation.
     */
    public static Optional<CombatPolicySnapshot> activePolicy(
            NumenPlayer companion,
            String entityType,
            String adapterId,
            String schema) {
        return activePolicy(
                companion,
                new CombatPolicy.Binding(entityType, adapterId, schema));
    }

    public static Optional<CombatPolicySnapshot> activePolicy(
            NumenPlayer companion, CombatPolicy.Binding binding) {
        return repository(companion).active(owner(companion), binding);
    }

    public static CombatPolicySnapshot recordExecutionOutcome(
            NumenPlayer companion,
            String policyId,
            boolean success,
            String detail) {
        return repository(companion).recordOutcome(
                owner(companion), policyId, success, detail);
    }

    public static List<CombatPolicyValidationRecord> validationRecords(
            NumenPlayer companion, String policyId) {
        return repository(companion).validations(owner(companion), policyId);
    }

    static synchronized CombatPolicyRepository repository(
            NumenPlayer companion) {
        if (companion == null) {
            throw new IllegalArgumentException("companion is required");
        }
        MinecraftServer server = companion.getServer();
        if (server == null) {
            throw new IllegalStateException(
                    "combat policies require a running server");
        }
        return REPOSITORIES.computeIfAbsent(server, ignored -> {
            Path path = server.getWorldPath(LevelResource.ROOT)
                    .resolve("data")
                    .resolve(STORE_FILE_NAME);
            return new CombatPolicyRepository(path);
        });
    }

    private static CombatPolicyRepository.Owner owner(
            NumenPlayer companion) {
        return new CombatPolicyRepository.Owner(
                companion.getUUID().toString(),
                companion.getScoreboardName());
    }
}
