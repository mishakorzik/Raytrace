package com.heonezen.raytrace.config;

import org.bukkit.Material;

// One band of fake terrain: every Y below belowY, down to the next band or the world floor.
public record LayerBand(int belowY, Material block) {}
