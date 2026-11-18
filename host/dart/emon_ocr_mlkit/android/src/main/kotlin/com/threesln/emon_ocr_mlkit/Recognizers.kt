package com.threesln.emon_ocr_mlkit

import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * One recogniser for each script. Latin is always there; the others are optional dependencies (`emonMlkitScripts` in gradle.properties), so they are found by name at run time: an app that
 * did not include one never loads it, and a request in its script is unread.
 */
class Recognizers {
    private val open = HashMap<String, TextRecognizer>()

    private val optionClasses = mapOf(
        "chinese" to "com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions",
        "japanese" to "com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions",
        "korean" to "com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions",
        "devanagari" to "com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions",
    )

    /** Is the dependency of this script part of the app? */
    fun present(script: String): Boolean {
        if (script == "latin") return true
        val name = optionClasses[script] ?: return false
        return try {
            Class.forName(name)
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    @Synchronized
    fun get(script: String): TextRecognizer? {
        open[script]?.let { return it }
        val r = if (script == "latin") TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) else (create(script) ?: return null)
        open[script] = r
        return r
    }

    // the options of a non-Latin script: `<Script>TextRecognizerOptions.Builder().build()`, then `TextRecognition.getClient(options)`
    private fun create(script: String): TextRecognizer? {
        val name = optionClasses[script] ?: return null
        return try {
            val builder = Class.forName("$name\$Builder").getDeclaredConstructor().newInstance()
            val options = builder.javaClass.getMethod("build").invoke(builder)
            val getClient = TextRecognition::class.java.methods.first {
                it.name == "getClient" && it.parameterTypes.size == 1 && it.parameterTypes[0].isAssignableFrom(options.javaClass)
            }
            getClient.invoke(null, options) as TextRecognizer
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun close() {
        open.values.forEach { it.close() }
        open.clear()
    }
}
