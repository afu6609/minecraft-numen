package com.dwinovo.numen.core.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerPerceptionTest {

    @Test
    void labelsVanillaInventorySections() {
        assertEquals("hotbar", PlayerPerception.inventorySection(0));
        assertEquals("hotbar", PlayerPerception.inventorySection(8));
        assertEquals("backpack", PlayerPerception.inventorySection(9));
        assertEquals("backpack", PlayerPerception.inventorySection(35));
        assertEquals("armor", PlayerPerception.inventorySection(36));
        assertEquals("armor", PlayerPerception.inventorySection(39));
        assertEquals("offhand", PlayerPerception.inventorySection(40));
        assertEquals("other", PlayerPerception.inventorySection(41));
    }
}
