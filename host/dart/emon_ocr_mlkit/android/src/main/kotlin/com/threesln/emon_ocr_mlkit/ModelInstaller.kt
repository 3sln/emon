package com.threesln.emon_ocr_mlkit

import android.content.Context

/** What the plugin needs of where the models live: whether the first recogniser can start, and (when Play services holds the models) downloading them with progress. */
interface ModelInstallerApi {
    fun isUsable(recognizers: Recognizers, script: String): Boolean
    fun install(scripts: List<String>, recognizers: Recognizers)
    fun playServicesPresent(): Boolean
}

/** The variant's installer (`src/bundled` or `src/unbundled`), reporting each download as an event `{kind, pack, done, total, message}` for `Packs.events`. */
fun ModelInstaller(context: Context, report: (Map<String, Any?>) -> Unit): ModelInstallerApi = VariantInstaller(context, report)
