plugins { id("com.android.application") }
android {
    namespace = "dev.modkit.fixture"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.modkit.fixture"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
