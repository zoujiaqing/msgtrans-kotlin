plugins {
    id("com.android.application") version "8.13.2"
    kotlin("android") version "2.1.21"
}

// The coordinates under test; override with -PmsgtransQuicVersion=… etc. when staging a release.
val msgtransQuic = providers.gradleProperty("msgtransQuicVersion").getOrElse("0.2.0")
val msgtransWebSocket = providers.gradleProperty("msgtransWebSocketVersion").getOrElse("0.2.0")
val tls = providers.gradleProperty("tlsVersion").getOrElse("0.3.0")
val openssl = providers.gradleProperty("opensslVersion").getOrElse("4.0.2-3")

android {
    namespace = "com.netonstream.smoke"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.netonstream.smoke"
        minSdk = 24   // the stack's floor (interop/android-min-api.py)
        targetSdk = 36
        versionCode = 1
        versionName = "1"
        // openssl-android-natives has the 64-bit ABIs only; without the filter a 32-bit device would install an APK
        // whose other libraries (zstd-kmp ships armeabi-v7a and x86) load but libneton_openssl does not.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")   // installable; never distributed
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
    // The openssl JVM jar carries the desktop JNI libraries as resources; an app takes the Android ones from
    // openssl-android-natives instead, so the desktop ones stay out of the APK.
    packaging { resources { excludes += "META-INF/native/**" } }
}

dependencies {
    implementation("com.netonstream:msgtrans-quic:$msgtransQuic")
    implementation("com.netonstream:msgtrans-websocket:$msgtransWebSocket")
    implementation("com.netonstream:tls-websocket:$tls")
    implementation("com.netonstream:openssl-android-natives:$openssl")
}
