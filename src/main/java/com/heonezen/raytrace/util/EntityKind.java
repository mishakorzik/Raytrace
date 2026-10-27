package com.heonezen.raytrace.util;

/** What a server entity is, as far as hiding is concerned. */
public enum EntityKind {
    /** A real player. */
    PLAYER,
    /** Any living entity that is not a player or an armor stand. */
    MOB,
    /** Minecarts of every kind (chest, hopper, furnace, TNT, spawner...) and boats. */
    VEHICLE,
    /** Item frames, paintings, armor stands, leash knots and display entities. */
    DECOR,
    /** Dropped items. */
    ITEM,
    /** Arrows, experience orbs, lightning and everything else: never hidden. */
    OTHER
}
