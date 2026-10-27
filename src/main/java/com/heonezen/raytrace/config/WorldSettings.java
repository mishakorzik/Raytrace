package com.heonezen.raytrace.config;

import com.heonezen.raytrace.util.EntityKind;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class WorldSettings {

    private final boolean enabled;
    private final List<LayerBand> layers;
    private final int maskCeilingY;
    private final boolean hideMobs;
    private final boolean hidePlayers;
    private final boolean hideVehicles;
    private final boolean hideDecorations;
    private final boolean hideItems;
    private final int surfaceBuffer;

    public WorldSettings(boolean enabled, List<LayerBand> rawLayers, boolean hideMobs, boolean hidePlayers, int surfaceBuffer) {
        this(enabled, rawLayers, hideMobs, hidePlayers, true, true, true, surfaceBuffer);
    }

    public WorldSettings(boolean enabled, List<LayerBand> rawLayers, boolean hideMobs, boolean hidePlayers, boolean hideVehicles, boolean hideDecorations, boolean hideItems, int surfaceBuffer) {
        this.enabled = enabled;
        this.hideMobs = hideMobs;
        this.hidePlayers = hidePlayers;
        this.hideVehicles = hideVehicles;
        this.hideDecorations = hideDecorations;
        this.hideItems = hideItems;
        this.surfaceBuffer = surfaceBuffer;

        List<LayerBand> sorted = new ArrayList<>(rawLayers);
        sorted.sort(Comparator.comparingInt(LayerBand::belowY).reversed());
        this.layers = List.copyOf(sorted);
        this.maskCeilingY = sorted.isEmpty() ? Integer.MIN_VALUE : sorted.get(0).belowY();
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean terrainMaskingEnabled() {
        return enabled && !layers.isEmpty();
    }

    public boolean hideMobs() {
        return enabled && hideMobs;
    }

    public boolean hidePlayers() {
        return enabled && hidePlayers;
    }

    public boolean hideVehicles() {
        return enabled && hideVehicles;
    }

    public boolean hideDecorations() {
        return enabled && hideDecorations;
    }

    public boolean hideItems() {
        return enabled && hideItems;
    }

    public boolean hides(EntityKind kind) {
        return switch (kind) {
            case PLAYER -> hidePlayers();
            case MOB -> hideMobs();
            case VEHICLE -> hideVehicles();
            case DECOR -> hideDecorations();
            case ITEM -> hideItems();
            case OTHER -> false;
        };
    }

    public boolean hidesAnyEntity() {
        return hideMobs() || hidePlayers() || hideVehicles() || hideDecorations() || hideItems();
    }

    public int maskCeilingY() {
        return maskCeilingY;
    }

    public int surfaceBuffer() {
        return surfaceBuffer;
    }

    public boolean isMaskedY(int y, int worldMinY) {
        return terrainMaskingEnabled() && y >= worldMinY && y < maskCeilingY;
    }

    public Material fakeBlockFor(int y) {
        Material result = layers.get(layers.size() - 1).block();
        for (LayerBand band : layers) {
            if (y < band.belowY()) {
                result = band.block();
            } else {
                break;
            }
        }
        return result;
    }

    public List<LayerBand> layers() {
        return layers;
    }

    public String describeLayers() {
        if (layers.isEmpty()) {
            return "none";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < layers.size(); i++) {
            LayerBand band = layers.get(i);
            if (i > 0) {
                builder.append(", ");
            }
            builder.append("below ").append(band.belowY()).append(" -> ").append(band.block().name());
        }
        return builder.toString();
    }
}
