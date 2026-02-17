package com.openclaw.healthuploader.ui.chart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.max

class BedWakeLineChartView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
) : View(context, attrs) {

  private val bedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f) }
  private val wakePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f) }
  private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
  private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f) }

  private var bedtimes: List<Int?> = emptyList()
  private var wakes: List<Int?> = emptyList()

  fun setData(bedtimes: List<Int?>, wakes: List<Int?>) {
    this.bedtimes = bedtimes
    this.wakes = wakes
    invalidate()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)

    val n = max(bedtimes.size, wakes.size)
    if (n <= 0) return

    val left = paddingLeft.toFloat()
    val right = (width - paddingRight).toFloat()
    val top = paddingTop.toFloat()
    val bottom = (height - paddingBottom).toFloat()

    val w = right - left
    val h = bottom - top
    if (w <= 0f || h <= 0f) return

    val bedColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondary, 0xFF00897B.toInt())
    val wakeColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, 0xFF3F51B5.toInt())
    val gridColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline, 0xFF808080.toInt())

    bedPaint.color = bedColor
    wakePaint.color = wakeColor
    gridPaint.color = gridColor

    // Normalize into a "night timeline" to avoid midnight wrap jumps:
    // 18:00..23:59 stays, 00:00..11:59 shifts to +24h.
    fun norm(minOfDay: Int): Int = if (minOfDay < 12 * 60) minOfDay + 1440 else minOfDay

    val bedNorm = bedtimes.map { it?.let(::norm) }
    val wakeNorm = wakes.map { it?.let(::norm) }
    val all = (bedNorm + wakeNorm).filterNotNull()
    if (all.isEmpty()) {
      // nothing to draw
      return
    }

    var minY = all.minOrNull() ?: 0
    var maxY = all.maxOrNull() ?: 0
    if (minY == maxY) {
      minY -= 60
      maxY += 60
    }

    // Padding for readability
    minY -= 45
    maxY += 45

    val rangeY = max(1, maxY - minY)

    // Grid: 2 horizontal lines
    canvas.drawLine(left, top + h * 0.33f, right, top + h * 0.33f, gridPaint)
    canvas.drawLine(left, top + h * 0.66f, right, top + h * 0.66f, gridPaint)

    fun xAt(i: Int): Float {
      return if (n <= 1) (left + right) / 2f else left + (w * (i.toFloat() / (n - 1).toFloat()))
    }

    fun yAt(v: Int): Float {
      val frac = ((v - minY).toFloat() / rangeY.toFloat()).coerceIn(0f, 1f)
      return bottom - (frac * h)
    }

    drawLineSeries(canvas, bedNorm, xAt = ::xAt, yAt = ::yAt, linePaint = bedPaint, pointColor = bedColor)
    drawLineSeries(canvas, wakeNorm, xAt = ::xAt, yAt = ::yAt, linePaint = wakePaint, pointColor = wakeColor)
  }

  private fun drawLineSeries(
    canvas: Canvas,
    values: List<Int?>,
    xAt: (Int) -> Float,
    yAt: (Int) -> Float,
    linePaint: Paint,
    pointColor: Int,
  ) {
    val path = Path()
    var started = false
    for (i in values.indices) {
      val v = values[i] ?: continue
      val x = xAt(i)
      val y = yAt(v)
      if (!started) {
        path.moveTo(x, y)
        started = true
      } else {
        path.lineTo(x, y)
      }
    }
    if (started) canvas.drawPath(path, linePaint)

    pointPaint.color = pointColor
    val r = dp(3f)
    for (i in values.indices) {
      val v = values[i] ?: continue
      canvas.drawCircle(xAt(i), yAt(v), r, pointPaint)
    }
  }

  private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
