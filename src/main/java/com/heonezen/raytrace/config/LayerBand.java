package com.heonezen.raytrace.config;

import org.bukkit.Material;

/**
 * One horizontal band of the hidden range. Covers every Y below {@code belowY} down to the next
 * band, or the world floor for the deepest band.
 */
public record LayerBand(int belowY, Material block) {}
