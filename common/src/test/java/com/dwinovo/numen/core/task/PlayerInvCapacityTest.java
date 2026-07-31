package com.dwinovo.numen.core.task;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PlayerInvCapacityTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void fullMainInventoryRejectsANewItemType() {
        List<ItemStack> slots = filledMainInventory();

        assertEquals(
                0,
                PlayerInv.mainCapacityFor(
                        slots, new ItemStack(Items.OAK_LOG, 8)));
    }

    @Test
    void matchingPartialStackStillAcceptsGroundPickup() {
        List<ItemStack> slots = filledMainInventory();
        slots.set(12, new ItemStack(Items.OAK_LOG, 63));

        assertEquals(
                1,
                PlayerInv.mainCapacityFor(
                        slots, new ItemStack(Items.OAK_LOG, 8)));
    }

    @Test
    void emptyMainSlotContributesTheIncomingMaximumStackSize() {
        List<ItemStack> slots = filledMainInventory();
        slots.set(27, ItemStack.EMPTY);

        assertEquals(
                64,
                PlayerInv.mainCapacityFor(
                        slots, new ItemStack(Items.OAK_LOG, 8)));
    }

    @Test
    void mainItemCountSupportsPartialPickupReceipt() {
        List<ItemStack> slots = filledMainInventory();
        slots.set(2, new ItemStack(Items.OAK_LOG, 12));
        slots.set(9, new ItemStack(Items.OAK_LOG, 7));

        assertEquals(
                19,
                PlayerInv.mainCount(slots, Items.OAK_LOG));
    }

    @Test
    void completePickupIsNotDoubleCountedByTwoReceipts() {
        assertEquals(
                8,
                CollectItemsTaskGoal.receivedUnits(100, 108, 20, 28));
    }

    @Test
    void inventoryDeltaCatchesVanillaPartialPickupWithoutAStat() {
        assertEquals(
                1,
                CollectItemsTaskGoal.receivedUnits(100, 100, 63, 64));
    }

    private static List<ItemStack> filledMainInventory() {
        List<ItemStack> slots = new ArrayList<>(36);
        for (int i = 0; i < 36; i++) {
            slots.add(new ItemStack(Items.SNOWBALL, 16));
        }
        return slots;
    }
}
