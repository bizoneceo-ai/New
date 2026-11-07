plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bizoneceo.foldreader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bizoneceo.foldreader"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Same fixed debug key as the snake app, so CI builds install as updates.
    signingConfigs {
        getByName("debug") {
            storeFile = file("../app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// No AndroidX on purpose: the reader only uses platform APIs (StaticLayout,
// Canvas.drawBitmapMesh, Storage Access Framework), which keeps the APK tiny.
dependencies {
}
