package com.dwinovo.numen.core.task.combat;

import com.dwinovo.numen.core.combat.observe.CombatAction;
import com.dwinovo.numen.core.combat.observe.CombatObservation;
import com.dwinovo.numen.core.task.combat.CombatDecisionInput.AttackPhase;
import com.dwinovo.numen.core.task.combat.CombatDecisionInput.EngagementDirective;
import com.dwinovo.numen.core.task.combat.CombatDecisionInput.Loadout;
import com.dwinovo.numen.core.task.combat.CombatDecisionInput.RangedWeapon;
import com.dwinovo.numen.core.task.combat.CombatDecisionInput.SelfState;
import com.dwinovo.numen.core.task.combat.CombatDecisionInput.StatusEffects;
import com.dwinovo.numen.core.task.combat.CombatDecisionInput.TerrainState;
import com.dwinovo.numen.core.task.combat.CombatDecisionInput.ThreatGroup;
import com.dwinovo.numen.core.task.combat.CombatDecisionInput.ThreatType;
import com.dwinovo.numen.core.task.combat.CombatDecisionInput.Vitals;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.common.collect.Multimap;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.FlyingMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.alchemy.PotionUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Cheap server-thread translation from authoritative Minecraft state into the
 * pure, explainable combat decision DTO.
 */
public final class CombatDecisionInputFactory {

    private CombatDecisionInputFactory() {}

    public static CombatDecisionInput create(
            NumenPlayer self,
            List<? extends LivingEntity> threats,
            Map<Integer, CombatObservation> observations,
            TerrainState terrain,
            EngagementDirective directive) {
        return new CombatDecisionInput(
                new SelfState(vitals(self), loadout(self), effects(self)),
                groupThreats(self, threats, observations),
                terrain,
                directive);
    }

    private static Vitals vitals(NumenPlayer self) {
        return new Vitals(
                self.getHealth(),
                self.getMaxHealth(),
                self.getAbsorptionAmount(),
                self.getFoodData().getFoodLevel(),
                self.getArmorValue(),
                self.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
    }

    private static Loadout loadout(NumenPlayer self) {
        boolean hasMelee = false;
        double bestMeleeDamage = 0.0;
        RangedWeapon ranged = RangedWeapon.NONE;
        int arrows = 0;
        int rockets = 0;
        boolean chargedCrossbow = false;
        boolean shield = self.getOffhandItem().getItem() instanceof ShieldItem
                && !self.getCooldowns().isOnCooldown(Items.SHIELD);
        int food = 0;
        int healing = 0;

        var inventory = self.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;
            double damage = attackDamage(stack);
            if (damage > 0.0) {
                hasMelee = true;
                bestMeleeDamage = Math.max(bestMeleeDamage, damage);
            }
            if (stack.getItem() instanceof BowItem) {
                ranged = ranged == RangedWeapon.CROSSBOW
                        ? RangedWeapon.CROSSBOW : RangedWeapon.BOW;
            } else if (stack.getItem() instanceof CrossbowItem) {
                ranged = RangedWeapon.CROSSBOW;
                chargedCrossbow |= CrossbowItem.isCharged(stack);
            }
            if (stack.getItem() instanceof ArrowItem) {
                arrows += stack.getCount();
            } else if (stack.getItem() instanceof FireworkRocketItem) {
                rockets += stack.getCount();
            }
            if (stack.getItem().getFoodProperties() != null) {
                food += stack.getCount();
            }
            if (isHealing(stack)) {
                healing += stack.getCount();
            }
        }

        int ammunition = switch (ranged) {
            case CROSSBOW -> arrows + rockets + (chargedCrossbow ? 1 : 0);
            case BOW -> arrows;
            default -> 0;
        };
        return new Loadout(
                hasMelee, bestMeleeDamage, ranged, ammunition, shield, food, healing);
    }

    private static StatusEffects effects(NumenPlayer self) {
        return new StatusEffects(
                effectLevel(self, MobEffects.DAMAGE_RESISTANCE),
                effectLevel(self, MobEffects.DAMAGE_BOOST),
                effectLevel(self, MobEffects.MOVEMENT_SPEED),
                effectLevel(self, MobEffects.REGENERATION),
                effectLevel(self, MobEffects.WEAKNESS),
                effectLevel(self, MobEffects.MOVEMENT_SLOWDOWN),
                effectLevel(self, MobEffects.POISON),
                effectLevel(self, MobEffects.WITHER),
                effectLevel(self, MobEffects.BLINDNESS));
    }

    private static List<ThreatGroup> groupThreats(
            NumenPlayer self,
            List<? extends LivingEntity> threats,
            Map<Integer, CombatObservation> observations) {
        Map<ThreatType, List<LivingEntity>> grouped = new EnumMap<>(ThreatType.class);
        for (LivingEntity threat : threats) {
            grouped.computeIfAbsent(typeOf(threat), ignored -> new ArrayList<>()).add(threat);
        }

        List<ThreatGroup> result = new ArrayList<>(grouped.size());
        grouped.forEach((type, members) -> {
            LivingEntity nearest = members.stream()
                    .min(Comparator.comparingDouble(self::distanceToSqr))
                    .orElseThrow();
            AttackPhase phase = members.stream()
                    .map(entity -> phaseOf(observations.get(entity.getId())))
                    .max(Comparator.comparingInt(CombatDecisionInputFactory::phaseUrgency))
                    .orElse(AttackPhase.UNKNOWN);
            boolean targetingSelf = members.stream().anyMatch(entity ->
                    entity instanceof Mob mob && mob.getTarget() == self);
            boolean lineOfSight = members.stream().anyMatch(entity ->
                    entity.hasLineOfSight(self));
            double maxHealth = Math.max(1.0, nearest.getMaxHealth());
            double strongestMaxHealth = members.stream()
                    .mapToDouble(LivingEntity::getMaxHealth)
                    .max()
                    .orElse(maxHealth);
            double attackDamage = members.stream()
                    .mapToDouble(CombatDecisionInputFactory::attackDamage)
                    .max()
                    .orElse(0.0);
            boolean rangedCapable = members.stream().anyMatch(entity ->
                    entity instanceof RangedAttackMob
                            || observedRanged(observations.get(entity.getId())));
            boolean airborne = members.stream().anyMatch(entity ->
                    entity instanceof FlyingMob || entity instanceof Phantom);
            boolean bossLike = strongestMaxHealth >= 80.0
                    || attackDamage >= 12.0;
            result.add(new ThreatGroup(
                    type,
                    members.size(),
                    self.distanceTo(nearest),
                    lineOfSight,
                    targetingSelf,
                    phase,
                    nearest.getHealth() / maxHealth,
                    strongestMaxHealth,
                    attackDamage,
                    rangedCapable,
                    airborne,
                    bossLike));
        });
        result.sort(Comparator.comparing(ThreatGroup::nearestDistance));
        return List.copyOf(result);
    }

    private static ThreatType typeOf(LivingEntity entity) {
        if (entity instanceof Creeper) return ThreatType.CREEPER;
        if (entity instanceof AbstractSkeleton) return ThreatType.SKELETON;
        if (entity instanceof Spider) return ThreatType.SPIDER;
        if (entity instanceof Phantom) return ThreatType.PHANTOM;
        if (entity instanceof Zombie) return ThreatType.ZOMBIE;
        if (entity instanceof Player) return ThreatType.PLAYER;
        if (entity instanceof IronGolem) return ThreatType.IRON_GOLEM;
        return ThreatType.OTHER_HOSTILE;
    }

    private static AttackPhase phaseOf(CombatObservation observation) {
        if (observation == null || observation.intent() == null) {
            return AttackPhase.UNKNOWN;
        }
        if (observation.intent().confidence() < 0.5) {
            return AttackPhase.UNKNOWN;
        }
        CombatAction action = observation.intent().action();
        return switch (action) {
            case APPROACH -> AttackPhase.APPROACHING;
            case MELEE_WINDUP -> AttackPhase.MELEE_WINDUP;
            case RANGED_CHARGE -> observation.intent().phase()
                    == com.dwinovo.numen.core.combat.observe.CombatPhase.WINDUP
                    || observation.intent().confidence() >= 0.85
                    ? AttackPhase.RANGED_CHARGE : AttackPhase.UNKNOWN;
            case PROJECTILE_RELEASE -> AttackPhase.PROJECTILE_RELEASED;
            case AOE_CHARGE -> AttackPhase.EXPLOSION_CHARGE;
            case DASH -> AttackPhase.DIVING;
            case RECOVER -> AttackPhase.RECOVERING;
            case IDLE -> AttackPhase.IDLE;
            default -> AttackPhase.UNKNOWN;
        };
    }

    private static int phaseUrgency(AttackPhase phase) {
        return switch (phase) {
            case EXPLOSION_CHARGE -> 9;
            case PROJECTILE_RELEASED -> 8;
            case DIVING -> 7;
            case RANGED_CHARGE -> 6;
            case MELEE_WINDUP -> 5;
            case APPROACHING -> 3;
            case UNKNOWN -> 2;
            case RECOVERING -> 1;
            case IDLE -> 0;
        };
    }

    private static double attackDamage(ItemStack stack) {
        Multimap<Attribute, AttributeModifier> modifiers =
                stack.getAttributeModifiers(EquipmentSlot.MAINHAND);
        double damage = 0.0;
        for (AttributeModifier modifier : modifiers.get(Attributes.ATTACK_DAMAGE)) {
            if (modifier.getOperation() == AttributeModifier.Operation.ADDITION) {
                damage += modifier.getAmount();
            }
        }
        return damage;
    }

    private static double attackDamage(LivingEntity entity) {
        var attribute = entity.getAttribute(Attributes.ATTACK_DAMAGE);
        return attribute == null ? 0.0 : Math.max(0.0, attribute.getValue());
    }

    private static boolean observedRanged(CombatObservation observation) {
        if (observation == null || observation.intent() == null) return false;
        return observation.intent().action() == CombatAction.RANGED_CHARGE
                || observation.intent().action() == CombatAction.PROJECTILE_RELEASE;
    }

    private static boolean isHealing(ItemStack stack) {
        if (stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE)
                || stack.is(Items.TOTEM_OF_UNDYING)) {
            return true;
        }
        return PotionUtils.getMobEffects(stack).stream().anyMatch(effect ->
                effect.getEffect() == MobEffects.HEAL
                        || effect.getEffect() == MobEffects.REGENERATION);
    }

    private static int effectLevel(NumenPlayer self, MobEffect effect) {
        MobEffectInstance instance = self.getEffect(effect);
        return instance == null ? 0 : instance.getAmplifier() + 1;
    }
}
