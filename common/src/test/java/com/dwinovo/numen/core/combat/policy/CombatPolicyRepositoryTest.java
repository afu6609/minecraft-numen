package com.dwinovo.numen.core.combat.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatPolicyRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsOwnedPoliciesAndExactBindingActivation() throws Exception {
        Path file = tempDir.resolve("data").resolve("policies.json");
        CombatPolicyRepository repository = new CombatPolicyRepository(file);
        var momo = new CombatPolicyRepository.Owner("momo-uuid", "momo");
        var other = new CombatPolicyRepository.Owner("other-uuid", "other");

        CombatPolicySnapshot saved = repository.save(
                momo, policy(null, "minecraft:creeper", "numen:creeper", "v1"));
        assertEquals(CombatPolicyState.CANDIDATE, saved.state());
        assertFalse(saved.active());
        assertTrue(Files.exists(file));
        assertFalse(Files.exists(file.resolveSibling("policies.json.tmp")));
        assertTrue(repository.status(other, null).isEmpty());

        CombatPolicySnapshot active =
                repository.activate(momo, saved.policy().id());
        assertTrue(active.active());
        assertTrue(repository.active(
                momo, active.policy().binding()).isPresent());
        assertTrue(repository.active(
                momo,
                new CombatPolicy.Binding(
                        "minecraft:creeper", "numen:creeper", "v2")).isEmpty());

        CombatPolicyRepository reloaded = new CombatPolicyRepository(file);
        assertEquals(
                saved.policy().id(),
                reloaded.active(momo, active.policy().binding())
                        .orElseThrow().policy().id());
        assertThrows(
                IllegalArgumentException.class,
                () -> reloaded.activate(other, saved.policy().id()));
    }

    @Test
    void threeSuccessesPromoteAndFailureReturnsToCandidate() {
        CombatPolicyRepository repository =
                new CombatPolicyRepository(tempDir.resolve("policies.json"));
        var owner = new CombatPolicyRepository.Owner("momo-uuid", "momo");
        CombatPolicySnapshot saved = repository.save(
                owner, policy(null, "minecraft:skeleton", "numen:generic", "v1"));
        repository.activate(owner, saved.policy().id());

        repository.recordOutcome(owner, saved.policy().id(), true, "one");
        repository.recordOutcome(owner, saved.policy().id(), true, "two");
        CombatPolicySnapshot trusted =
                repository.recordOutcome(
                        owner, saved.policy().id(), true, "three");
        assertEquals(CombatPolicyState.TRUSTED, trusted.state());
        assertEquals(3, trusted.consecutiveSuccesses());

        CombatPolicySnapshot regressed =
                repository.recordOutcome(
                        owner, saved.policy().id(), false, "took lethal damage");
        assertEquals(CombatPolicyState.CANDIDATE, regressed.state());
        assertEquals(0, regressed.consecutiveSuccesses());
        assertEquals(1, regressed.totalFailures());
        assertFalse(regressed.active());
        assertTrue(repository.active(owner, regressed.policy().binding()).isEmpty());
    }

    @Test
    void abortDisablesAndExplicitActivationResetsCandidate() {
        CombatPolicyRepository repository =
                new CombatPolicyRepository(tempDir.resolve("policies.json"));
        var owner = new CombatPolicyRepository.Owner("momo-uuid", "momo");
        CombatPolicySnapshot saved = repository.save(
                owner, policy(null, "minecraft:spider", "numen:spider", "v1"));
        repository.activate(owner, saved.policy().id());

        CombatPolicySnapshot disabled =
                repository.abort(owner, saved.policy().id());
        assertEquals(CombatPolicyState.DISABLED, disabled.state());
        assertFalse(disabled.active());
        assertTrue(repository.active(owner, disabled.policy().binding()).isEmpty());

        CombatPolicySnapshot reactivated =
                repository.activate(owner, saved.policy().id());
        assertEquals(CombatPolicyState.CANDIDATE, reactivated.state());
        assertTrue(reactivated.active());
    }

    @Test
    void revisingPolicyResetsTrustAndKeepsOwnership() {
        CombatPolicyRepository repository =
                new CombatPolicyRepository(tempDir.resolve("policies.json"));
        var owner = new CombatPolicyRepository.Owner("momo-uuid", "momo");
        CombatPolicySnapshot saved = repository.save(
                owner, policy(null, "minecraft:zombie", "numen:generic", "v1"));
        repository.activate(owner, saved.policy().id());
        repository.recordOutcome(owner, saved.policy().id(), true, null);
        repository.recordOutcome(owner, saved.policy().id(), true, null);
        repository.recordOutcome(owner, saved.policy().id(), true, null);

        CombatPolicy revised = policy(
                saved.policy().id(),
                "minecraft:zombie",
                "numen:generic",
                "v1");
        CombatPolicySnapshot result = repository.save(owner, revised);

        assertEquals(CombatPolicyState.CANDIDATE, result.state());
        assertFalse(result.active());
        assertEquals(0, result.consecutiveSuccesses());
        assertNotEquals(saved.revision(), result.revision());
        assertEquals(3, result.totalSuccesses());
    }

    @Test
    void invalidAttemptIsPersistedButNeverBecomesExecutable() {
        Path file = tempDir.resolve("policies.json");
        CombatPolicyRepository repository = new CombatPolicyRepository(file);
        var owner = new CombatPolicyRepository.Owner("momo-uuid", "momo");
        CombatPolicy invalid = new CombatPolicy(
                null,
                "invalid",
                new CombatPolicy.Binding(
                        "minecraft:creeper", "numen:creeper", "v1"),
                List.of(new CombatPolicy.Step(
                        CombatPolicyAction.RETREAT,
                        41,
                        new CombatPolicy.Condition(null, null, null, null))));

        assertThrows(
                CombatPolicyRepository.PolicyRejectedException.class,
                () -> repository.save(owner, invalid));
        assertTrue(repository.status(owner, null).isEmpty());
        List<CombatPolicyValidationRecord> records =
                repository.validations(owner, null);
        assertEquals(1, records.size());
        assertFalse(records.get(0).valid());

        CombatPolicyRepository reloaded = new CombatPolicyRepository(file);
        assertEquals(1, reloaded.validations(owner, null).size());
    }

    @Test
    void rejectedRawPolicyIdIsSanitizedAndBoundedBeforePersistence() {
        CombatPolicyRepository repository =
                new CombatPolicyRepository(tempDir.resolve("policies.json"));
        var owner = new CombatPolicyRepository.Owner("momo-uuid", "momo");
        String hostileId = "\u0000bad id\n" + "x".repeat(200);

        CombatPolicyValidationRecord record = repository.recordValidation(
                owner,
                hostileId,
                CombatPolicyValidation.invalid("$", "test", "rejected"));

        assertTrue(record.policyId().length() <= 96);
        assertFalse(record.policyId().contains("\u0000"));
        assertFalse(record.policyId().contains("\n"));
        assertFalse(record.policyId().contains(" "));
    }

    private static CombatPolicy policy(
            String id,
            String entityType,
            String adapter,
            String schema) {
        return new CombatPolicy(
                id,
                "bounded strategy",
                new CombatPolicy.Binding(entityType, adapter, schema),
                List.of(
                        new CombatPolicy.Step(
                                CombatPolicyAction.GUARD,
                                8,
                                new CombatPolicy.Condition(
                                        "RANGED_CHARGE", 2.0, 16.0, 0.3)),
                        new CombatPolicy.Step(
                                CombatPolicyAction.SEEK_COVER,
                                12,
                                new CombatPolicy.Condition(
                                        "PROJECTILE_RELEASE", null, 24.0, 0.2))));
    }
}
