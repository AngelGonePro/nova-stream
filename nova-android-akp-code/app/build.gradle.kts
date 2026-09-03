plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.cosmoscraft.nova"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.cosmoscraft.nova"
        // minSdk 26 (Android 8.0, 2017) chosen deliberately — not for broad reach,
        // but because it's the first version with AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
        // (the whole reason for this app to exist) and with notification channels,
        // which a foreground playback service requires unconditionally anyway. Below
        // 26, the native audio engine would need a second, degraded code path for no
        // real benefit — this app's entire purpose is the low-latency path.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // AndroidX webkit — needed specifically for WebViewCompat.addWebMessageListener
    // with allowedOriginRules (the origin-restricted bridge — see MainActivity's own
    // comment on why this is used instead of the older addJavascriptInterface).
    implementation("androidx.webkit:webkit:1.11.0")
    // media session (lock-screen controls, Bluetooth/headset play-pause buttons,
    // Android Auto compatibility) — genuinely necessary for a real music app, not
    // just a nice-to-have; without it, a hardware play/pause button (wired headset,
    // Bluetooth headphones) has nothing to route to.
    implementation("androidx.media:media:1.7.0")
}
