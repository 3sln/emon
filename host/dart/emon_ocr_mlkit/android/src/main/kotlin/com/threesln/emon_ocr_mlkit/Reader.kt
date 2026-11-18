package com.threesln.emon_ocr_mlkit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One reading: decode the page, cut the region, make it big enough to read, run the recogniser of the script, and map what it found back to pixels of the page.
 *
 * A SMALL REGION (a re-read of one cell or word) is padded with its own border colour and enlarged by a whole factor first: recognisers answer nothing for a 46 x 45 pixel crop (dart1, section 3).
 */
object Reader {
    private const val MIN_SIDE = 160
    private const val TARGET_SIDE = 192
    private const val MAX_SCALE = 8

    // request-bitmap pixel = prepared pixel / scale - margin + (ox, oy)
    private class Prepared(val bitmap: Bitmap, val scale: Double, val margin: Double, val ox: Double, val oy: Double)

    fun read(recognizers: Recognizers, png: ByteArray, region: List<Int>?, script: String): Map<String, Any?> {
        val recognizer = recognizers.get(script) ?: return mapOf("unsupported" to "lang-unavailable")
        val page = BitmapFactory.decodeByteArray(png, 0, png.size) ?: throw IllegalArgumentException("the page is not a decodable image")
        val (w, h) = Pair(page.width, page.height)
        val rect = if (region != null && region.size == 4 && region[2] > 0 && region[3] > 0) region else listOf(0, 0, w, h)
        val p = prepare(page, rect)
        try {
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(p.bitmap, 0)))
            return map(text, p, w, h)
        } finally {
            if (p.bitmap !== page) p.bitmap.recycle()
            page.recycle()
        }
    }

    private fun prepare(page: Bitmap, r: List<Int>): Prepared {
        val rx = r[0].coerceIn(0, page.width - 1)
        val ry = r[1].coerceIn(0, page.height - 1)
        val rw = min(r[2], page.width - rx)
        val rh = min(r[3], page.height - ry)
        val whole = rx == 0 && ry == 0 && rw == page.width && rh == page.height
        if (min(rw, rh) >= MIN_SIDE) {
            val cut = if (whole) page else Bitmap.createBitmap(page, rx, ry, rw, rh)
            return Prepared(cut, 1.0, 0.0, rx.toDouble(), ry.toDouble())
        }
        // pad with the region's own border colour (the mean of its edge pixels), then enlarge by a whole factor
        val cut = Bitmap.createBitmap(page, rx, ry, rw, rh)
        var sr = 0L; var sg = 0L; var sb = 0L; var n = 0L
        for (y in 0 until rh) for (x in 0 until rw) {
            if (y == 0 || y == rh - 1 || x == 0 || x == rw - 1) {
                val c = cut.getPixel(x, y)
                sr += Color.red(c); sg += Color.green(c); sb += Color.blue(c); n++
            }
        }
        val fill = Color.rgb((sr / max(n, 1)).toInt(), (sg / max(n, 1)).toInt(), (sb / max(n, 1)).toInt())
        val margin = max(min(rw, rh) / 2, 16)
        val pw = rw + 2 * margin
        val ph = rh + 2 * margin
        val padded = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(padded)
        canvas.drawColor(fill)
        canvas.drawBitmap(cut, margin.toFloat(), margin.toFloat(), Paint())
        cut.recycle()
        val k = ((TARGET_SIDE + min(pw, ph) - 1) / min(pw, ph)).coerceIn(1, MAX_SCALE)
        if (k == 1) return Prepared(padded, 1.0, margin.toDouble(), rx.toDouble(), ry.toDouble())
        val big = Bitmap.createScaledBitmap(padded, pw * k, ph * k, true)
        padded.recycle()
        return Prepared(big, k.toDouble(), margin.toDouble(), rx.toDouble(), ry.toDouble())
    }

    private fun px(p: Prepared, v: Double, max: Int, x: Boolean): Int {
        val o = if (x) p.ox else p.oy
        return (v / p.scale - p.margin + o).roundToInt().coerceIn(0, max)
    }

    private fun box(p: Prepared, b: Rect?, w: Int, h: Int): List<Int> {
        if (b == null) return listOf(0, 0, 0, 0)
        val x0 = px(p, b.left.toDouble(), w, true)
        val y0 = px(p, b.top.toDouble(), h, false)
        val x1 = px(p, b.right.toDouble(), w, true)
        val y1 = px(p, b.bottom.toDouble(), h, false)
        return listOf(x0, y0, max(x1 - x0, 0), max(y1 - y0, 0))
    }

    private fun quad(p: Prepared, pts: Array<Point>?, w: Int, h: Int): List<Int>? {
        if (pts == null || pts.size != 4) return null
        val out = ArrayList<Int>(8)
        for (pt in pts) {
            out.add(px(p, pt.x.toDouble(), w, true))
            out.add(px(p, pt.y.toDouble(), h, false))
        }
        return out
    }

    private fun conf(c: Float): Int = (c.coerceIn(0f, 1f) * 1000f).roundToInt()

    private fun map(text: Text, p: Prepared, w: Int, h: Int): Map<String, Any?> {
        val blocks = ArrayList<Map<String, Any?>>()
        val lines = ArrayList<Map<String, Any?>>()
        var pageLang: String? = null
        for ((bi, b) in text.textBlocks.withIndex()) {
            val bb = b.boundingBox ?: unionOf(b.lines.mapNotNull { it.boundingBox })
            blocks.add(mapOf("box" to box(p, bb, w, h)))
            if (pageLang == null && b.recognizedLanguage != "und") pageLang = b.recognizedLanguage
            for (l in b.lines) {
                val words = ArrayList<Map<String, Any?>>()
                for (e in l.elements) {
                    if (e.text.isBlank()) continue
                    words.add(
                        mapOf(
                            "text" to e.text,
                            "box" to box(p, e.boundingBox, w, h),
                            "conf" to conf(e.confidence),
                            "lang" to e.recognizedLanguage,
                        ),
                    )
                }
                if (words.isEmpty()) continue
                lines.add(
                    mapOf(
                        "box" to box(p, l.boundingBox, w, h),
                        "quad" to quad(p, l.cornerPoints, w, h),
                        "conf" to conf(l.confidence),
                        "block" to bi,
                        "words" to words,
                    ),
                )
            }
        }
        return mapOf("blocks" to blocks, "lines" to lines, "lang" to pageLang)
    }

    private fun unionOf(rs: List<Rect>): Rect? {
        if (rs.isEmpty()) return null
        val u = Rect(rs[0])
        for (r in rs) u.union(r)
        return u
    }
}
