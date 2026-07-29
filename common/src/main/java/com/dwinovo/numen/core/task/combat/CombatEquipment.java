package com.dwinovo.numen.core.task.combat;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * Item-conserving emergency equipment selection. It only swaps backpack/hotbar
 * stacks with vanilla equipment slots and never creates or deletes an item.
 */
public final class CombatEquipment {

    private CombatEquipment() {}

    public static void prepareDefensiveLoadout(NumenPlayer self) {
        equipBest(self, EquipmentSlot.HEAD);
        equipBest(self, EquipmentSlot.CHEST);
        equipBest(self, EquipmentSlot.LEGS);
        equipBest(self, EquipmentSlot.FEET);
        equipShield(self);
    }

    private static void equipBest(NumenPlayer self, EquipmentSlot wantedSlot) {
        Inventory inventory = self.getInventory();
        ItemStack equipped = self.getItemBySlot(wantedSlot);
        if (!equipped.isEmpty() && EnchantmentHelper.hasBindingCurse(equipped)) {
            return;
        }
        double bestScore = armorScore(equipped, wantedSlot);
        int bestSlot = -1;
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || self.getEquipmentSlotForItem(stack) != wantedSlot) {
                continue;
            }
            double score = armorScore(stack, wantedSlot);
            if (score > 0.0 && score > bestScore + 0.01) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        if (bestSlot >= 0) {
            swapWithEquipment(self, bestSlot, wantedSlot);
        }
    }

    private static void equipShield(NumenPlayer self) {
        if (self.getOffhandItem().getItem() instanceof ShieldItem) return;
        // Never discard the stronger one-shot death protection just to block.
        if (self.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return;
        Inventory inventory = self.getInventory();
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            if (inventory.getItem(slot).getItem() instanceof ShieldItem) {
                swapWithEquipment(self, slot, EquipmentSlot.OFFHAND);
                return;
            }
        }
    }

    private static void swapWithEquipment(
            NumenPlayer self, int inventorySlot, EquipmentSlot equipmentSlot) {
        Inventory inventory = self.getInventory();
        ItemStack candidate = inventory.getItem(inventorySlot);
        ItemStack previous = self.getItemBySlot(equipmentSlot);
        inventory.setItem(inventorySlot, previous);
        self.setItemSlot(equipmentSlot, candidate);
        inventory.setChanged();
    }

    private static double armorScore(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty()) return 0.0;
        double armor = stack.getAttributeModifiers(slot).get(Attributes.ARMOR)
                .stream()
                .filter(modifier ->
                        modifier.getOperation() == AttributeModifier.Operation.ADDITION)
                .mapToDouble(AttributeModifier::getAmount)
                .sum();
        double toughness = stack.getAttributeModifiers(slot).get(Attributes.ARMOR_TOUGHNESS)
                .stream()
                .filter(modifier ->
                        modifier.getOperation() == AttributeModifier.Operation.ADDITION)
                .mapToDouble(AttributeModifier::getAmount)
                .sum();
        int protection = EnchantmentHelper.getItemEnchantmentLevel(
                Enchantments.ALL_DAMAGE_PROTECTION, stack);
        if (armor <= 0.0 && toughness <= 0.0 && protection <= 0) {
            return 0.0;
        }
        // Armor points dominate; toughness and remaining durability break ties.
        double durability = stack.isDamageableItem()
                ? 1.0 - (double) stack.getDamageValue() / Math.max(1, stack.getMaxDamage())
                : 1.0;
        return armor * 100.0 + toughness * 10.0 + protection * 4.0 + durability;
    }
}
