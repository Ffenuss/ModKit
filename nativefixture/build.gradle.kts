plugins { id("com.android.application") }
android {
    namespace = "dev.modkit.nativefixture"
    compileSdk = 37
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "dev.modkit.nativefixture"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }
    }
    externalNativeBuild { ndkBuild { path = file("src/main/jni/Android.mk") } }
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }
dependencies {
    androidTestImplementation(project(":analysiscore"))
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
val guestFixtureRoot = layout.buildDirectory.dir("generated/guestMenuFixtures")
val prepareGuestMenuFixtures = tasks.register("prepareGuestMenuFixtures") {
    dependsOn(":runtimeprobe:assembleDebug")
    inputs.files(rootProject.fileTree("spacehost/src") { include("**/*.java") })
    inputs.files(rootProject.file("runtimeprobe/src/main/java/io/github/ffenuss/modkit/runtimeprobe/RuntimeNativeBridge.java"),
        rootProject.file("runtimeprobe/src/main/java/io/github/ffenuss/modkit/runtimeprobe/RuntimeNativeTraceBuffer.java"),
        rootProject.file("spaceengine/src/androidTest/java/io/github/ffenuss/modkit/space/MenuSyncFixtureProvider.java"))
    inputs.file(rootProject.file("runtimeprobe/build/outputs/apk/debug/runtimeprobe-debug.apk"))
    outputs.dir(guestFixtureRoot)
    doLast {
        val root = guestFixtureRoot.get().asFile
        val java = root.resolve("java/io/github/ffenuss/modkit/space").apply { mkdirs() }
        rootProject.file("spacehost/src/io/github/ffenuss/modkit/space").listFiles()!!.filter { it.extension == "java" }
            .forEach { it.copyTo(java.resolve(it.name), overwrite = true) }
        rootProject.file("spaceengine/src/androidTest/java/io/github/ffenuss/modkit/space/MenuSyncFixtureProvider.java")
            .copyTo(java.resolve("MenuSyncFixtureProvider.java"), overwrite = true)
        val runtime = root.resolve("java/io/github/ffenuss/modkit/runtimeprobe").apply { mkdirs() }
        listOf("RuntimeNativeBridge.java", "RuntimeNativeTraceBuffer.java").forEach {
            rootProject.file("runtimeprobe/src/main/java/io/github/ffenuss/modkit/runtimeprobe/$it").copyTo(runtime.resolve(it), overwrite = true)
        }
        val assets = root.resolve("assets").apply { mkdirs() }
        rootProject.file("runtimeprobe/build/outputs/apk/debug/runtimeprobe-debug.apk").copyTo(assets.resolve("native-runtime.apk"), overwrite = true)
    }
}
android.sourceSets.getByName("androidTest").apply {
    java.directories.add(guestFixtureRoot.get().dir("java").asFile.absolutePath)
    assets.directories.add(guestFixtureRoot.get().dir("assets").asFile.absolutePath)
}
tasks.configureEach {
    if (name == "compileDebugAndroidTestKotlin" || name == "compileDebugAndroidTestJavaWithJavac" || name == "mergeDebugAndroidTestAssets") dependsOn(prepareGuestMenuFixtures)
}
