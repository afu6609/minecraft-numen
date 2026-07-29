package com.dwinovo.numen.core.task.combat;

import com.dwinovo.numen.core.act.Ballistics;
import com.dwinovo.numen.core.act.Interaction;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;

/** One small stateful ranged-combat primitive for the survival chain. */
public final class CombatRangedExecutor {

    private static final double ARROW_GRAVITY = 0.05;
    private static final double ARROW_DRAG = 0.99;
    private static final double ARROW_HITBOX_RADIUS = 0.5;
    private static final int BOW_DRAW_TICKS = 20;
    private static final int CROSSBOW_LOAD_TICKS = 30;
    private static final int SHOT_COOLDOWN_TICKS = 8;
    private static final int AIM_CACHE_TICKS = 5;

    public enum Status { RUNNING, FIRED, FAILED }

    private Interaction use;
    private int cooldown;
    private boolean loadingCrossbow;
    private int cachedAimTargetId = -1;
    private long cachedAimGameTime = Long.MIN_VALUE;
    private double cachedAimVelocity;
    private Ballistics.Aim cachedAim;

    public Status tick(NumenPlayer self, LivingEntity target) {
        if (target == null || !target.isAlive()) {
            stop(self);
            return Status.FAILED;
        }
        if (cooldown > 0) {
            cooldown--;
            InputDriver.halt(self);
            return Status.RUNNING;
        }

        int slot = findRangedWeapon(self);
        if (slot < 0) {
            stop(self);
            return Status.FAILED;
        }
        self.holdInHand(slot);
        ItemStack held = self.getMainHandItem();

        if (held.getItem() instanceof CrossbowItem) {
            if (!CrossbowItem.isCharged(held) && !hasProjectile(self)) {
                stop(self);
                return Status.FAILED;
            }
            return tickCrossbow(self, target, held);
        }
        if (!(held.getItem() instanceof BowItem)) {
            return Status.FAILED;
        }
        if (!hasProjectile(self)) {
            stop(self);
            return Status.FAILED;
        }

        Ballistics.Aim aim = cachedAim(self, target, 3.0);
        if (aim == null) {
            stop(self);
            return Status.FAILED;
        }
        InputDriver.lookAt(self, aim.lookPoint());
        if (use == null) {
            use = Interaction.useInAir(
                    self, InteractionHand.MAIN_HAND, Interaction.Timing.hold(BOW_DRAW_TICKS));
        }
        Interaction.Status status = use.tick();
        if (status == Interaction.Status.RUNNING) {
            return Status.RUNNING;
        }
        boolean fired = status == Interaction.Status.DONE;
        use.stop();
        use = null;
        cooldown = SHOT_COOLDOWN_TICKS;
        return fired ? Status.FIRED : Status.FAILED;
    }

    private Status tickCrossbow(
            NumenPlayer self, LivingEntity target, ItemStack crossbow) {
        if (!CrossbowItem.isCharged(crossbow)) {
            if (use == null) {
                loadingCrossbow = true;
                use = Interaction.useInAir(
                        self,
                        InteractionHand.MAIN_HAND,
                        Interaction.Timing.hold(CROSSBOW_LOAD_TICKS));
            }
            Interaction.Status status = use.tick();
            if (status == Interaction.Status.RUNNING) return Status.RUNNING;
            use.stop();
            use = null;
            loadingCrossbow = false;
            return status == Interaction.Status.DONE ? Status.RUNNING : Status.FAILED;
        }

        Ballistics.Aim aim = cachedAim(self, target, 3.15);
        if (aim == null) return Status.FAILED;
        InputDriver.lookAt(self, aim.lookPoint());
        if (use == null) {
            use = Interaction.useInAir(
                    self, InteractionHand.MAIN_HAND, Interaction.Timing.once());
        }
        Interaction.Status status = use.tick();
        if (status == Interaction.Status.RUNNING) return Status.RUNNING;
        boolean fired = status == Interaction.Status.DONE;
        use.stop();
        use = null;
        cooldown = SHOT_COOLDOWN_TICKS;
        return fired ? Status.FIRED : Status.FAILED;
    }

    public void stop(NumenPlayer self) {
        // Clear vanilla item-use even if our local Interaction handle was
        // already lost; stop must be a fail-closed cancellation boundary.
        if (self.isUsingItem()) {
            self.stopUsingItem();
        }
        if (use != null) {
            // Interaction.stop() intentionally releases held-use actions. That
            // is correct for a completed draw, but an abort must not loose a
            // weak arrow or finish loading a crossbow. Clear vanilla item-use
            // first so Interaction.stop() only halts its local input state.
            use.stop();
            use = null;
        }
        loadingCrossbow = false;
        cooldown = 0;
        cachedAimTargetId = -1;
        cachedAimGameTime = Long.MIN_VALUE;
        cachedAim = null;
    }

    public boolean active() {
        return use != null || loadingCrossbow;
    }

    private Ballistics.Aim cachedAim(
            NumenPlayer self, LivingEntity target, double velocity) {
        long now = self.level().getGameTime();
        if (cachedAimTargetId == target.getId()
                && cachedAimVelocity == velocity
                && now - cachedAimGameTime < AIM_CACHE_TICKS) {
            return cachedAim;
        }
        cachedAimTargetId = target.getId();
        cachedAimVelocity = velocity;
        cachedAimGameTime = now;
        cachedAim = Ballistics.findArrowShot(
                self.level(),
                self,
                target,
                velocity,
                ARROW_GRAVITY,
                ARROW_DRAG,
                ARROW_HITBOX_RADIUS,
                32.0,
                true);
        return cachedAim;
    }

    private static int findRangedWeapon(NumenPlayer self) {
        var inventory = self.getInventory();
        int bow = -1;
        int crossbow = -1;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.getItem() instanceof CrossbowItem) {
                if (CrossbowItem.isCharged(stack)) return slot;
                if (crossbow < 0) crossbow = slot;
            }
            if (bow < 0 && stack.getItem() instanceof BowItem) bow = slot;
        }
        return crossbow >= 0 ? crossbow : bow;
    }

    private static boolean hasProjectile(NumenPlayer self) {
        return !self.getProjectile(self.getMainHandItem()).isEmpty();
    }
}
