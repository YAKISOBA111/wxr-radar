package com.wxr.radar.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.wxr.radar.data.*
import kotlin.math.*

/**
 * A320 Navigation Display スタイルの気象レーダー描画 View
 *
 * スマホ縦画面・Android Auto 両対応の共通描画コア。
 * データは外部 (ViewModel) から set メソッドで注入する。
 */
class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ──────────────────────────────────────────
    //  外部から注入するデータ
    // ──────────────────────────────────────────
    private var radarData: RadarData?   = null
    private var ownship:   OwnshipState = OwnshipState()
    private var settings:  NdSettings   = NdSettings()

    fun update(radar: RadarData?, own: OwnshipState, cfg: NdSettings) {
        radarData = radar
        ownship   = own
        settings  = cfg
        invalidate()
    }

    // ──────────────────────────────────────────
    //  スイープアニメーション
    // ──────────────────────────────────────────
    private var sweepAngle = 0f

    init {
        // 約 30fps でスイープ回転
        post(object : Runnable {
            override fun run() {
                sweepAngle = (sweepAngle + 1.2f) % 360f
                invalidate()
                postDelayed(this, 33)
            }
        })
    }

    // ──────────────────────────────────────────
    //  Paints (A320カラーパレット準拠)
    // ──────────────────────────────────────────
    private val bgPaint      = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }

    // WXR降水色 (4段階)
    private val wxrGreen     = Color.rgb(  0, 200,   0)
    private val wxrYellow    = Color.rgb(200, 200,   0)
    private val wxrRed       = Color.rgb(200,   0,   0)
    private val wxrMagenta   = Color.rgb(200,   0, 200)

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
    private val labelPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.argb(140, 0, 200, 220)
        textAlign = Paint.Align.LEFT
        typeface  = Typeface.MONOSPACE
    }
    private val wxrLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.rgb(0, 230, 0)
        typeface  = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }

    // ──────────────────────────────────────────
    //  onDraw
    // ──────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        // 描画は正方形領域に収める
        val side   = min(w, h)
        val cx     = w / 2f
        // ARCモード: 自機を下側78%に置く (A320 ND準拠)
        val isArc  = settings.mode == NdMode.ARC
        val ownY   = if (isArc) h * 0.78f else h / 2f
        val radius = if (isArc) side * 0.72f else side * 0.46f

        // 背景
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // H-UP: 地図・レーダーを-heading分回転させる
        val rotDeg  = if (settings.orient == NdOrient.HEADING_UP) -ownship.headingDeg else 0f
        val rotRad  = Math.toRadians(rotDeg.toDouble()).toFloat()

        // ── クリップ ──
        canvas.save()
        val clipPath = buildClipPath(cx, ownY, radius, isArc)
        canvas.clipPath(clipPath)

        // ── WXRレーダー描画 ──
        if (settings.wxrOn) {
            drawWxr(canvas, cx, ownY, radius, rotDeg)
        }

        // ── スイープ ──
        if (settings.wxrOn) {
            drawSweep(canvas, cx, ownY, radius, rotDeg)
        }

        canvas.restore() // clip解除

        // ── ARC外側を黒塗り ──
        if (isArc) {
            drawArcMask(canvas, cx, ownY, radius)
        }

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
    //  クリップパス
    // ──────────────────────────────────────────
    private fun buildClipPath(cx: Float, ownY: Float, radius: Float, isArc: Boolean): Path {
        val path = Path()
        if (isArc) {
            val startDeg = -90f - 60f
            val sweepDeg = 120f
            path.moveTo(cx, ownY)
            path.arcTo(
                RectF(cx-radius, ownY-radius, cx+radius, ownY+radius),
                startDeg, sweepDeg
            )
            path.close()
        } else {
            path.addCircle(cx, ownY, radius, Path.Direction.CW)
        }
        return path
    }

    // ──────────────────────────────────────────
    //  WXR レーダー描画
    // ──────────────────────────────────────────
    private fun drawWxr(canvas: Canvas, cx: Float, ownY: Float, radius: Float, rotDeg: Float) {
        val data = radarData ?: return
        val g    = data.gridSize

        // オフスクリーン Bitmap にグリッドを描画
        val bmp = Bitmap.createBitmap(g, g, Bitmap.Config.ARGB_8888)
        for (gy in 0 until g) {
            for (gx in 0 until g) {
                val mmh = data.getAt(gx, gy)
                val color = wxrColor(mmh)
                if (color != Color.TRANSPARENT) bmp.setPixel(gx, gy, color)
            }
        }

        val pxPerKm = radius / settings.rangeKm.toFloat()

        canvas.save()
        canvas.translate(cx, ownY)
        canvas.rotate(rotDeg)
        canvas.translate(-cx, -ownY)

        val bounds = data.bounds
        val kmPerDeg = 111.0
        val cosLat   = cos(Math.toRadians(ownship.lat))
        val latSpan  = ((bounds.latMax - bounds.latMin) * kmPerDeg).toFloat()
        val lonSpan  = ((bounds.lonMax - bounds.lonMin) * kmPerDeg * cosLat).toFloat()
        val dw = lonSpan * pxPerKm
        val dh = latSpan * pxPerKm
        val ox = ((ownship.lon - bounds.lonMin) * kmPerDeg * cosLat).toFloat() * pxPerKm
        val oy = ((bounds.latMax - ownship.lat) * kmPerDeg).toFloat() * pxPerKm

        val dest = RectF(cx - ox, ownY - oy, cx - ox + dw, ownY - oy + dh)
        canvas.drawBitmap(bmp, null, dest, null)
        canvas.restore()
        bmp.recycle()
    }

    private fun wxrColor(mmh: Float): Int = when {
        mmh <  1f -> Color.TRANSPARENT
        mmh < 10f -> Color.argb(180,   0, 160,   0)
        mmh < 30f -> Color.argb(200,   0, 200,   0)   // 緑
        mmh < 50f -> Color.argb(210, 200, 200,   0)   // 黄
        mmh < 80f -> Color.argb(230, 200,   0,   0)   // 赤
        else      -> Color.argb(255, 200,   0, 200)   // マゼンタ
    }

    // ──────────────────────────────────────────
    //  スイープ
    // ──────────────────────────────────────────
    private fun drawSweep(canvas: Canvas, cx: Float, ownY: Float, radius: Float, rotDeg: Float) {
        val shader = SweepGradient(
            cx, ownY,
            intArrayOf(
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                Color.argb(70, 0, 200, 60)
            ),
            floatArrayOf(0f, 0.85f, 1f)
        )
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.shader = shader
        }
        canvas.save()
        canvas.translate(cx, ownY)
        canvas.rotate(rotDeg + sweepAngle - 90f)
        canvas.translate(-cx, -ownY)
        canvas.drawCircle(cx, ownY, radius, p)
        canvas.restore()
    }

    // ──────────────────────────────────────────
    //  ARC マスク
    // ──────────────────────────────────────────
    private fun drawArcMask(canvas: Canvas, cx: Float, ownY: Float, radius: Float) {
        val maskPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        val path = Path()
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        // 扇形をくり抜く (evenOdd)
        val startDeg = -90f - 60f
        path.moveTo(cx, ownY)
        path.arcTo(
            RectF(cx-radius-1, ownY-radius-1, cx+radius+1, ownY+radius+1),
            startDeg, 120f
        )
        path.close()
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
        val rings  = if (settings.mode == NdMode.ARC) 2 else 4
        val isArc  = settings.mode == NdMode.ARC
        val fs     = (side * 0.025f).coerceAtLeast(9f)
        labelPaint.textSize = fs

        for (i in 1..rings) {
            val r  = radius * i / rings
            val nm = (settings.rangeNm * i / rings)

            if (isArc) {
                val oval = RectF(cx-r, ownY-r, cx+r, ownY+r)
                canvas.drawArc(oval, -150f, 120f, false, ringPaint)
            } else {
                canvas.drawCircle(cx, ownY, r, ringPaint)
            }

            // 距離ラベル
            val labelAng = if (isArc) -Math.PI/2 - Math.PI/4 else rotRad.toDouble() - Math.PI/2 + 0.12
            val lx = cx + cos(labelAng).toFloat() * r + 6f
            val ly = ownY + sin(labelAng).toFloat() * r - 3f
            canvas.drawText("$nm", lx, ly, labelPaint)
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

            // ARCモード: 表示範囲±65°のみ
            if (isArc) {
                var rel = (a - ownship.headingDeg).toDouble()
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
                    0   -> "N"; 90  -> "E"; 180 -> "S"; 270 -> "W"
                    else -> (a / 10).toString().padStart(2, '0')
                }
                val lr = radius - 24f
                canvas.save()
                canvas.translate(cx + cosA * lr, ownY + sinA * lr)
                canvas.rotate(Math.toDegrees(angRad).toFloat() + 90f)
                compassText.color  = if (isCard) Color.WHITE else Color.argb(180, 255, 255, 255)
                compassText.isFakeBoldText = isCard
                canvas.drawText(label, 0f, fs * 0.4f, compassText)
                canvas.restore()
            }
        }
    }

    // ──────────────────────────────────────────
    //  自機シンボル
    // ──────────────────────────────────────────
    private fun drawOwnship(canvas: Canvas, cx: Float, ownY: Float, radius: Float, side: Float) {
        val s     = (side * 0.036f).coerceAtLeast(12f)
        val isArc = settings.mode == NdMode.ARC

        // ヘディングライン (黄色 — A320 NDの特徴)
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

        // グラウンドトラックライン (緑破線)
        val trkAng = if (settings.orient == NdOrient.HEADING_UP) {
            -Math.PI.toFloat() / 2f     // 常に上向き
        } else {
            (ownship.headingDeg * Math.PI.toFloat() / 180f) - Math.PI.toFloat() / 2f
        }
        val vecLen = (radius * 0.28f).coerceAtMost(ownship.speedKmh * 0.3f + 10f)
        canvas.drawLine(
            cx, ownY,
            cx + cos(trkAng) * (vecLen + s),
            ownY + sin(trkAng) * (vecLen + s),
            trackPaint
        )

        // 機体シンボル (A320スタイル)
        canvas.save()
        canvas.translate(cx, ownY)

        ownshipPaint.strokeWidth = (side * 0.003f).coerceAtLeast(1.5f)
        // 胴体
        canvas.drawLine(0f, -s, 0f, s * 0.7f, ownshipPaint)
        // 主翼 (後退翼)
        canvas.drawLine(-s * 1.1f, s * 0.15f, 0f, -s * 0.1f, ownshipPaint)
        canvas.drawLine( s * 1.1f, s * 0.15f, 0f, -s * 0.1f, ownshipPaint)
        // 尾翼
        canvas.drawLine(-s * 0.45f, s * 0.55f, 0f, s * 0.3f, ownshipPaint)
        canvas.drawLine( s * 0.45f, s * 0.55f, 0f, s * 0.3f, ownshipPaint)

        // 中心ドット
        canvas.drawCircle(0f, 0f, 3.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.FILL
        })
        canvas.restore()
    }

    // ──────────────────────────────────────────
    //  ラベル
    // ──────────────────────────────────────────
    private fun drawLabels(canvas: Canvas, w: Float, h: Float, side: Float) {
        val fs = (side * 0.025f).coerceAtLeast(9f)
        wxrLabelPaint.textSize = fs

        if (settings.wxrOn) {
            canvas.drawText("WXR", 10f, h - 10f, wxrLabelPaint)
        }
        if (settings.orient == NdOrient.HEADING_UP) {
            wxrLabelPaint.color = Color.rgb(0, 200, 255)
            canvas.drawText("↑ HDG", w / 2f - 20f, 20f, wxrLabelPaint)
            wxrLabelPaint.color = Color.rgb(0, 230, 0)
        }
    }

    // ──────────────────────────────────────────
    //  View サイズ (正方形を強制)
    // ──────────────────────────────────────────
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // 正方形: 小さい方に合わせる
        val size = min(measuredWidth, measuredHeight)
        setMeasuredDimension(size, size)
    }
}
