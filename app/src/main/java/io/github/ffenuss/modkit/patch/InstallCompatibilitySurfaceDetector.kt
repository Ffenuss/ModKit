package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.iface.reference.TypeReference

enum class InstallCompatibilitySurfaceKind {
    SIGNING_API_REFERENCE,
    PLAY_ATTESTATION_REFERENCE,
    REFLECTION_OR_DYNAMIC_CODE,
    NATIVE_METHOD,
}

data class InstallCompatibilitySurfaceSample(
    val kind: InstallCompatibilitySurfaceKind,
    val owner: String,
    val member: String,
    val detail: String,
)

data class InstallCompatibilitySurfaceScan(
    val counts: Map<InstallCompatibilitySurfaceKind, Int>,
    val samples: List<InstallCompatibilitySurfaceSample>,
) {
    fun count(kind: InstallCompatibilitySurfaceKind): Int = counts[kind] ?: 0

    val remoteAttestationPossible: Boolean
        get() = count(InstallCompatibilitySurfaceKind.PLAY_ATTESTATION_REFERENCE) > 0

    val dexOnlyInspectionIncomplete: Boolean
        get() = count(InstallCompatibilitySurfaceKind.NATIVE_METHOD) > 0 ||
            count(InstallCompatibilitySurfaceKind.REFLECTION_OR_DYNAMIC_CODE) > 0
}

/**
 * Diagnostic-only companion to the exact installer-branch detector.
 *
 * These are *surfaces*, not proof that enforcement exists. They are reported so
 * ModKit does not claim that a repacked APK is compatible merely because no
 * direct PackageManager installer branch was found.
 */
object InstallCompatibilitySurfaceDetector {
    private const val RUNTIME_PROBE = "Lio/github/ffenuss/modkit/runtimeprobe/"
    private const val PACKAGE_MANAGER = "Landroid/content/pm/PackageManager;"
    private const val PACKAGE_INFO = "Landroid/content/pm/PackageInfo;"
    private const val SIGNING_INFO = "Landroid/content/pm/SigningInfo;"
    private const val SIGNATURE = "Landroid/content/pm/Signature;"
    private const val MAX_SAMPLES = 48

    fun scan(bytes: ByteArray, cancellation: CancellationSignal): InstallCompatibilitySurfaceScan {
        val counts = InstallCompatibilitySurfaceKind.entries.associateWith { 0 }.toMutableMap()
        val samples = ArrayList<InstallCompatibilitySurfaceSample>()

        fun check() {
            if (cancellation.isCancelled()) throw AnalysisCancelledException()
        }
        fun record(
            kind: InstallCompatibilitySurfaceKind,
            owner: String,
            member: String,
            detail: String,
        ) {
            counts[kind] = counts.getValue(kind) + 1
            if (samples.size < MAX_SAMPLES) {
                samples += InstallCompatibilitySurfaceSample(kind, owner, member, detail)
            }
        }

        val dex = DexBackedDexFile(null, bytes)
        for (clazz in dex.classes) {
            check()
            if (clazz.type.startsWith(RUNTIME_PROBE)) continue
            for (method in clazz.methods) {
                check()
                val identity = clazz.type + "->" + method.name
                if (AccessFlags.NATIVE.isSet(method.accessFlags)) {
                    record(
                        InstallCompatibilitySurfaceKind.NATIVE_METHOD,
                        clazz.type,
                        method.name,
                        "Нативный метод: DEX-анализ не может исключить проверку в JNI/ELF.",
                    )
                }
                val implementation = method.implementation ?: continue
                for (instruction in implementation.instructions) {
                    check()
                    val reference = (instruction as? ReferenceInstruction)?.reference ?: continue
                    val descriptor = reference.toString()

                    val signing = when (reference) {
                        is MethodReference -> isSigningMethod(reference)
                        is FieldReference ->
                            reference.definingClass == PACKAGE_INFO &&
                                reference.name in setOf("signatures", "signingInfo")
                        is TypeReference ->
                            reference.type == SIGNING_INFO || reference.type == SIGNATURE
                        else -> false
                    }
                    if (signing) {
                        record(
                            InstallCompatibilitySurfaceKind.SIGNING_API_REFERENCE,
                            clazz.type,
                            method.name,
                            "Ссылка на Android API подписи: $descriptor",
                        )
                    }

                    if (isPlayAttestationReference(reference, descriptor)) {
                        record(
                            InstallCompatibilitySurfaceKind.PLAY_ATTESTATION_REFERENCE,
                            clazz.type,
                            method.name,
                            "Найдена ссылка на Play Integrity/SafetyNet: $descriptor",
                        )
                    }

                    if (reference is MethodReference && isReflectionOrDynamic(reference)) {
                        record(
                            InstallCompatibilitySurfaceKind.REFLECTION_OR_DYNAMIC_CODE,
                            clazz.type,
                            method.name,
                            "Динамический вызов может скрывать проверку от статического DEX-поиска: $descriptor",
                        )
                    }
                }
            }
        }
        return InstallCompatibilitySurfaceScan(counts.toMap(), samples)
    }

    private fun isSigningMethod(ref: MethodReference): Boolean {
        val name = ref.name
        return when (ref.definingClass) {
            PACKAGE_MANAGER -> name in setOf(
                "hasSigningCertificate",
                "checkSignatures",
                "getPackageInfo",
                "getPackageArchiveInfo",
            )
            SIGNING_INFO -> name in setOf(
                "getApkContentsSigners",
                "getSigningCertificateHistory",
                "hasMultipleSigners",
            )
            SIGNATURE -> name in setOf("toByteArray", "toCharsString")
            else -> false
        }
    }

    private fun isPlayAttestationReference(reference: Any, descriptor: String): Boolean {
        val text = when (reference) {
            is StringReference -> reference.string
            else -> descriptor
        }.replace('.', '/')
        return "com/google/android/play/core/integrity" in text ||
            "com/google/android/play/integrity" in text ||
            "com/google/android/gms/safetynet" in text
    }

    private fun isReflectionOrDynamic(ref: MethodReference): Boolean =
        (ref.definingClass == "Ljava/lang/Class;" && ref.name == "forName") ||
            (ref.definingClass == "Ljava/lang/reflect/Method;" && ref.name == "invoke") ||
            (ref.definingClass == "Ljava/lang/reflect/Constructor;" && ref.name == "newInstance") ||
            ref.definingClass in setOf(
                "Ldalvik/system/DexClassLoader;",
                "Ldalvik/system/InMemoryDexClassLoader;",
                "Ldalvik/system/PathClassLoader;",
            )
}
