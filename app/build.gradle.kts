import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * The upload key, when there is one.
 *
 * Read from `keystore.properties` at the repo root, which is gitignored and is not
 * created by the build: generating the keystore means choosing a password, and that
 * password belongs to whoever owns the Play listing. See README, "Signing".
 *
 * Absent on a machine that has never released — which is the normal case, and has to
 * keep working, or `assembleRelease` cannot be used to find out what R8 breaks.
 */
val uploadKeystore = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

/**
 * The AdMob unit IDs, when there are any.
 *
 * Read from `admob.properties` at the repo root, gitignored for the same reason as the
 * keystore: it identifies somebody's earning account, and a repo is not where that
 * belongs. Wants `appId` and `bannerUnitId`.
 *
 * Absent on a machine that has never had an AdMob account, which is the normal case and
 * has to keep working — so the fallback is Google's *published* test IDs. Those are safe
 * to commit, safe to click, and serve a real ad marked "Test Ad".
 */
val admob = Properties().apply {
    val file = rootProject.file("admob.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

/**
 * Google's public test IDs, documented at
 * developers.google.com/admob/android/test-ads. Not secrets and not placeholders — they
 * are live units that always fill.
 *
 * The app ID has to reach the manifest whatever happens: the ads SDK reads it from a
 * `meta-data` tag at initialisation and **throws** if it is missing or malformed, so
 * there is no version of "leave it out and see" that works.
 */
val testAdMobAppId = "ca-app-pub-3940256099942544~3347511713"
val testBannerUnitId = "ca-app-pub-3940256099942544/9214589741"

android {
    namespace = "com.lukecao.suggest"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lukecao.suggest"
        minSdk = 31

        /*
         * 36 because Play requires it, not because anything here wants it.
         *
         * From 31 August 2026 a new app cannot be submitted below Android 16, and the
         * timeline makes this unavoidable rather than optional: a new personal developer
         * account owes a 14-day closed test with 12 testers before it may even apply for
         * production, so the earliest possible submission is already past the date. An
         * extension to 1 November 2026 can be requested from the Console, which buys
         * time and does not remove the requirement.
         *
         * The audit of what Android 16 changes for a target bump is in the README under
         * "Targeting Android 16, and what that actually changed". The short version is
         * that the one change that could have hurt — edge-to-edge becoming impossible to
         * opt out of — costs nothing here, because targetSdk 35 already enforced it and
         * this app never used the opt-out. The one item that could not be settled by
         * reading code was whether Android 16's tighter job quotas still let the
         * 15-minute refresh land; since measured on device, and they defer it without
         * ever killing it — 85 runs in 16.1 hours, worst gap 120.8 minutes in deep Doze.
         *
         * What the audit did miss, because the wrong code was absent rather than
         * present, is that edge-to-edge from 35 also removed the scrim the platform drew
         * behind the system bars, leaving nothing to choose contrasting icons. See
         * res/values/themes.xml.
         */
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (uploadKeystore.isNotEmpty()) {
            create("upload") {
                storeFile = rootProject.file(uploadKeystore.getProperty("storeFile"))
                storePassword = uploadKeystore.getProperty("storePassword")
                keyAlias = uploadKeystore.getProperty("keyAlias")
                keyPassword = uploadKeystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false

            // Always the test IDs, with no way to opt out. Ad traffic from a debug build
            // is a developer poking at their own app, and impressions or clicks on a live
            // unit from that traffic are invalid activity — the penalty for which is
            // having the AdMob account closed, with whatever it had earned. Reading
            // admob.properties here would make that one careless `assembleDebug` away.
            manifestPlaceholders["admobAppId"] = testAdMobAppId
            buildConfigField("String", "BANNER_UNIT_ID", "\"$testBannerUnitId\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Falls back to the debug key when there is no upload keystore, so that a
            // release build can be assembled and installed to find out whether R8 broke
            // anything. This cannot ship by accident: Play rejects a debug-signed
            // upload outright, so the failure lands at the upload step rather than on
            // somebody's phone.
            signingConfig = signingConfigs.findByName("upload")
                ?: signingConfigs.getByName("debug")

            // Falls back to the test IDs for the same reason the signing config falls
            // back to the debug key: a release build has to be assemblable on a machine
            // that has never released, or it cannot be used to find out what R8 broke.
            // A test ad is a visible, labelled failure — "Test Ad" is printed across it —
            // so this cannot ship earning nothing without somebody noticing.
            val appId = admob.getProperty("appId")
            val bannerId = admob.getProperty("bannerUnitId")
            if (appId.isNullOrBlank() || bannerId.isNullOrBlank()) {
                logger.warn(
                    "admob.properties missing appId/bannerUnitId — release build will " +
                        "serve Google's test ads and earn nothing.",
                )
            }
            manifestPlaceholders["admobAppId"] = appId ?: testAdMobAppId
            buildConfigField("String", "BANNER_UNIT_ID", "\"${bannerId ?: testBannerUnitId}\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // On so that the evaluation harness can be gated on a compile-time constant.
        // `applicationInfo.flags and FLAG_DEBUGGABLE` reads the same truth at runtime,
        // but R8 cannot fold it, so the whole of Replay and its card shipped to the
        // store as unreachable code. With BuildConfig.DEBUG the branch is dead at
        // compile time and R8 removes it outright — checked, not assumed: see README.
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.glance.appwidget)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Reachable only from ui/ and monetize/. Nothing in widget/, work/, rank/ or sense/
    // touches these, which is deliberate and checked — see README, "Ads".
    implementation(libs.play.services.ads)
    implementation(libs.billing)
    implementation(libs.user.messaging.platform)
}
