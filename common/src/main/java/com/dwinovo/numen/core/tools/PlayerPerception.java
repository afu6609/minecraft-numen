package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.Comparator;
import java.util.UUID;

/** Shared, read-only server perception for human players. */
final class PlayerPerception {

    private PlayerPerception() {}

    /**
     * Resolve an online human by UUID, exact name, then case-insensitive name.
     * Numen bodies have their own status tool and are deliberately excluded.
     */
    static ServerPlayer resolveHuman(NumenPlayer observer, String selector) {
        if (observer == null || selector == null || selector.isBlank()) return null;
        MinecraftServer server = observer.level().getServer();
        if (server == null) return null;
        String needle = selector.trim();

        try {
            ServerPlayer byUuid = server.getPlayerList().getPlayer(UUID.fromString(needle));
            if (byUuid != null && !(byUuid instanceof NumenPlayer)) return byUuid;
        } catch (IllegalArgumentException ignored) {
            // A normal player name, not a UUID.
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!(player instanceof NumenPlayer)
                    && player.getGameProfile().getName().equals(needle)) {
                return player;
            }
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!(player instanceof NumenPlayer)
                    && player.getGameProfile().getName().equalsIgnoreCase(needle)) {
                return player;
            }
        }
        return null;
    }

    static JsonObject status(ServerPlayer player) {
        JsonObject root = new JsonObject();
        root.addProperty("uuid", player.getUUID().toString());
        root.addProperty("name", player.getGameProfile().getName());
        root.addProperty("game_mode", player.gameMode.getGameModeForPlayer().getName());
        root.addProperty("hp", player.getHealth());
        root.addProperty("max_hp", player.getMaxHealth());
        root.addProperty("absorption", player.getAbsorptionAmount());
        root.addProperty("armor", player.getArmorValue());
        root.addProperty("hunger", player.getFoodData().getFoodLevel());
        root.addProperty("saturation", player.getFoodData().getSaturationLevel());
        root.addProperty("experience_level", player.experienceLevel);
        root.addProperty("experience_progress", player.experienceProgress);

        JsonObject position = new JsonObject();
        position.addProperty("x", player.getX());
        position.addProperty("y", player.getY());
        position.addProperty("z", player.getZ());
        root.add("position", position);
        root.addProperty("dimension", player.level().dimension().location().toString());
        root.addProperty("biome", player.level().getBiome(player.blockPosition())
                .unwrapKey().map(key -> key.location().toString()).orElse("unknown"));

        JsonObject equipment = new JsonObject();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = player.getItemBySlot(slot);
            if (!stack.isEmpty()) equipment.add(slot.getName(), stack(stack));
        }
        root.add("equipment", equipment);

        var inventory = player.getInventory();
        JsonArray items = new JsonArray();
        int used = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;
            used++;
            JsonObject item = stack(stack);
            item.addProperty("slot", slot);
            item.addProperty("section", inventorySection(slot));
            items.add(item);
        }
        JsonObject backpack = new JsonObject();
        backpack.add("items", items);
        backpack.addProperty("selected_hotbar_slot", inventory.selected);
        backpack.addProperty("slots_used", used);
        backpack.addProperty("slots_total", inventory.getContainerSize());
        root.add("inventory", backpack);

        JsonArray effects = new JsonArray();
        player.getActiveEffects().stream()
                .sorted(Comparator.comparing(effect ->
                        BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect()).toString()))
                .forEach(effect -> effects.add(effect(effect)));
        root.add("effects", effects);

        root.addProperty("on_ground", player.onGround());
        root.addProperty("in_water", player.isInWater());
        root.addProperty("in_lava", player.isInLava());
        root.addProperty("on_fire", player.isOnFire());
        root.addProperty("sleeping", player.isSleeping());
        root.addProperty("air", player.getAirSupply());
        root.addProperty("max_air", player.getMaxAirSupply());
        return root;
    }

    private static JsonObject stack(ItemStack stack) {
        JsonObject item = new JsonObject();
        item.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        item.addProperty("count", stack.getCount());
        if (stack.isDamageableItem()) {
            item.addProperty("damage", stack.getDamageValue());
            item.addProperty("max_damage", stack.getMaxDamage());
        }
        if (stack.hasCustomHoverName()) {
            item.addProperty("custom_name", stack.getHoverName().getString());
        }
        return item;
    }

    private static JsonObject effect(MobEffectInstance instance) {
        JsonObject effect = new JsonObject();
        effect.addProperty(
                "effect",
                BuiltInRegistries.MOB_EFFECT.getKey(instance.getEffect()).toString());
        effect.addProperty("amplifier", instance.getAmplifier());
        effect.addProperty("duration_ticks", instance.getDuration());
        effect.addProperty("ambient", instance.isAmbient());
        effect.addProperty("visible", instance.isVisible());
        return effect;
    }

    static String inventorySection(int slot) {
        if (slot >= 0 && slot <= 8) return "hotbar";
        if (slot >= 9 && slot <= 35) return "backpack";
        if (slot >= 36 && slot <= 39) return "armor";
        if (slot == 40) return "offhand";
        return "other";
    }
}
