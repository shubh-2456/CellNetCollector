package com.cellnet.collector

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

class SignalChart(context: Context) : View(context) {
    private var values: List<Int?> = emptyList()
    private val band = Paint()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        line.color = Color.parseColor("#E6EDF7")
        line.strokeWidth = 4f
        line.style = Paint.Style.STROKE
        line.strokeJoin = Paint.Join.ROUND
        dot.color = Color.parseColor("#E6EDF7")
        txt.color = Color.parseColor("#8FA3C4")
        txt.textSize = 26f
    }

    fun setValues(v: List<Int?>) {
        values = ArrayList(v)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val lo = -130f
        val hi = -60f
        fun yOf(v: Float): Float = h - (v - lo) / (hi - lo) * h

        band.color = Color.parseColor("#3322C55E")
        canvas.drawRect(0f, yOf(hi), w, yOf(-85f), band)
        band.color = Color.parseColor("#33F59E0B")
        canvas.drawRect(0f, yOf(-85f), w, yOf(-100f), band)
        band.color = Color.parseColor("#33EF4444")
        canvas.drawRect(0f, yOf(-100f), w, yOf(lo), band)
        canvas.drawText("-85", 6f, yOf(-85f) - 6f, txt)
        canvas.drawText("-100", 6f, yOf(-100f) - 6f, txt)

        val n = values.size
        if (n == 0) {
            canvas.drawText("Waiting for readings...", w / 4, h / 2, txt)
            return
        }
        val step = if (n > 1) w / (n - 1) else 0f
        val path = Path()
        var open = false
        for (i in 0 until n) {
            val v = values[i]
            if (v == null) {
                open = false
                continue
            }
            val x = if (n > 1) i * step else w / 2
            val y = yOf(v.toFloat().coerceIn(lo, hi))
            if (!open) {
                path.moveTo(x, y)
                open = true
            } else {
                path.lineTo(x, y)
            }
            canvas.drawCircle(x, y, 5f, dot)
        }
        canvas.drawPath(path, line)
    }
}
