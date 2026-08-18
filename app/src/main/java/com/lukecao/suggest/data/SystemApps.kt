package com.lukecao.suggest.data

/**
 * Apps that are technically launchable and heavily "used", but that nobody wants a
 * suggestion for.
 *
 * Settings is the worst offender: every permission grant, every toggle, every trip
 * to Wi-Fi counts as a session, so it climbs to the top of any usage-based ranking
 * while being the last thing you want a shortcut to.
 *
 * This is a list rather than a heuristic on purpose. "Is a system app" is far too
 * broad — Phone, Messages, Camera and Clock are all system apps and all perfectly
 * good suggestions. Both AOSP and One UI package names are included so the same
 * build behaves on either.
 */
object SystemApps {

    val PACKAGES: Set<String> = setOf(
        // Settings, and the search index behind it
        "com.android.settings",
        "com.android.settings.intelligence",
        "com.samsung.android.app.settings",
        "com.samsung.android.settings",
        // Permission grants and app installs — pure plumbing surfaces
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.android.systemui",
        // Housekeeping tools
        "com.samsung.android.lool", // Device care
        "com.samsung.android.forest", // Modes and Routines / Digital Wellbeing
        "com.google.android.apps.wellbeing",
        "com.wssyncmldm", // Software update
        "com.samsung.android.dqagent",
        // Store-side plumbing, as opposed to the Play Store itself
        "com.android.vending.billing",
        "com.google.android.gms",
        "com.samsung.android.mobileservice",
    )
}
