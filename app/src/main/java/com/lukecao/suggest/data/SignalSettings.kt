package com.lukecao.suggest.data

/**
 * Which of the costly signals the ranker is allowed to use.
 *
 * Three, and all three are here for the same reason: each one either needs a
 * permission or spends battery. Everything else the ranker knows how to measure —
 * continuity, sequence, weekly rhythm, due-ness, novelty, tap and impression feedback,
 * headphones, charging, the exploration slot — is now always on and has no switch,
 * because those cost nothing beyond the usage access the app cannot work without and
 * a toggle for each was eight questions in place of no question at all.
 *
 * Turning one of these off makes its terms neutral rather than zero, so the ranking
 * degrades to a simpler model instead of breaking.
 *
 * The two that need a permission default to **off**. Nothing that reads your
 * notifications or your calendar should start doing so because an update shipped.
 * [place] is the exception among the sensitive ones: the place term predates this
 * setting and is why location was granted in the first place, so switching it off
 * silently would remove something already asked for.
 */
data class SignalSettings(
    /**
     * Where you are: coordinates, the WiFi network as an exact place identity, and
     * whether you are on the move. Needs location, including background, and it is the
     * only signal here that can wake the radios.
     *
     * Has no effect in this release — see
     * [LOCATION_DECLARED][com.lukecao.suggest.Permissions.LOCATION_DECLARED], which the
     * ranker and the sampler both check alongside the grant, and which the settings
     * screen checks before offering the switch at all.
     *
     * Left defaulting to true rather than flipped to false, because the value only starts
     * meaning anything again in a build that asks for location, and in that build the
     * original reasoning applies: the place term predates this setting, so defaulting it
     * off would silently remove something already asked for. Nothing is read either way
     * until the runtime permission is granted, which is an explicit prompt.
     */
    val place: Boolean = true,

    /** Which apps have an unopened notification. Needs notification access. */
    val notifications: Boolean = false,

    /** Meetings, and the apps they name. Needs calendar access. */
    val calendar: Boolean = false,
)
