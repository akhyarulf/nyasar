package com.nyasar.app.ui.components

/**
 * The 3 states the map camera can be in relative to the user's GPS
 * position — cycled by tapping the recenter button, same convention as
 * Google Maps' own location button. Replaces the old followMode Boolean +
 * rotateWithHeading Boolean pair, which allowed a 4th, meaningless
 * combination (heading-up rotation with follow off).
 */
enum class CameraFollowMode {
    /** User has panned/zoomed manually. Camera does not track GPS at all. */
    FREE,

    /** Camera recenters on the user on every fix; map stays north-up. */
    FOLLOW_NORTH_UP,

    /** Camera recenters on the user on every fix AND rotates so "up" on
     *  screen matches the user's current GPS heading (turn-by-turn feel). */
    FOLLOW_HEADING
}
