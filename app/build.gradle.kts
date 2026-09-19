import java.io.File
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val runtimeProbeAssetDir =
    layout.buildDirectory.dir("generated/runtimeProbeAssets")
val runtimeProbeDexAsset =
    runtimeProbeAssetDir.map { it.file("modkit-runtime-probe.dex") }

android {
    namespace = "io.github.ffenuss.modkit"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "io.github.ffenuss.modkit"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets.getByName("main").assets.directories.add(runtimeProbeAssetDir.get().asFile.absolutePath)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.android.tools.build:apksig:9.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}


val generateRuntimeProbeDexAsset =
    tasks.register("generateRuntimeProbeDexAsset") {
        dependsOn(":runtimeprobe:assembleDebug")
        outputs.file(runtimeProbeDexAsset)

        doLast {
            val payloadApk = project(":runtimeprobe")
                .layout
                .buildDirectory
                .file("outputs/apk/debug/runtimeprobe-debug.apk")
                .get()
                .asFile
            check(payloadApk.isFile && payloadApk.length() > 0L) {
                "Runtime probe payload APK was not produced."
            }

            val output = runtimeProbeDexAsset.get().asFile
            output.parentFile.mkdirs()
            val temp = File(
                output.parentFile,
                output.name + ".tmp",
            )
            temp.delete()
            output.delete()

            val providerMarker =
                "RuntimeEvidenceProvider".toByteArray(Charsets.UTF_8)
            val matchingDex = mutableListOf<ByteArray>()
            ZipFile(payloadApk).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (
                        !entry.isDirectory &&
                        Regex("""classes(?:\d*)?\.dex""")
                            .matches(entry.name)
                    ) {
                        val bytes = zip.getInputStream(entry).use {
                            it.readBytes()
                        }
                        val markerFound =
                            bytes.indices.any { start ->
                                start + providerMarker.size <= bytes.size &&
                                    providerMarker.indices.all { offset ->
                                        bytes[start + offset] ==
                                            providerMarker[offset]
                                    }
                            }
                        if (markerFound) matchingDex += bytes
                    }
                }
            }
            check(matchingDex.size == 1) {
                "Runtime probe provider must resolve to exactly one DEX; found " +
                    matchingDex.size
            }
            temp.outputStream().buffered().use { target ->
                target.write(matchingDex.single())
            }

            val magic = ByteArray(4)
            val magicBytesRead = temp.inputStream().use { input ->
                input.read(magic)
            }
            check(
                magicBytesRead == 4 &&
                    magic.contentEquals(
                        byteArrayOf(0x64, 0x65, 0x78, 0x0a),
                    ),
            ) {
                "Generated runtime probe payload is not a DEX file."
            }
            check(temp.renameTo(output)) {
                temp.delete()
                "Could not finalize runtime probe DEX asset."
            }
        }
    }

tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) {
        dependsOn(generateRuntimeProbeDexAsset)
    }
}
