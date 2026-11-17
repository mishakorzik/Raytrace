package com.heonezen.raytrace.util;

// What a server entity is, as far as hiding is concerned.
public enum EntityKind {
    // A real player.
    PLAYER,
    // Any living entity except players and armor stands.
    MOB,
    // Minecarts of every kind and boats.
    VEHICLE,
    // Item frames, paintings, armor stands, leash knots and displays.
    DECOR,
    // Dropped items and experience orbs.
    ITEM,
    // Arrows, tridents, thrown items, fireballs, skulls, bullets, hooks, fireworks, wind charges.
    PROJECTILE,
    // Falling blocks, TNT, end crystals, eyes of ender, fangs, effect clouds, interactions, ominous spawners.
    MISC,
    // Lightning, markers and anything unknown: never hidden.
    OTHER
}
