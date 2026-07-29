package com.dwinovo.numen.core.combat.observe;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.Map;

/**
 * Extension point for mod-specific mobs. An adapter reads state only; it must
 * never mutate the entity or world.
 */
public interface CombatObservationAdapter {

    String id();

    /**
     * Exact fail-closed policy schema for this adapter's facts/intent semantics.
     * A mod adapter must change this token whenever its telegraph contract changes.
     */
    default String schemaId() {
        return CombatObservationService.POLICY_SCHEMA;
    }

    /** Higher priority adapters win when more than one supports an entity. */
    int priority();

    boolean supports(LivingEntity entity);

    Result observe(Context context);

    record Context(NumenPlayer observer, LivingEntity entity, CombatFacts facts) {}

    record Result(CombatIntent intent, Map<String, Object> state) {
        public Result {
            if (intent == null) {
                throw new IllegalArgumentException("intent is required");
            }
            state = state == null ? Map.of() : Map.copyOf(state);
        }
    }
}
