package com.wxr.radar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.wxr.radar.data.NdMode
import com.wxr.radar.data.NdOrient
import com.wxr.radar.data.NdSettings
import com.wxr.radar.data.OwnshipState
import com.wxr.radar.map.NdGeometry
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 航空機ND風の計器オーバーレイ（3層構成の最前面・透過View）。
 *
 * 旧 RadarView から計器描画のみを抽出したもの。雨雲・道路の描画は
 * 背面の MapLibre (MapController) に委譲しており、本Viewは
 * コンパスローズ / レンジリング / ヘディングライン / 自機シンボル /
 * ARCマスク / ラベル / OSM帰属表示 だけを描く。
 *
 * 平滑化ヘディングの値は onFrame コールバックで毎フレーム通知され、
 * MapController.applyCamera() がカメラ(bearing等)を同期する。
 */
class NdOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ──────────────────────────────────────────
    //  外部から注入するデータ
    // ──────────────────────────────────────────
    private var ownship:  OwnshipState = OwnshipState()
    private var settings: NdSettings   = NdSettings()

    /** 基図(PMTiles)が利用できない場合に注意表示を出す */
    var basemapMissing = false

    /** 平滑化済みヘディングを毎フレーム通知（地図カメラ同期用） */
    var onFrame: ((displayHeading: Float) -> Unit)? = null

    /** 表示用の平滑化ヘディング（プルプル防止） */
    private var displayHeading = 0f
    private var headingInitialized = false

    fun update(own: OwnshipState, cfg: NdSettings) {
        ownship  = own
        settings = cfg
        if (!headingInitialized) {
            displayHeading = own.headingDeg
            headingInitialized = true
        }
        invalidate()
    }

    // ──────────────────────────────────────────
    //  描画ループ（約30fps: ヘディング平滑化＋カメラ同期通知）
    // ──────────────────────────────────────────
    init {
        post(object : Runnable {
            override fun run() {
                smoothHeading()
                onFrame?.invoke(displayHeading)
                invalidate()
                postDelayed(this, 33)
            }
        })
    }

    /**
     * 目標ヘディングへ滑らかに追従させる（ローパス補間）。
     * 0°↔360°境界をまたぐ最短方向で補間し、微小なノイズは無視する。
     */
    private fun smoothHeading() {
        val target = ownship.headingDeg
        var diff = target - displayHeading
        while (diff > 180f) diff -= 360f
        while (diff < -180f) diff += 360f
        if (abs(diff) < 1.0f) return   // デッドバンド ~1°（微小ノイズ無視）
        displayHeading += diff * 0.08f   // ローパス係数 ~0.08
        displayHeading = ((displayHeading % 360f) + 360f) % 360f
    }

    // ──────────────────────────────────────────
    //  Paints
    // ──────────────────────────────────────────
    private val maskPaint    = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
    private val ringPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 0, 160, 200)
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
    }
    private val compassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val compassMinor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 0.8f
    }
    private val compassText  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface  = Typeface.MONOSPACE
    }
    private val hdgLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.YELLOW
        style       = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val trackPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.rgb(0, 230, 0)
        style       = Paint.Style.STROKE
        strokeWidth = 1.5f
        pathEffect  = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }
    private val ownshipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.WHITE
        style       = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val ownshipFill  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val labelPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.rgb(0, 220, 240)
        textAlign = Paint.Align.LEFT
        typeface  = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val wxrLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.rgb(0, 230, 0)
        typeface  = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val attribPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.argb(190, 200, 200, 200)
        textAlign = Paint.Align.RIGHT
    }

    // ──────────────────────────────────────────
    //  onDraw
    // ──────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val side   = min(w, h)
        val geo    = NdGeometry.compute(w.toDouble(), h.toDouble(), settings.mode)
        val cx     = geo.cxPx.toFloat()
        val ownY   = geo.ownYPx.toFloat()
        val radius = geo.radiusPx.toFloat()
        val isArc  = settings.mode == NdMode.ARC

        val rotRad = if (settings.orient == NdOrient.HEADING_UP)
            Math.toRadians(-displayHeading.toDouble()).toFloat() else 0f

        // ── 計器エリア外を黒マスク（背面の地図を計器形状にくり抜く） ──
        drawOutsideMask(canvas, cx, ownY, radius, isArc, w, h)

        // ── 距離リング ──
        drawRangeRings(canvas, cx, ownY, radius, rotRad, side)

        // ── コンパスローズ ──
        drawCompassRose(canvas, cx, ownY, radius, rotRad, side)

        // ── 自機シンボル ──
        drawOwnship(canvas, cx, ownY, radius, side)

        // ── ラベル類 ──
        drawLabels(canvas, w, h, side)
    }

    // ──────────────────────────────────────────
    //  計器エリア外マスク
    // ──────────────────────────────────────────
    private fun drawOutsideMask(
        canvas: Canvas, cx: Float, ownY: Float, radius: Float,
        isArc: Boolean, w: Float, h: Float
    ) {
        val path = Path()
        path.addRect(0f, 0f, w, h, Path.Direction.CW)
        if (isArc) {
            val startDeg = -90f - 60f
            path.moveTo(cx, ownY)
            path.arcTo(
                RectF(cx - radius - 1, ownY - radius - 1, cx + radius + 1, ownY + radius + 1),
                startDeg, 120f
            )
            path.close()
        } else {
            path.addCircle(cx, ownY, radius, Path.Direction.CW)
        }
        path.fillType = Path.FillType.EVEN_ODD
        canvas.drawPath(path, maskPaint)
    }

    // ──────────────────────────────────────────
    //  距離リング
    // ──────────────────────────────────────────
    private fun drawRangeRings(
        canvas: Canvas, cx: Float, ownY: Float,
        radius: Float, rotRad: Float, side: Float
    ) {
        val isArc  = settings.mode == NdMode.ARC
        val rings  = if (isArc) 2 else 4
        val fs     = (side * 0.034f).coerceAtLeast(12f)

        for (i in 1..rings) {
            val r = radius * i / rings

            if (isArc) {
                val oval = RectF(cx - r, ownY - r, cx + r, ownY + r)
                canvas.drawArc(oval, -150f, 120f, false, ringPaint)
            } else {
                canvas.drawCircle(cx, ownY, r, ringPaint)
            }

            // 距離ラベル（現在の単位。最外周は単位付き・大きめで強調）
            val outermost = i == rings
            val label = if (outermost) {
                "${settings.ringLabel(i, rings)} ${settings.distanceUnit.label}"
            } else {
                settings.ringLabel(i, rings)
            }
            labelPaint.textSize = if (outermost) fs * 1.25f else fs
            labelPaint.alpha    = if (outermost) 255 else 200
            val labelAng = if (isArc) -Math.PI / 2 - Math.PI / 4 else rotRad.toDouble() - Math.PI / 2 + 0.12
            val lx = cx + cos(labelAng).toFloat() * r + 8f
            val ly = ownY + sin(labelAng).toFloat() * r - 4f
            canvas.drawText(label, lx, ly, labelPaint)
        }
    }

    // ──────────────────────────────────────────
    //  コンパスローズ
    // ──────────────────────────────────────────
    private fun drawCompassRose(
        canvas: Canvas, cx: Float, ownY: Float,
        radius: Float, rotRad: Float, side: Float
    ) {
        val isArc = settings.mode == NdMode.ARC
        val fs    = (side * 0.028f).coerceAtLeast(9f)
        compassText.textSize = fs

        for (a in 0 until 360 step 10) {
            val angRad = Math.toRadians(a.toDouble()) + rotRad - Math.PI / 2
            val isMaj  = a % 30 == 0
            val isCard = a == 0 || a == 90 || a == 180 || a == 270

            // ARCモード: 表示範囲±68°のみ（平滑化値で判定しチラつき防止）
            if (isArc) {
                var rel = (a - displayHeading).toDouble()
                while (rel >  180) rel -= 360
                while (rel < -180) rel += 360
                if (abs(rel) > 68) continue
            }

            val cosA = cos(angRad).toFloat()
            val sinA = sin(angRad).toFloat()

            val tickLen = if (isMaj) 14f else 7f
            val t0 = radius - tickLen
            val t1 = radius
            val paint = if (isMaj) compassPaint else compassMinor

            canvas.drawLine(
                cx + cosA * t0, ownY + sinA * t0,
                cx + cosA * t1, ownY + sinA * t1,
                paint
            )

            if (isMaj) {
                val label = when (a) {
                    0 -> "N"; 90 -> "E"; 180 -> "S"; 270 -> "W"
                    else -> (a / 10).toString().padStart(2, '0')
                }
                val lr = radius - 24f
                canvas.save()
                canvas.translate(cx + cosA * lr, ownY + sinA * lr)
                canvas.rotate(Math.toDegrees(angRad).toFloat() + 90f)
                compassText.color = if (isCard) Color.WHITE else Color.argb(180, 255, 255, 255)
                compassText.isFakeBoldText = isCard
                canvas.drawText(label, 0f, fs * 0.4f, compassText)
                canvas.restore()
            }
        }
    }

    // ──────────────────────────────────────────
    //  自機シンボル（汎用シェブロン）
    // ──────────────────────────────────────────
    private fun drawOwnship(canvas: Canvas, cx: Float, ownY: Float, radius: Float, side: Float) {
        val s     = (side * 0.036f).coerceAtLeast(12f)
        val isArc = settings.mode == NdMode.ARC

        // ヘディングライン (黄)
        val hdgLen = if (isArc) radius * 0.94f else radius * 0.90f
        canvas.drawLine(cx, ownY, cx, ownY - hdgLen, hdgLinePaint)
        // 先端三角マーカー
        val tri = Path().apply {
            moveTo(cx,      ownY - hdgLen)
            lineTo(cx - 5f, ownY - hdgLen + 10f)
            lineTo(cx + 5f, ownY - hdgLen + 10f)
            close()
        }
        canvas.drawPath(tri, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.YELLOW; style = Paint.Style.FILL
        })

        // グラウンドトラックライン (緑破線・速度に応じて伸びる)
        val trkAng = if (settings.orient == NdOrient.HEADING_UP) {
            -Math.PI.toFloat() / 2f
        } else {
            (displayHeading * Math.PI.toFloat() / 180f) - Math.PI.toFloat() / 2f
        }
        val vecLen = (radius * 0.28f).coerceAtMost(ownship.speedKmh * 0.3f + 10f)
        canvas.drawLine(
            cx, ownY,
            cx + cos(trkAng) * (vecLen + s),
            ownY + sin(trkAng) * (vecLen + s),
            trackPaint
        )

        // 自機シンボル: シェブロン（バイク/車両を問わない汎用形状）
        canvas.save()
        canvas.translate(cx, ownY)
        ownshipPaint.strokeWidth = (side * 0.004f).coerceAtLeast(2f)

        val chevron = Path().apply {
            moveTo(0f, -s)              // 先端
            lineTo(-s * 0.75f, s * 0.7f) // 左後端
            lineTo(0f, s * 0.35f)        // 中央くびれ
            lineTo(s * 0.75f, s * 0.7f)  // 右後端
            close()
        }
        canvas.drawPath(chevron, ownshipPaint)

        // 中心ドット
        canvas.drawCircle(0f, 0f, 3.5f, ownshipFill)
        canvas.restore()
    }

    // ──────────────────────────────────────────
    //  ラベル
    // ──────────────────────────────────────────
    private fun drawLabels(canvas: Canvas, w: Float, h: Float, side: Float) {
        val fs = (side * 0.025f).coerceAtLeast(9f)
        wxrLabelPaint.textSize = fs

        if (settings.wxrOn) {
            wxrLabelPaint.color = Color.rgb(0, 230, 0)
            canvas.drawText("WXR", 10f, h - 10f, wxrLabelPaint)
        }
        if (settings.orient == NdOrient.HEADING_UP) {
            wxrLabelPaint.color = Color.rgb(0, 200, 255)
            canvas.drawText("↑ HDG", w / 2f - 20f, 20f, wxrLabelPaint)
        }
        if (basemapMissing) {
            wxrLabelPaint.color = Color.rgb(255, 170, 0)
            canvas.drawText("NO BASEMAP", 10f, 20f, wxrLabelPaint)
        }

        // OSM 帰属表示（常時・右下）
        attribPaint.textSize = (side * 0.022f).coerceAtLeast(8f)
        canvas.drawText("© OpenStreetMap contributors", w - 8f, h - 8f, attribPaint)
    }

    // ──────────────────────────────────────────
    //  View サイズ (正方形を強制)
    // ──────────────────────────────────────────
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val size = min(measuredWidth, measuredHeight)
        setMeasuredDimension(size, size)
    }
}
