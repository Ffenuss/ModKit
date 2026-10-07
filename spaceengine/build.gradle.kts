plugins { id("com.android.application") }
android {
    namespace = "io.github.ffenuss.modkit.spaceengine"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.ffenuss.modkit.spaceengine"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":analysiscore"))
    testImplementation("junit:junit:4.13.2")
}
