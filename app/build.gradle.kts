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
val runtimeProbeNativeAssetRoot =
    runtimeProbeAssetDir.map {
        it.dir("modkit-runtime-probe-native")
    }
val rootMemoryWatchAssetRoot =
    runtimeProbeAssetDir.map {
        it.dir("modkit-root-memory-watch")
    }
val rootFastScanAssetRoot =
    runtimeProbeAssetDir.map {
        it.dir("modkit-root-fast-scan")
    }

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
        versionCode = 15
        versionName = "0.0.15"
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
        outputs.dir(runtimeProbeNativeAssetRoot)
        outputs.dir(rootMemoryWatchAssetRoot)
        outputs.dir(rootFastScanAssetRoot)

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


            val nativeRoot =
                runtimeProbeNativeAssetRoot.get().asFile
            nativeRoot.deleteRecursively()
            nativeRoot.mkdirs()
            val expectedAbis = listOf(
                "arm64-v8a",
                "armeabi-v7a",
                "x86",
                "x86_64",
            )
            val extractedAbis = mutableSetOf<String>()
            ZipFile(payloadApk).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val match = Regex(
                        """lib/(arm64-v8a|armeabi-v7a|x86|x86_64)/libmodkit_runtime_probe\.so""",
                    ).matchEntire(entry.name) ?: continue
                    val abi = match.groupValues[1]
                    check(extractedAbis.add(abi)) {
                        "Duplicate runtime probe native payload for $abi"
                    }
                    val nativeOutput = File(
                        nativeRoot,
                        "$abi/libmodkit_runtime_probe.so",
                    )
                    nativeOutput.parentFile.mkdirs()
                    zip.getInputStream(entry).use { source ->
                        nativeOutput.outputStream().buffered().use { target ->
                            source.copyTo(target)
                        }
                    }
                    val magic = ByteArray(4)
                    val read = nativeOutput.inputStream().use {
                        it.read(magic)
                    }
                    check(
                        read == 4 &&
                            magic.contentEquals(
                                byteArrayOf(
                                    0x7f,
                                    0x45,
                                    0x4c,
                                    0x46,
                                ),
                            ),
                    ) {
                        "Runtime probe native payload for $abi is not ELF."
                    }
                }
            }
            check(extractedAbis == expectedAbis.toSet()) {
                "Runtime probe native payload ABI set mismatch: " +
                    extractedAbis.sorted().joinToString()
            }

            val rootWatchRoot =
                rootMemoryWatchAssetRoot.get().asFile
            rootWatchRoot.deleteRecursively()
            rootWatchRoot.mkdirs()
            val runtimeProbeProject =
                project(":runtimeprobe")
            val watchSearchRoots =
                listOf(
                    runtimeProbeProject
                        .layout
                        .buildDirectory
                        .get()
                        .asFile,
                    File(
                        runtimeProbeProject.projectDir,
                        ".cxx",
                    ),
                )
            val watchCandidates =
                watchSearchRoots
                    .asSequence()
                    .filter {
                        it.exists()
                    }
                    .flatMap {
                        it.walkTopDown()
                            .asSequence()
                    }
                    .filter {
                        it.isFile &&
                            it.name ==
                            "modkit_root_memory_watch" &&
                            it.invariantSeparatorsPath
                                .contains(
                                    "/arm64-v8a/",
                                )
                    }
                    .toList()
            check(watchCandidates.isNotEmpty()) {
                "ARM64 root memory watch helper was not produced by ndk-build."
            }
            val newestWatch =
                watchCandidates.maxBy {
                    it.lastModified()
                }
            val watchOutput =
                File(
                    rootWatchRoot,
                    "arm64-v8a/modkit_root_memory_watch",
                )
            watchOutput.parentFile.mkdirs()
            newestWatch.copyTo(
                watchOutput,
                overwrite = true,
            )
            val watchMagic =
                ByteArray(4)
            val watchRead =
                watchOutput.inputStream()
                    .use {
                        it.read(
                            watchMagic,
                        )
                    }
            check(
                watchRead == 4 &&
                    watchMagic.contentEquals(
                        byteArrayOf(
                            0x7f,
                            0x45,
                            0x4c,
                            0x46,
                        ),
                    ),
            ) {
                "Root memory watch helper is not ELF."
            }
            check(
                watchOutput
                    .readBytes()
                    .toString(
                        Charsets.ISO_8859_1,
                    )
                    .contains(
                        "MODKIT_ROOT_WATCH_V1",
                    ),
            ) {
                "Root memory watch helper marker is missing."
            }

            val fastScanRoot =
                rootFastScanAssetRoot.get().asFile
            fastScanRoot.deleteRecursively()
            fastScanRoot.mkdirs()
            val fastScanCandidates =
                watchSearchRoots
                    .asSequence()
                    .filter {
                        it.exists()
                    }
                    .flatMap {
                        it.walkTopDown()
                            .asSequence()
                    }
                    .filter {
                        it.isFile &&
                            it.name ==
                            "modkit_root_fast_value_scan"
                    }
                    .toList()
            val fastScanByAbi =
                expectedAbis.associateWith {
                    abi ->
                    fastScanCandidates
                        .filter {
                            it.invariantSeparatorsPath
                                .contains(
                                    "/$abi/",
                                )
                        }
                        .maxByOrNull {
                            it.lastModified()
                        }
                        ?: error(
                            "Root fast scanner was not produced for $abi.",
                        )
                }
            fastScanByAbi.forEach {
                    (abi, source),
                ->
                val target =
                    File(
                        fastScanRoot,
                        "$abi/modkit_root_fast_value_scan",
                    )
                target.parentFile.mkdirs()
                source.copyTo(
                    target,
                    overwrite = true,
                )
                val bytes =
                    target.readBytes()
                check(
                    bytes.size >= 4 &&
                        bytes[0] ==
                        0x7f.toByte() &&
                        bytes[1] ==
                        0x45.toByte() &&
                        bytes[2] ==
                        0x4c.toByte() &&
                        bytes[3] ==
                        0x46.toByte()
                ) {
                    "Root fast scanner for $abi is not ELF."
                }
                check(
                    bytes.toString(
                        Charsets.ISO_8859_1,
                    ).contains(
                        "MODKIT_ROOT_FAST_SCAN_V1",
                    ),
                ) {
                    "Root fast scanner marker is missing for $abi."
                }
            }
        }
    }

tasks.configureEach {
    if (
        (name.startsWith("merge") && name.endsWith("Assets")) ||
        name.contains("Lint", ignoreCase = true)
    ) {
        dependsOn(generateRuntimeProbeDexAsset)
    }
}
