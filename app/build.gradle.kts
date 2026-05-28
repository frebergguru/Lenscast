plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "guru.freberg.lenscast"
    compileSdk = 36

    defaultConfig {
        applicationId = "guru.freberg.lenscast"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"

        // Locales bundled into the APK. Order doesn't matter; "en" must stay because it's
        // our default. "nb" exposes the Norwegian Bokmål translation (res/values-nb). Add
        // any new language code here when adding a values-XX folder — without it, AAPT
        // strips the translated strings from the final arsc and the LocaleManager call
        // silently falls back to "en".
        resourceConfigurations += listOf("en", "nb")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // No applicationId/versionName suffixes — debug and release share the same
            // package so `adb install -r app-debug.apk` upgrades whichever variant the
            // user has installed (signed with the same debug keystore either way).
        }
        release {
            // Sign with the auto-generated debug keystore. This is *not* a production
            // signing key — it's a hobby-project convenience so sideloaded GitHub-release
            // APKs install without "unsigned" prompts. If we ever go Play Store, swap in
            // a real keystore via a credentials gradle file kept out of git.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Kotlin 2.2 deprecated the kotlinOptions extension in favour of the compilerOptions
    // DSL — same knobs, less ceremony.
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        named("main") {
            java.srcDirs("src/main/kotlin")
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                // Bouncy Castle ships per-Java-version OSGI manifests that conflict
                // across bcprov, bcpkix and bcutil. We don't use OSGi.
                "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.coroutines.android)

    // HTTPS — software-side cert + RSA key generation. AndroidKeyStore's TLS server
    // story is unreliable across vendor keymasters (Conscrypt's RSA upcalls hit
    // KM_TAG digest restrictions that can't be satisfied even with the broadest
    // KeyGenParameterSpec on some devices). Bouncy Castle builds the cert; the key
    // lives in a PKCS12 file in app-private storage.
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)

    // QR-code generation for the Connection card. Just the pure-Java core lib —
    // we render the BitMatrix into a Compose Canvas ourselves, no Android UI dep.
    implementation(libs.zxing.core)

    // WebRTC SDK (community fork of upstream libwebrtc). Adds ~10 MB after R8.
    // Used by [streaming.webrtc.WebRtcManager] — owns Camera2 via Camera2Capturer and
    // serves a browser-native sub-100 ms stream via the /webrtc/offer endpoint on the
    // WebControlServer. Mutually exclusive with MJPEG / RTSP (the camera is single-tenant).
    implementation(libs.webrtc.android)

    // SFTP upload for finished recordings (RTSP path). sshj reuses our Bouncy Castle
    // for crypto; slf4j-nop quiets its logging since we don't have a logging backend.
    implementation(libs.sshj)
    implementation(libs.slf4j.nop)

    // Only used for `AppCompatDelegate.setApplicationLocales` — the cross-API shim for the
    // platform LocaleManager (API 33+). We don't use AppCompatActivity or AppCompat themes.
    implementation(libs.androidx.appcompat)

    // libsrt — Haivision's SRT transport. We use ThibaultBee's well-maintained Android
    // wrapper which ships prebuilt .so for arm64-v8a, armeabi-v7a, x86_64 (~600 KB each).
    // Pure Kotlin Socket API; we hand-roll the MPEG-TS muxer on top.
    implementation(libs.srtdroid.core)
    implementation(libs.srtdroid.ktx)

    // JVM unit tests for pure data paths (SDP builder, RTCP SR builder, etc.).
    // The streaming / camera pipeline tests stay deferred — they need Android stubs.
    testImplementation("junit:junit:4.13.2")
}
