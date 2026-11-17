package com.heonezen.raytrace.config;

import com.heonezen.raytrace.util.EntityKind;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldSettingsTest {

    private static WorldSettings overworld() {
        // Deliberately out of order: the bands must be sorted top-down.
        return new WorldSettings(true, List.of(new LayerBand(0, Material.DEEPSLATE), new LayerBand(128, Material.STONE)), true, true, 4);
    }

    @Test
    void bandsPickTheFakeBlockByHeight() {
        WorldSettings settings = overworld();
        assertEquals(128, settings.maskCeilingY());
        assertEquals(Material.STONE, settings.fakeBlockFor(127));
        assertEquals(Material.STONE, settings.fakeBlockFor(0));
        assertEquals(Material.DEEPSLATE, settings.fakeBlockFor(-1));
        assertEquals(Material.DEEPSLATE, settings.fakeBlockFor(-64));
    }

    @Test
    void maskedRangeIsBelowTheCeilingAndAboveTheFloor() {
        WorldSettings settings = overworld();
        assertTrue(settings.isMaskedY(-64, -64));
        assertTrue(settings.isMaskedY(127, -64));
        assertFalse(settings.isMaskedY(128, -64));
        assertFalse(settings.isMaskedY(-65, -64));
    }

    @Test
    void eachKindFollowsItsOwnSwitch() {
        WorldSettings settings = new WorldSettings(true, List.of(), false, true, false, true, false, true, false, 4);
        assertFalse(settings.hides(EntityKind.MOB));
        assertTrue(settings.hides(EntityKind.PLAYER));
        assertFalse(settings.hides(EntityKind.VEHICLE));
        assertTrue(settings.hides(EntityKind.DECOR));
        assertFalse(settings.hides(EntityKind.ITEM));
        assertTrue(settings.hides(EntityKind.PROJECTILE));
        assertFalse(settings.hides(EntityKind.MISC));
        assertFalse(settings.hides(EntityKind.OTHER), "OTHER is never hidden");
        assertTrue(settings.hidesAnyEntity());
    }

    @Test
    void aDisabledWorldHidesNothing() {
        WorldSettings settings = new WorldSettings(false, List.of(new LayerBand(64, Material.STONE)), true, true, true, true, true, true, true, 4);
        assertFalse(settings.terrainMaskingEnabled());
        assertFalse(settings.hidesAnyEntity());
        for (EntityKind kind : EntityKind.values()) {
            assertFalse(settings.hides(kind), kind.name());
        }
    }

    @Test
    void noLayersMeansNoTerrainMasking() {
        WorldSettings settings = new WorldSettings(true, List.of(), true, true, 4);
        assertFalse(settings.terrainMaskingEnabled());
        assertTrue(settings.hidesAnyEntity());
    }
}
