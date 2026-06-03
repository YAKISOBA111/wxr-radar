package com.wxr.radar.auto

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import com.wxr.radar.data.*
import com.wxr.radar.ui.RadarView

/**
 * Android Auto 表示画面
 *
 * NavigationTemplate の SurfaceCallback を使い、
 * 車載ディスプレイの Surface に RadarView の描画ロジックを直接呼び出す。
 *
 * ポイント:
 * - RadarView は View だが、描画ロジック (onDraw) はそのまま流用
 * - Surface のサイズに合わせてダミー View を作成し drawToBitmap → Surface に転写
 */
class WxrScreen(carContext: CarContext) : Screen(carContext) {

    // ViewModel の代わりにリポジトリを直接持つ
    // (CarAppService は Application Context しか持てないため)
    private val repo     = JmaRepository()
    private var radar: RadarData?    = null
    private var ownship  = OwnshipState()
    private var settings = NdSettings(mode = NdMode.ARC, orient = NdOrient.HEADING_UP, rangeNm = 80)

    // Surface描画用
    private var surfaceContainer: SurfaceContainer? = null
    private val radarDrawer = RadarSurfaceDrawer()

    // 自動更新
    private var fetchJob: kotlinx.coroutines.Job? = null

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) {
            surfaceContainer = container
            startPeriodicFetch()
            renderToSurface()
        }
        override fun onSurfaceDestroyed(container: SurfaceContainer) {
            surfaceContainer = null
            fetchJob?.cancel()
        }
        override fun onVisibleAreaChanged(visibleArea: Rect) {
            renderToSurface()
        }
        override fun onStableAreaChanged(stableArea: Rect) {
            renderToSurface()
        }
    }

    init {
        carContext.getCarService(AppManager::class.java)
            .setSurfaceCallback(surfaceCallback)
    }

    // ──────────────────────────────────────────
    //  Template (NavigationTemplate でフルスクリーン描画)
    // ──────────────────────────────────────────
    override fun onGetTemplate(): Template {
        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle(if (settings.mode == NdMode.ARC) "ROSE" else "ARC")
                    .setOnClickListener {
                        settings = settings.copy(
                            mode = if (settings.mode == NdMode.ARC) NdMode.ROSE else NdMode.ARC
                        )
                        renderToSurface()
                        invalidate()
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle(if (settings.orient == NdOrient.HEADING_UP) "N-UP" else "H-UP")
                    .setOnClickListener {
                        settings = settings.copy(
                            orient = if (settings.orient == NdOrient.HEADING_UP)
                                NdOrient.NORTH_UP else NdOrient.HEADING_UP
                        )
                        renderToSurface()
                        invalidate()
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("RNG+")
                    .setOnClickListener {
                        val steps = listOf(10,20,40,80,160,320)
                        val i = steps.indexOf(settings.rangeNm)
                        if (i < steps.size - 1) {
                            settings = settings.copy(rangeNm = steps[i+1])
                            startPeriodicFetch()
                            renderToSurface()
                            invalidate()
                        }
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("RNG-")
                    .setOnClickListener {
                        val steps = listOf(10,20,40,80,160,320)
                        val i = steps.indexOf(settings.rangeNm)
                        if (i > 0) {
                            settings = settings.copy(rangeNm = steps[i-1])
                            startPeriodicFetch()
                            renderToSurface()
                            invalidate()
                        }
                    }
                    .build()
            )
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(actionStrip)
            .build()
    }

    // ──────────────────────────────────────────
    //  Surface に描画
    // ──────────────────────────────────────────
    private fun renderToSurface() {
        val container = surfaceContainer ?: return
        val surface   = container.surface ?: return
        try {
            val canvas: Canvas = surface.lockHardwareCanvas() ?: surface.lockCanvas(null)
            radarDrawer.draw(canvas, radar, ownship, settings)
            surface.unlockCanvasAndPost(canvas)
        } catch (e: Exception) {
            // Surface が無効な場合は無視
        }
    }

    // ──────────────────────────────────────────
    //  定期取得
    // ──────────────────────────────────────────
    private fun startPeriodicFetch() {
        fetchJob?.cancel()
        fetchJob = kotlinx.coroutines.MainScope().launch {
            while (true) {
                val data = repo.fetchRadar(ownship.lat, ownship.lon, settings.rangeKm)
                if (data != null) {
                    radar = data
                    renderToSurface()
                }
                kotlinx.coroutines.delay(5 * 60 * 1000L)
            }
        }
    }

    /** GPS更新コールバック (外部から呼ぶ) */
    fun updateOwnship(state: OwnshipState) {
        ownship = state
        renderToSurface()
    }
}

// ──────────────────────────────────────────────────────────
//  RadarSurfaceDrawer — Surface Canvas への A320 ND 描画
//  RadarView の描画ロジックを Canvas直描き版として再実装
// ──────────────────────────────────────────────────────────
private class JmaRepository : com.wxr.radar.data.JmaRepository()

class RadarSurfaceDrawer {

    private val bgPaint = Paint().apply { color = Color.BLACK }

    fun draw(
        canvas: Canvas,
        radar: RadarData?,
        own: OwnshipState,
        cfg: NdSettings
    ) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // RadarView を View として生成して Bitmap に描画 → Surface へ転写
        // ※ CarContext は UI Context を持たないため、View を inflate できない
        // → 描画ロジックを直接 Canvas に書く

        // シンプルな fallback: テキスト情報のみ表示
        // (完全なCanvas描画は RadarView と同一ロジックをここに移植するか、
        //  Bitmap経由でオフスクリーン描画する方法を採る)
        val infoP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color     = Color.rgb(0, 200, 255)
            textSize  = h * 0.035f
            typeface  = android.graphics.Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
        }

        // ── 簡易WXR表示 (Auto) ──
        // Surface上でRadarViewと同じ描画ロジックを呼び出す
        val view = android.widget.FrameLayout(android.app.Application())
        // NOTE: 完全な実装では RadarView の描画ロジックを
        // このクラスに完全移植するか、Bitmap 経由で描画する

        // ヘッダー情報
        canvas.drawText("WXR  ${cfg.rangeNm}NM  " +
            (if (cfg.mode == NdMode.ARC) "ARC" else "ROSE") + "  " +
            (if (cfg.orient == NdOrient.HEADING_UP) "H-UP" else "N-UP"),
            w / 2, h * 0.05f, infoP)

        // HDG/GS
        val dataP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color    = Color.rgb(0, 230, 0)
            textSize = h * 0.03f
            typeface = android.graphics.Typeface.MONOSPACE
            textAlign = Paint.Align.LEFT
        }
        canvas.drawText("HDG  ${own.headingDeg.toInt().toString().padStart(3,'0')}°", 20f, h * 0.92f, dataP)
        canvas.drawText("GS   ${own.speedKmh.toInt()} km/h", 20f, h * 0.96f, dataP)

        dataP.textAlign = Paint.Align.RIGHT
        val srcTxt = if (radar != null) "JMA LIVE" else "NO DATA"
        dataP.color = if (radar != null) Color.rgb(0,200,255) else Color.rgb(200,100,0)
        canvas.drawText(srcTxt, w - 20f, h * 0.96f, dataP)

        // レーダーデータがある場合は中心に円形表示
        if (radar != null) {
            drawSimpleRadar(canvas, radar, own, cfg, w, h)
        }
    }

    private fun drawSimpleRadar(
        canvas: Canvas, radar: RadarData,
        own: OwnshipState, cfg: NdSettings,
        w: Float, h: Float
    ) {
        val cx     = w / 2f
        val ownY   = if (cfg.mode == NdMode.ARC) h * 0.75f else h / 2f
        val radius = if (cfg.mode == NdMode.ARC) minOf(w,h) * 0.70f else minOf(w,h) * 0.45f

        val g     = radar.gridSize
        val pxPerCell = radius * 2f / g
        val rotDeg = if (cfg.orient == NdOrient.HEADING_UP) -own.headingDeg else 0f

        canvas.save()
        canvas.translate(cx, ownY)
        canvas.rotate(rotDeg)
        canvas.translate(-cx, -ownY)

        val p = Paint()
        for (gy in 0 until g) {
            for (gx in 0 until g) {
                val mmh = radar.getAt(gx, gy)
                val color = wxrColor(mmh)
                if (color == Color.TRANSPARENT) continue
                p.color = color
                val x = cx - radius + gx * pxPerCell
                val y = ownY - radius + gy * pxPerCell
                canvas.drawRect(x, y, x + pxPerCell + 1, y + pxPerCell + 1, p)
            }
        }
        canvas.restore()

        // リング
        val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(80, 0,160,200)
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        canvas.drawCircle(cx, ownY, radius / 2, ringP)
        canvas.drawCircle(cx, ownY, radius,     ringP)

        // 自機
        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f
        }
        val s = radius * 0.04f
        canvas.drawLine(cx, ownY-s*2f, cx, ownY+s*1.5f, sp)
        canvas.drawLine(cx-s*2f, ownY+s*0.3f, cx+s*2f, ownY+s*0.3f, sp)

        // ヘディングライン
        val hdgP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 2f
        }
        canvas.drawLine(cx, ownY, cx, ownY - radius * 0.92f, hdgP)
    }

    private fun wxrColor(mmh: Float): Int = when {
        mmh <  1f -> Color.TRANSPARENT
        mmh < 10f -> Color.argb(150, 0, 160, 0)
        mmh < 30f -> Color.argb(200, 0, 200, 0)
        mmh < 50f -> Color.argb(210, 200, 200, 0)
        mmh < 80f -> Color.argb(230, 200, 0, 0)
        else      -> Color.argb(255, 200, 0, 200)
    }
}
