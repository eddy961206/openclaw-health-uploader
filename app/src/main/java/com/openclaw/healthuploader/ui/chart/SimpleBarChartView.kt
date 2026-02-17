package com.openclaw.healthuploader.ui.chart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.max

class SimpleBarChartView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
) : View(context, attrs) {

  private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
  private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f) }

  private var values: List<Int?> = emptyList()

  fun setData(values: List<Int?>) {
    this.values = values
    invalidate()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)

    val n = values.size
    if (n <= 0) return

    val left = paddingLeft.toFloat()
    val right = (width - paddingRight).toFloat()
    val top = paddingTop.toFloat()
    val bottom = (height - paddingBottom).toFloat()

    val w = right - left
    val h = bottom - top
    if (w <= 0f || h <= 0f) return

    val maxVal = max(1, values.filterNotNull().maxOrNull() ?: 1)

    val barColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, 0xFF3F51B5.toInt())
    val missingColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant, 0xFFB0B0B0.toInt())
    val gridColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline, 0xFF808080.toInt())

    // Baseline
    gridPaint.color = gridColor
    canvas.drawLine(left, bottom, right, bottom, gridPaint)

    val slotW = w / n.toFloat()
    val barW = slotW * 0.72f
    val gap = slotW - barW
    val radius = dp(6f)

    for (i in 0 until n) {
      val v = values[i]
      val x0 = left + (i * slotW) + (gap / 2f)
      val x1 = x0 + barW

      if (v == null || v <= 0) {
        paint.color = missingColor
        val y0 = bottom - dp(4f)
        canvas.drawRoundRect(RectF(x0, y0, x1, bottom), radius, radius, paint)
        continue
      }

      val frac = (v.toFloat() / maxVal.toFloat()).coerceIn(0f, 1f)
      val barH = h * frac
      val y0 = bottom - barH
      paint.color = barColor
      canvas.drawRoundRect(RectF(x0, y0, x1, bottom), radius, radius, paint)
    }
  }

  private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
