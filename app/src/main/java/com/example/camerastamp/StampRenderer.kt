package com.example.camerastamp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface

/**
 * Draws the company name / time / date / day / location stamp (and logo)
 * either directly onto a captured photo, or onto a standalone transparent
 * bitmap (used as a texture overlay when burning the stamp into video frames).
 */
object StampRenderer {

    data class StampData(
        val companyName: String,
        val timeText: String,      // "08.44"
        val dateText: String,      // "05 SEPTEMBER"
        val dayText: String,       // "SABTU"
        val locationText: String,  // full address string
        val logo: Bitmap?
    )

    /** Draws the stamp directly onto a copy of [source] (used for photos). */
    fun applyStamp(source: Bitmap, data: StampData): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        drawStamp(canvas, result.width, result.height, data)
        return result
    }

    /** Renders just the stamp onto a transparent bitmap of the given size (used for video overlay). */
    fun renderOverlayBitmap(width: Int, height: Int, data: StampData): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawStamp(canvas, width, height, data)
        return bmp
    }

    private fun textPaint(size: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = size
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        style = Paint.Style.FILL
    }

    private fun outlinePaint(base: Paint, strokeWidth: Float): Paint = Paint(base).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        this.strokeWidth = strokeWidth
    }

    /** Draws a thin black outline behind the white fill so text stays readable without any dark box/blur. */
    private fun drawCrispText(canvas: Canvas, text: String, x: Float, y: Float, fill: Paint, outline: Paint) {
        canvas.drawText(text, x, y, outline)
        canvas.drawText(text, x, y, fill)
    }

    private fun drawStamp(canvas: Canvas, widthPx: Int, heightPx: Int, data: StampData) {
        val w = widthPx.toFloat()
        val h = heightPx.toFloat()

        val scale = w / 1080f
        val marginX = 32f * scale
        val yellow = Color.parseColor("#FFEB0B")

        val locationFill = textPaint(26f * scale)
        val locationOutline = outlinePaint(locationFill, 3f * scale)

        val dayFill = textPaint(34f * scale)
        val dayOutline = outlinePaint(dayFill, 3.5f * scale)

        val timeFill = textPaint(58f * scale)
        val timeOutline = outlinePaint(timeFill, 4.5f * scale)

        val companyFill = textPaint(20f * scale)
        val companyOutline = outlinePaint(companyFill, 2.5f * scale)

        // Wrap location text onto up to 2 lines that fit within the width.
        val maxTextWidth = w - marginX * 2f - 40f * scale
        val locationLines = wrapText(data.locationText, locationFill, maxTextWidth, maxLines = 2)
        val lineSpacing = locationFill.textSize * 1.3f

        // ---- Layout, bottom-up. Each row uses a fixed line-height gap from the row below it,
        // so rows can never collide regardless of font metrics quirks. ----

        var y = h - 36f * scale

        for (i in locationLines.indices.reversed()) {
            drawCrispText(canvas, locationLines[i], marginX + 24f * scale, y, locationFill, locationOutline)
            y -= lineSpacing
        }

        y -= 14f * scale

        val dateDayLineHeight = dayFill.textSize * 1.25f

        val dayBaselineY = y
        drawCrispText(canvas, data.dayText, marginX + 46f * scale, dayBaselineY, dayFill, dayOutline)

        val dateBaselineY = dayBaselineY - dateDayLineHeight
        drawCrispText(canvas, data.dateText, marginX + 46f * scale, dateBaselineY, dayFill, dayOutline)

        // Big time text shares the day line's baseline (to its left, before the divider bar).
        val timeBaselineY = dayBaselineY
        drawCrispText(canvas, data.timeText, marginX, timeBaselineY, timeFill, timeOutline)

        // Yellow divider bar between time and date/day
        val timeWidth = timeFill.measureText(data.timeText)
        val barLeft = marginX + timeWidth + 14f * scale
        val barTop = dateBaselineY - dayFill.textSize
        val barBottom = dayBaselineY + 6f * scale
        val barPaint = Paint().apply { color = yellow }
        canvas.drawRect(barLeft, barTop, barLeft + 6f * scale, barBottom, barPaint)

        // Company name one line above the date/day block
        val companyBaselineY = dateBaselineY - dateDayLineHeight
        drawCrispText(canvas, data.companyName, marginX, companyBaselineY, companyFill, companyOutline)

        // Location pin marker (simple drawn dot) to the left of location text
        val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val pinCenterX = marginX + 8f * scale
        val pinCenterY = y + lineSpacing - locationFill.textSize * 0.4f
        canvas.drawCircle(pinCenterX, pinCenterY, 8f * scale, pinPaint)

        // Logo top-right corner watermark
        data.logo?.let { logo ->
            val logoTargetW = 130f * scale
            val ratio = logoTargetW / logo.width
            val logoTargetH = logo.height * ratio
            val logoRect = Rect(
                (w - logoTargetW - 28f * scale).toInt(),
                (28f * scale).toInt(),
                (w - 28f * scale).toInt(),
                (28f * scale + logoTargetH).toInt()
            )
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 235 }
            canvas.drawBitmap(logo, null, logoRect, paint)
        }
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float, maxLines: Int): List<String> {
        if (text.isBlank()) return listOf("")
        val words = text.split(" ").filter { it.isNotEmpty() }
        val lines = mutableListOf<String>()
        var current = StringBuilder()

        var i = 0
        while (i < words.size) {
            val word = words[i]
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current = StringBuilder(candidate)
                i++
            } else if (current.isEmpty()) {
                current = StringBuilder(word)
                i++
            } else {
                lines.add(current.toString())
                current = StringBuilder()
                if (lines.size == maxLines) break
            }
        }
        if (lines.size < maxLines && current.isNotEmpty()) {
            lines.add(current.toString())
        }

        val consumedWords = lines.joinToString(" ") { it }.split(" ").size
        if (lines.size == maxLines && consumedWords < words.size) {
            var lastLine = lines.last()
            while (paint.measureText("$lastLine…") > maxWidth && lastLine.length > 1) {
                lastLine = lastLine.dropLast(1)
            }
            lines[lines.size - 1] = "$lastLine…"
        }

        return lines.take(maxLines)
    }
}
