package io.github.ffenuss.modkit.runtime

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

data class RepackedRuntimeLaunchResult(
    val packageName: String,
    val activityClassName: String,
)

object RepackedRuntimeTestAppLauncher {
    fun launch(
        context: Context,
        build: RepackedRuntimeBuildResult,
    ): RepackedRuntimeLaunchResult {
        val authority =
            build.packageName +
                BinaryAndroidManifestProbeInjector.AUTHORITY_SUFFIX
        val transport =
            AndroidRepackedRuntimeProbeTransport(context)
        val installed = requireNotNull(
            transport.inspectInstalled(
                packageName = build.packageName,
                authority = authority,
            ),
        ) {
            "Installed repacked test probe provider was not found."
        }
        RepackedRuntimeProbeIdentityVerifier.verify(
            build = build,
            installed = installed,
        )

        val packageManager = context.packageManager
        val candidate =
            packageManager.getLaunchIntentForPackage(
                build.packageName,
            ) ?: packageManager.getLeanbackLaunchIntentForPackage(
                build.packageName,
            )
            ?: error(
                "Installed test build has no launchable main activity.",
            )
        val resolved = packageManager.resolveActivity(
            candidate,
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo
            ?: error(
                "Android could not resolve the test build launch activity.",
            )
        require(resolved.packageName == build.packageName) {
            "Resolved launch activity does not belong to the test build package."
        }

        val explicit = Intent(candidate).apply {
            setClassName(
                resolved.packageName,
                resolved.name,
            )
            setPackage(resolved.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(explicit)
        return RepackedRuntimeLaunchResult(
            packageName = build.packageName,
            activityClassName = resolved.name,
        )
    }
}
