package com.threesln.emon_ocr_mlkit

import android.content.Context

object Variant {
    const val NAME = "bundled"
}

/** The models are in the app: nothing to download, and no need for Google Play services. */
class VariantInstaller(private val context: Context, private val report: (Map<String, Any?>) -> Unit) : ModelInstallerApi {
    override fun isUsable(recognizers: Recognizers, script: String): Boolean = recognizers.get(script) != null

    override fun install(scripts: List<String>, recognizers: Recognizers) {
        for (s in scripts) report(mapOf("kind" to "done", "pack" to "mlkit-text-$s", "done" to 0, "total" to 0))
    }

    override fun playServicesPresent(): Boolean = try {
        context.packageManager.getPackageInfo("com.google.android.gms", 0)
        true
    } catch (e: Exception) {
        false
    }
}
