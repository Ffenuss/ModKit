plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.ffenuss.modkit.runtimeprobe"
    ndkVersion = "28.2.13676358"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "io.github.ffenuss.modkit.runtimeprobe.payload"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1"

        ndk {
            abiFilters += listOf(
                "arm64-v8a",
                "armeabi-v7a",
                "x86",
                "x86_64",
            )
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
