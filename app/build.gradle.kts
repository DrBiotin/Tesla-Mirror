plugins {
    id("com.android.application")
}

android {
    namespace = "com.apptesla.mirror"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.apptesla.mirror"
        minSdk = 29          // Android 10: internal audio capture
        targetSdk = 36       // Android 16
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Personal use: sign release with the debug key so the APK installs directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}
