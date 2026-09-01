import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

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

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signed with the debug key, deliberately. This is not a Play release any
            // more, so the only thing a release build is for is finding out what R8
            // broke — and it has to survive `adb install -r` to do that, which means
            // keeping the signature the installed app already carries.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // On so that the evaluation harness can be gated on a compile-time constant.
        // `applicationInfo.flags and FLAG_DEBUGGABLE` reads the same truth at runtime,
        // but R8 cannot fold it, so the whole of Replay shipped in release builds as
        // unreachable code. With BuildConfig.DEBUG the branch is dead at compile time
        // and R8 removes it outright — checked, not assumed: see README.
        buildConfig = true
    }
}

/*
 * The Kotlin JVM target, which does not live in `android { }`.
 *
 * It used to, as `kotlinOptions { jvmTarget = "17" }`. Kotlin 2.4 turned that DSL from a
 * deprecation warning into a hard error, so this is a migration rather than a preference —
 * and the replacement is a top-level `kotlin { }` block owned by the Kotlin plugin, not a
 * renamed field inside AGP's block. Worth stating because the obvious fix, moving
 * `jvmTarget` into `compileOptions` next to the Java levels, does not compile: those are
 * AGP's and this is not.
 *
 * It has to keep agreeing with `compileOptions` above. Java and Kotlin compile to the same
 * dex here, and a mismatch is only caught much later, in `dexBuilder`.
 */
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
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
}
