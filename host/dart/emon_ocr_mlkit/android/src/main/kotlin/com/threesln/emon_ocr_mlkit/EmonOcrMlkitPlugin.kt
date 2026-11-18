package com.threesln.emon_ocr_mlkit

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

/**
 * ML Kit text recognition as emon's OCR engine on Android (DEVLOG D895, D898, D896). Dart (`MlKitAdapter`) sends one request: the page's PNG, the region to read, the script; this answers
 * blocks, lines (with quads) and words with boxes, confidences and languages, in pixels of the PNG. Nothing here decides what emon needs: kin does that, and the Dart adapter says what this
 * engine is and is not.
 */
class EmonOcrMlkitPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, EventChannel.StreamHandler {
    private lateinit var context: Context
    private lateinit var channel: MethodChannel
    private lateinit var events: EventChannel
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var sink: EventChannel.EventSink? = null
    private val recognizers = Recognizers()
    private lateinit var installer: ModelInstaller

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "com.3sln.emon/mlkit")
        channel.setMethodCallHandler(this)
        events = EventChannel(binding.binaryMessenger, "com.3sln.emon/mlkit/events")
        events.setStreamHandler(this)
        installer = ModelInstaller(context) { event -> main.post { sink?.success(event) } }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        events.setStreamHandler(null)
        recognizers.close()
        worker.shutdown()
    }

    override fun onListen(arguments: Any?, eventSink: EventChannel.EventSink?) {
        sink = eventSink
    }

    override fun onCancel(arguments: Any?) {
        sink = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "info" -> worker.execute {
                val info = info()
                main.post { result.success(info) }
            }
            "recognize" -> {
                val png = call.argument<ByteArray>("png")
                val region = call.argument<List<Int>>("region")
                val script = call.argument<String>("script") ?: "latin"
                if (png == null) {
                    result.error("usage", "no image", null)
                    return
                }
                worker.execute {
                    try {
                        val out = Reader.read(recognizers, png, region, script)
                        main.post { result.success(out) }
                    } catch (e: Exception) {
                        main.post { result.error("mlkit", e.message ?: e.javaClass.simpleName, null) }
                    }
                }
            }
            "install" -> {
                val scripts = call.argument<List<String>>("scripts") ?: listOf("latin")
                worker.execute {
                    try {
                        installer.install(scripts, recognizers)
                        main.post { result.success(null) }
                    } catch (e: Exception) {
                        main.post { result.error("mlkit-install", e.message ?: e.javaClass.simpleName, null) }
                    }
                }
            }
            else -> result.notImplemented()
        }
    }

    // what this build is: the variant, the library, which recognisers it has and whether the first of them starts
    private fun info(): Map<String, Any?> {
        val scripts = BuildConfig.EMON_MLKIT_SCRIPTS.split(",").filter { it.isNotEmpty() }
        val have = scripts.filter { recognizers.present(it) }
        var available = false
        var reason: String? = null
        try {
            available = have.isNotEmpty() && installer.isUsable(recognizers, have.first())
        } catch (e: Throwable) {
            reason = e.message
        }
        return mapOf(
            "available" to available,
            "reason" to reason,
            "variant" to Variant.NAME,
            "library" to if (Variant.NAME == "bundled") "com.google.mlkit:text-recognition" else "com.google.android.gms:play-services-mlkit-text-recognition",
            "version" to BuildConfig.EMON_MLKIT_VERSION,
            "scripts" to have,
            "playServices" to installer.playServicesPresent(),
            "sdk" to Build.VERSION.SDK_INT,
        )
    }
}
