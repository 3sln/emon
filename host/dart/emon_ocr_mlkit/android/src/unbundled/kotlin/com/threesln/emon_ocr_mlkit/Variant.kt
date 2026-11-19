package com.threesln.emon_ocr_mlkit

import android.content.Context
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState
import com.google.android.gms.common.api.OptionalModuleApi
import com.google.android.gms.tasks.Tasks

object Variant {
    const val NAME = "unbundled"
}

/**
 * The models are kept by Google Play services: about 260 KB each in the app, downloaded when first needed (and updated silently). Play services reports the download, which is what
 * `Packs.events` carries (`mlkit-text-<script>`); without Play services the engine is unavailable and a turnkey falls back to Tesseract.
 */
class VariantInstaller(private val context: Context, private val report: (Map<String, Any?>) -> Unit) : ModelInstallerApi {
    override fun playServicesPresent(): Boolean = try {
        context.packageManager.getPackageInfo("com.google.android.gms", 0)
        true
    } catch (e: Exception) {
        false
    }

    override fun isUsable(recognizers: Recognizers, script: String): Boolean {
        if (!playServicesPresent()) return false
        val r = recognizers.get(script) ?: return false
        val client = ModuleInstall.getClient(context)
        return Tasks.await(client.areModulesAvailable(r as OptionalModuleApi)).areModulesAvailable()
    }

    override fun install(scripts: List<String>, recognizers: Recognizers) {
        if (!playServicesPresent()) throw IllegalStateException("Google Play services is not on this device")
        val client = ModuleInstall.getClient(context)
        for (script in scripts) {
            val r = recognizers.get(script) ?: continue
            val pack = "mlkit-text-$script"
            if (Tasks.await(client.areModulesAvailable(r as OptionalModuleApi)).areModulesAvailable()) {
                report(mapOf("kind" to "done", "pack" to pack, "done" to 0, "total" to 0))
                continue
            }
            report(mapOf("kind" to "start", "pack" to pack, "done" to 0, "total" to 0))
            val listener = InstallStatusListener { update: ModuleInstallStatusUpdate ->
                val info = update.progressInfo
                val done = info?.bytesDownloaded ?: 0L
                val total = info?.totalBytesToDownload ?: 0L
                when (update.installState) {
                    InstallState.STATE_COMPLETED -> report(mapOf("kind" to "done", "pack" to pack, "done" to done, "total" to total))
                    InstallState.STATE_FAILED -> report(mapOf("kind" to "failed", "pack" to pack, "done" to done, "total" to total, "message" to "Play services could not install the model"))
                    InstallState.STATE_CANCELED -> report(mapOf("kind" to "cancelled", "pack" to pack, "done" to done, "total" to total))
                    else -> report(mapOf("kind" to "progress", "pack" to pack, "done" to done, "total" to total))
                }
            }
            val request = ModuleInstallRequest.newBuilder().addApi(r as OptionalModuleApi).setListener(listener).build()
            Tasks.await(client.installModules(request))
        }
    }
}
