import java.security.MessageDigest

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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

// Exercise the exact host loader on owned Android fixtures, without a proprietary host.
val deviceFixtureRoot = layout.buildDirectory.dir("generated/spaceDeviceFixtures")
val prepareSpaceDeviceFixtures = tasks.register("prepareSpaceDeviceFixtures") {
    dependsOn("assembleDebug", ":runtimeprobe:assembleDebug", ":nativefixture:assembleDebug")
    inputs.files(
        rootProject.file("spacehost/src/io/github/ffenuss/modkit/space/SpaceEngine.java"),
        rootProject.file("spacehost/src/io/github/ffenuss/modkit/space/SourceInventory.java"),
        rootProject.file("spacehost/src/io/github/ffenuss/modkit/space/SpacePolicy.java"),
        rootProject.file("spacehost/src/io/github/ffenuss/modkit/space/MenuProfile.java"),
        rootProject.file("spacehost/src/io/github/ffenuss/modkit/space/MenuProfileStore.java"),
        rootProject.file("spacehost/src/io/github/ffenuss/modkit/space/GuestCallbackChain.java"),
        rootProject.file("spacehost/src/io/github/ffenuss/modkit/space/NativePatch.java"),
        rootProject.file("spacehost/src/io/github/ffenuss/modkit/space/SpaceNativeController.java"),
        rootProject.file("spacehost/src/io/github/ffenuss/modkit/space/SpaceNativeBackend.java"),
        rootProject.file("spacehost/src/io/github/ffenuss/modkit/space/SpaceNativePayload.java"),
        rootProject.file("runtimeprobe/src/main/java/io/github/ffenuss/modkit/runtimeprobe/RuntimeNativeBridge.java"),
        rootProject.file("runtimeprobe/src/main/java/io/github/ffenuss/modkit/runtimeprobe/RuntimeNativeTraceBuffer.java"),
    )
    inputs.file(layout.buildDirectory.file("outputs/apk/debug/spaceengine-debug.apk"))
    inputs.file(rootProject.file("runtimeprobe/build/outputs/apk/debug/runtimeprobe-debug.apk"))
    inputs.file(rootProject.file("nativefixture/build/outputs/apk/debug/nativefixture-debug.apk"))
    outputs.dir(deviceFixtureRoot)
    doLast {
        val root = deviceFixtureRoot.get().asFile
        val javaDir = root.resolve("java/io/github/ffenuss/modkit/space").apply { mkdirs() }
        for (name in listOf("SpaceEngine.java", "SourceInventory.java", "SpacePolicy.java", "MenuProfile.java", "MenuProfileStore.java", "GuestCallbackChain.java", "NativePatch.java", "SpaceNativeController.java", "SpaceNativeBackend.java", "SpaceNativePayload.java")) {
            rootProject.file("spacehost/src/io/github/ffenuss/modkit/space/$name").copyTo(javaDir.resolve(name), overwrite = true)
        }
        val carrier = layout.buildDirectory.file("outputs/apk/debug/spaceengine-debug.apk").get().asFile.readBytes()
        val assets = root.resolve("assets").apply { mkdirs() }
        rootProject.file("runtimeprobe/build/outputs/apk/debug/runtimeprobe-debug.apk").copyTo(assets.resolve("native-runtime.apk"), overwrite = true)
        rootProject.file("nativefixture/build/outputs/apk/debug/nativefixture-debug.apk").copyTo(assets.resolve("native-fixture.apk"), overwrite = true)
        val bridge = root.resolve("java/io/github/ffenuss/modkit/runtimeprobe").apply { mkdirs() }
        for (name in listOf("RuntimeNativeBridge.java", "RuntimeNativeTraceBuffer.java")) {
            rootProject.file("runtimeprobe/src/main/java/io/github/ffenuss/modkit/runtimeprobe/$name").copyTo(bridge.resolve(name), overwrite = true)
        }
        val fixture = root.resolve("java/dev/modkit/nativefixture").apply { mkdirs() }
        fixture.resolve("GameActivity.java").writeText("package dev.modkit.nativefixture; public final class GameActivity { public static native int readNativeValue(); }\n")
        assets.resolve("modkit-space-engine.apk").writeBytes(carrier)
        val sha = MessageDigest.getInstance("SHA-256").digest(carrier).joinToString("") { "%02x".format(it.toInt() and 255) }
        assets.resolve("modkit-space-engine.sha256").writeText(sha, Charsets.US_ASCII)
    }
}
android.sourceSets.getByName("androidTest").apply {
    java.directories.add(deviceFixtureRoot.get().dir("java").asFile.absolutePath)
    assets.directories.add(deviceFixtureRoot.get().dir("assets").asFile.absolutePath)
}
tasks.configureEach {
    if (name == "compileDebugAndroidTestKotlin" || name == "compileDebugAndroidTestJavaWithJavac" || name == "mergeDebugAndroidTestAssets") {
        dependsOn(prepareSpaceDeviceFixtures)
    }
}
