package io.github.ffenuss.modkit.runtime

import android.content.Context
import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.data.InstalledAppRepository
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

enum class LearnedCandidateSource {
    AUTO,
    TRAINING,
    MANUAL,
}

data class BehavioralArtifactIdentity(
    val packageName: String,
    val label: String,
    val versionCode: Long,
    val artifactSha256: String,
)

data class LearnedRuntimeCandidate(
    val id: String,
    val title: String,
    val valueType: RuntimeValueType,
    val confidence: Int,
    val source: LearnedCandidateSource,
    val actionHint: BehavioralActionHint?,
    val lastKnownValue: String?,
    val anchor: StableRuntimePointerAnchor,
    val updatedAtEpochMs: Long,
)

data class LearnedRuntimeProfile(
    val packageName: String,
    val label: String,
    val versionCode: Long,
    val artifactSha256: String,
    val updatedAtEpochMs: Long,
    val candidates: List<LearnedRuntimeCandidate>,
)

class BehavioralProfileStore(
    private val context: Context,
) {
    private val root =
        File(
            context.filesDir,
            "behavioral-profiles",
        ).apply {
            mkdirs()
        }

    fun computeIdentity(
        packageName: String,
        cancellation: CancellationSignal,
    ): BehavioralArtifactIdentity {
        val app =
            InstalledAppRepository(
                context,
            ).find(packageName)
                ?: error(
                    "Installed package is unavailable for learned-profile identity.",
                )
        val apkFiles =
            app.apkFiles
                .sortedBy {
                    it.absolutePath
                }
        require(
            apkFiles.isNotEmpty()
        ) {
            "Installed package has no readable APK files."
        }
        val sourceFingerprint =
            sourceMetadataFingerprint(
                versionCode =
                    app.versionCode,
                files =
                    apkFiles,
            )
        loadIdentityCache(
            packageName =
                app.packageName,
            label =
                app.label,
            versionCode =
                app.versionCode,
            sourceFingerprint =
                sourceFingerprint,
        )?.let {
            return it
        }

        val sources =
            apkFiles.map {
                file ->
                if (
                    cancellation
                        .isCancelled()
                ) {
                    throw AnalysisCancelledException()
                }
                Triple(
                    file.name,
                    file.length(),
                    sha256(
                        file,
                        cancellation,
                    ),
                )
            }

        val artifactSha =
            if (sources.size == 1) {
                sources.single()
                    .third
            } else {
                val digest =
                    MessageDigest
                        .getInstance(
                            "SHA-256",
                        )
                sources
                    .sortedWith(
                        compareBy<
                            Triple<
                                String,
                                Long,
                                String
                            >
                        > {
                            it.first
                        }.thenBy {
                            it.third
                        }.thenBy {
                            it.second
                        },
                    )
                    .forEach {
                        source ->
                        digest.update(
                            source.first
                                .toByteArray(
                                    Charsets.UTF_8,
                                ),
                        )
                        digest.update(
                            0.toByte(),
                        )
                        digest.update(
                            source.third
                                .lowercase()
                                .toByteArray(
                                    Charsets.US_ASCII,
                                ),
                        )
                        digest.update(
                            0.toByte(),
                        )
                        digest.update(
                            source.second
                                .toString()
                                .toByteArray(
                                    Charsets.US_ASCII,
                                ),
                        )
                        digest.update(
                            0.toByte(),
                        )
                    }
                digest.digest()
                    .toHex()
            }

        val identity =
            BehavioralArtifactIdentity(
                packageName =
                    app.packageName,
                label = app.label,
                versionCode =
                    app.versionCode,
                artifactSha256 =
                    artifactSha,
            )
        saveIdentityCache(
            identity =
                identity,
            sourceFingerprint =
                sourceFingerprint,
        )
        return identity
    }

    fun saveCandidate(
        identity: BehavioralArtifactIdentity,
        candidate: LearnedRuntimeCandidate,
    ): LearnedRuntimeProfile {
        val current =
            loadExact(
                packageName =
                    identity.packageName,
                artifactSha256 =
                    identity.artifactSha256,
            )
        val merged =
            (
                current
                    ?.candidates
                    .orEmpty()
                    .filterNot {
                        stableKey(
                            it,
                        ) ==
                            stableKey(
                                candidate,
                            )
                    } +
                    candidate
                )
                .sortedWith(
                    compareByDescending<
                        LearnedRuntimeCandidate
                    > {
                        it.confidence
                    }.thenByDescending {
                        it.updatedAtEpochMs
                    },
                )
                .take(
                    MAX_CANDIDATES_PER_PROFILE,
                )

        val profile =
            LearnedRuntimeProfile(
                packageName =
                    identity.packageName,
                label =
                    identity.label,
                versionCode =
                    identity.versionCode,
                artifactSha256 =
                    identity.artifactSha256,
                updatedAtEpochMs =
                    System.currentTimeMillis(),
                candidates =
                    merged,
            )
        write(profile)
        return profile
    }

    fun removeCandidate(
        identity: BehavioralArtifactIdentity,
        anchor: StableRuntimePointerAnchor,
    ): Boolean {
        val current =
            loadExact(
                packageName =
                    identity.packageName,
                artifactSha256 =
                    identity.artifactSha256,
            ) ?: return false
        val remaining =
            current.candidates
                .filterNot {
                    it.anchor ==
                        anchor
                }
        if (
            remaining.size ==
            current.candidates.size
        ) {
            return false
        }

        val output =
            fileFor(
                packageName =
                    identity.packageName,
                artifactSha256 =
                    identity.artifactSha256,
            )
        if (remaining.isEmpty()) {
            if (!output.exists()) {
                return true
            }
            check(
                output.delete(),
            ) {
                "Could not remove the learned ModKit runtime profile."
            }
            return true
        }

        write(
            current.copy(
                updatedAtEpochMs =
                    System.currentTimeMillis(),
                candidates =
                    remaining,
            ),
        )
        return true
    }

    fun loadExact(
        packageName: String,
        artifactSha256: String,
    ): LearnedRuntimeProfile? {
        val file =
            fileFor(
                packageName =
                    packageName,
                artifactSha256 =
                    artifactSha256,
            )
        if (
            !file.isFile ||
            !file.canRead()
        ) {
            return null
        }
        return runCatching {
            parse(
                file.readText(
                    Charsets.UTF_8,
                ),
            )
        }.getOrNull()
    }

    fun loadLatest(
        packageName: String,
    ): LearnedRuntimeProfile? {
        val folder =
            packageFolder(
                packageName,
            )
        return folder
            .listFiles()
            .orEmpty()
            .asSequence()
            .filter {
                it.isFile &&
                    it.name
                        .endsWith(
                            ".json",
                        )
            }
            .mapNotNull {
                file ->
                runCatching {
                    parse(
                        file.readText(
                            Charsets.UTF_8,
                        ),
                    )
                }.getOrNull()
            }
            .maxByOrNull {
                it.updatedAtEpochMs
            }
    }

    private fun write(
        profile: LearnedRuntimeProfile,
    ) {
        val output =
            fileFor(
                packageName =
                    profile.packageName,
                artifactSha256 =
                    profile
                        .artifactSha256,
            )
        output.parentFile
            ?.mkdirs()
        val temp =
            File(
                output.parentFile,
                output.name +
                    ".tmp",
            )
        temp.writeText(
            encode(profile),
            Charsets.UTF_8,
        )
        if (output.exists()) {
            output.delete()
        }
        check(
            temp.renameTo(
                output,
            ),
        ) {
            temp.delete()
            "Could not persist learned ModKit runtime profile."
        }
    }

    private fun encode(
        profile: LearnedRuntimeProfile,
    ): String {
        val candidates =
            JSONArray()
        profile.candidates
            .forEach {
                candidate ->
                val offsets =
                    JSONArray()
                candidate.anchor
                    .offsetsFromAnchor
                    .forEach {
                        offsets.put(it)
                    }
                candidates.put(
                    JSONObject()
                        .put(
                            "id",
                            candidate.id,
                        )
                        .put(
                            "title",
                            candidate.title,
                        )
                        .put(
                            "valueType",
                            candidate
                                .valueType
                                .name,
                        )
                        .put(
                            "confidence",
                            candidate
                                .confidence,
                        )
                        .put(
                            "source",
                            candidate
                                .source
                                .name,
                        )
                        .put(
                            "actionHint",
                            candidate
                                .actionHint
                                ?.name,
                        )
                        .put(
                            "lastKnownValue",
                            candidate
                                .lastKnownValue,
                        )
                        .put(
                            "updatedAtEpochMs",
                            candidate
                                .updatedAtEpochMs,
                        )
                        .put(
                            "anchor",
                            JSONObject()
                                .put(
                                    "moduleIdentity",
                                    candidate
                                        .anchor
                                        .moduleIdentity,
                                )
                                .put(
                                    "moduleFileOffset",
                                    candidate
                                        .anchor
                                        .moduleFileOffset,
                                )
                                .put(
                                    "pointerWidth",
                                    candidate
                                        .anchor
                                        .pointerWidth,
                                )
                                .put(
                                    "offsets",
                                    offsets,
                                ),
                        ),
                )
            }

        return JSONObject()
            .put(
                "schema",
                SCHEMA,
            )
            .put(
                "packageName",
                profile.packageName,
            )
            .put(
                "label",
                profile.label,
            )
            .put(
                "versionCode",
                profile.versionCode,
            )
            .put(
                "artifactSha256",
                profile.artifactSha256,
            )
            .put(
                "updatedAtEpochMs",
                profile.updatedAtEpochMs,
            )
            .put(
                "candidates",
                candidates,
            )
            .toString()
    }

    private fun parse(
        raw: String,
    ): LearnedRuntimeProfile {
        val json =
            JSONObject(raw)
        require(
            json.getString(
                "schema",
            ) ==
                SCHEMA,
        ) {
            "Unsupported learned-profile schema."
        }

        val packageName =
            json.getString(
                "packageName",
            )
        require(
            packageName.matches(
                PACKAGE_REGEX,
            ),
        ) {
            "Invalid learned-profile package."
        }
        val sha =
            json.getString(
                "artifactSha256",
            ).lowercase()
        require(
            sha.matches(
                SHA_REGEX,
            ),
        ) {
            "Invalid learned-profile artifact SHA."
        }

        val array =
            json.getJSONArray(
                "candidates",
            )
        require(
            array.length() <=
                MAX_CANDIDATES_PER_PROFILE
        ) {
            "Learned profile contains too many candidates."
        }
        val candidates =
            buildList {
                repeat(
                    array.length(),
                ) {
                    index ->
                    val item =
                        array
                            .getJSONObject(
                                index,
                            )
                    val anchorJson =
                        item
                            .getJSONObject(
                                "anchor",
                            )
                    val offsetsJson =
                        anchorJson
                            .getJSONArray(
                                "offsets",
                            )
                    require(
                        offsetsJson
                            .length() in
                            1..6,
                    ) {
                        "Invalid learned pointer-chain depth."
                    }
                    val offsets =
                        buildList {
                            repeat(
                                offsetsJson
                                    .length(),
                            ) {
                                offsetIndex ->
                                add(
                                    offsetsJson
                                        .getLong(
                                            offsetIndex,
                                        ),
                                )
                            }
                        }
                    add(
                        LearnedRuntimeCandidate(
                            id =
                                item.getString(
                                    "id",
                                ),
                            title =
                                item.getString(
                                    "title",
                                ),
                            valueType =
                                RuntimeValueType
                                    .valueOf(
                                        item.getString(
                                            "valueType",
                                        ),
                                    ),
                            confidence =
                                item.getInt(
                                    "confidence",
                                ).coerceIn(
                                    1,
                                    99,
                                ),
                            source =
                                LearnedCandidateSource
                                    .valueOf(
                                        item.getString(
                                            "source",
                                        ),
                                    ),
                            actionHint =
                                item
                                    .optString(
                                        "actionHint",
                                    )
                                    .takeIf {
                                        it.isNotBlank()
                                    }
                                    ?.let {
                                        BehavioralActionHint
                                            .valueOf(
                                                it,
                                            )
                                    },
                            lastKnownValue =
                                if (
                                    item.isNull(
                                        "lastKnownValue",
                                    )
                                ) {
                                    null
                                } else {
                                    item
                                        .optString(
                                            "lastKnownValue",
                                        )
                                        .takeIf {
                                            it.isNotBlank()
                                        }
                                },
                            anchor =
                                StableRuntimePointerAnchor(
                                    moduleIdentity =
                                        anchorJson
                                            .getString(
                                                "moduleIdentity",
                                            ),
                                    moduleFileOffset =
                                        anchorJson
                                            .getLong(
                                                "moduleFileOffset",
                                            ),
                                    pointerWidth =
                                        anchorJson
                                            .getInt(
                                                "pointerWidth",
                                            ),
                                    offsetsFromAnchor =
                                        offsets,
                                ),
                            updatedAtEpochMs =
                                item.optLong(
                                    "updatedAtEpochMs",
                                    0L,
                                ),
                        ),
                    )
                }
            }

        return LearnedRuntimeProfile(
            packageName =
                packageName,
            label =
                json.optString(
                    "label",
                    packageName,
                ),
            versionCode =
                json.getLong(
                    "versionCode",
                ),
            artifactSha256 =
                sha,
            updatedAtEpochMs =
                json.optLong(
                    "updatedAtEpochMs",
                    0L,
                ),
            candidates =
                candidates,
        )
    }

    private fun fileFor(
        packageName: String,
        artifactSha256: String,
    ): File =
        File(
            packageFolder(
                packageName,
            ),
            artifactSha256
                .lowercase() +
                ".json",
        )

    private fun packageFolder(
        packageName: String,
    ): File =
        File(
            root,
            packageName.replace(
                Regex(
                    "[^A-Za-z0-9._-]",
                ),
                "_",
            ),
        ).apply {
            mkdirs()
        }

    private fun stableKey(
        candidate:
            LearnedRuntimeCandidate,
    ): String =
        candidate.anchor
            .moduleIdentity +
            ":" +
            candidate.anchor
                .moduleFileOffset
                .toString(16) +
            ":" +
            candidate.anchor
                .offsetsFromAnchor
                .joinToString(
                    ",",
                )

    private fun sourceMetadataFingerprint(
        versionCode: Long,
        files: List<File>,
    ): String {
        val digest =
            MessageDigest
                .getInstance(
                    "SHA-256",
                )
        digest.update(
            versionCode
                .toString()
                .toByteArray(
                    Charsets.US_ASCII,
                ),
        )
        digest.update(
            0.toByte(),
        )
        files.forEach {
            file ->
            require(
                file.isFile &&
                    file.canRead(),
            ) {
                "APK source is not readable: " +
                    file.absolutePath
            }
            listOf(
                file.absolutePath,
                file.length()
                    .toString(),
                file.lastModified()
                    .toString(),
            ).forEach {
                value ->
                digest.update(
                    value.toByteArray(
                        Charsets.UTF_8,
                    ),
                )
                digest.update(
                    0.toByte(),
                )
            }
        }
        return digest.digest()
            .toHex()
    }

    private fun loadIdentityCache(
        packageName: String,
        label: String,
        versionCode: Long,
        sourceFingerprint: String,
    ): BehavioralArtifactIdentity? {
        val cache =
            identityCacheFile(
                packageName,
            )
        if (
            !cache.isFile ||
            !cache.canRead()
        ) {
            return null
        }
        return runCatching {
            val json =
                JSONObject(
                    cache.readText(
                        Charsets.UTF_8,
                    ),
                )
            require(
                json.optString(
                    "schema",
                ) ==
                    IDENTITY_CACHE_SCHEMA
            )
            require(
                json.getString(
                    "packageName",
                ) ==
                    packageName
            )
            require(
                json.getLong(
                    "versionCode",
                ) ==
                    versionCode
            )
            require(
                json.getString(
                    "sourceFingerprint",
                ) ==
                    sourceFingerprint
            )
            val sha =
                json.getString(
                    "artifactSha256",
                ).lowercase()
            require(
                sha.matches(
                    SHA_REGEX,
                ),
            )
            BehavioralArtifactIdentity(
                packageName =
                    packageName,
                label = label,
                versionCode =
                    versionCode,
                artifactSha256 =
                    sha,
            )
        }.getOrNull()
    }

    private fun saveIdentityCache(
        identity: BehavioralArtifactIdentity,
        sourceFingerprint: String,
    ) {
        val cache =
            identityCacheFile(
                identity.packageName,
            )
        val temp =
            File(
                cache.parentFile,
                cache.name +
                    ".tmp",
            )
        val json =
            JSONObject()
                .put(
                    "schema",
                    IDENTITY_CACHE_SCHEMA,
                )
                .put(
                    "packageName",
                    identity.packageName,
                )
                .put(
                    "versionCode",
                    identity.versionCode,
                )
                .put(
                    "sourceFingerprint",
                    sourceFingerprint,
                )
                .put(
                    "artifactSha256",
                    identity
                        .artifactSha256,
                )
        temp.writeText(
            json.toString(),
            Charsets.UTF_8,
        )
        if (cache.exists()) {
            cache.delete()
        }
        if (!temp.renameTo(cache)) {
            temp.delete()
        }
    }

    private fun identityCacheFile(
        packageName: String,
    ): File =
        File(
            packageFolder(
                packageName,
            ),
            "identity.cache",
        )

    private fun sha256(
        file: File,
        cancellation:
            CancellationSignal,
    ): String {
        require(
            file.isFile &&
                file.canRead(),
        ) {
            "APK source is not readable: " +
                file.absolutePath
        }
        val digest =
            MessageDigest
                .getInstance(
                    "SHA-256",
                )
        FileInputStream(
            file,
        ).buffered(
            128 * 1024,
        ).use {
            input ->
            val buffer =
                ByteArray(
                    128 * 1024,
                )
            while (true) {
                if (
                    cancellation
                        .isCancelled()
                ) {
                    throw AnalysisCancelledException()
                }
                val read =
                    input.read(
                        buffer,
                    )
                if (read < 0) {
                    break
                }
                digest.update(
                    buffer,
                    0,
                    read,
                )
            }
        }
        return digest.digest()
            .toHex()
    }

    private fun ByteArray.toHex():
        String =
        joinToString("") {
            "%02x".format(
                it.toInt() and
                    0xff,
            )
        }

    companion object {
        private const val SCHEMA =
            "modkit/behavioral-profile/1"
        private const val IDENTITY_CACHE_SCHEMA =
            "modkit/behavioral-identity-cache/1"
        private const val MAX_CANDIDATES_PER_PROFILE =
            32
        private val PACKAGE_REGEX =
            Regex(
                "[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+",
            )
        private val SHA_REGEX =
            Regex(
                "[0-9a-f]{64}",
            )
    }
}
