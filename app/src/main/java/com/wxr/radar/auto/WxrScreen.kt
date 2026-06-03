package com.wxr.radar.auto

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.wxr.radar.data.FetchResult
import com.wxr.radar.data.HeadingSource
import com.wxr.radar.data.JmaRepository
import com.wxr.radar.data.NdMode
import com.wxr.radar.data.NdOrient
import com.wxr.radar.data.NdSettings
import com.wxr.radar.data.OwnshipState
import com.wxr.radar.data.RadarData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class WxrCarAppService : androidx.car.app.CarAppService() {
    override fun createHostValidator() = androidx.car.app.validation.HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession() = WxrSession()
}

class WxrSession : androidx.car.app.Session() {
    override fun onCreateScreen(intent: android.content.Intent): Screen = WxrScreen(carContext)
}

class WxrScreen(carContext: CarContext) : Screen(carContext) {

    private val repo    = JmaRepository()
    private val scope   = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var fetchJob: Job? = null

    private var radar: RadarData?   = null
    private var ownship = OwnshipState()
    private var settings = NdSettings(mode = NdMode.ARC, orient = NdOrient.HEADING_UP, rangeNm = 80)

    private var surfaceContainer: SurfaceContainer? = null
    private var sweepAngle = 0f
    private var statusText = "受信待機"

    // GPS
    private val fusedClient =
        LocationServices.getFusedLocationProviderClient(carContext)
    private var firstFixDone = false
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            val speedKmh = (loc.speed * 3.6f).coerceAtLeast(0f)
            // GPS速度>3km/h かつ bearing有効ならGPS方位、それ以外は前回方位を維持
            val heading = if (speedKmh > 3f && loc.hasBearing()) loc.bearing else ownship.headingDeg
            val source  = if (speedKmh > 3f && loc.hasBearing()) HeadingSource.GPS else ownship.headingSource
            ownship = ownship.copy(
                lat = loc.latitude,
                lon = loc.longitude,
                headingDeg = heading,
                speedKmh = speedKmh,
                headingSource = source
            )
            // 初回の位置確定後に実際の現在地でレーダーを取得
            if (!firstFixDone) {
                firstFixDone = true
                startFetch()
            }
            renderToSurface()
        }
    }

    // スイープアニメーション
    private val sweepJob = scope.launch {
        while (isActive) {
            sweepAngle = (sweepAngle + 1.5f) % 360f
            renderToSurface()
            delay(33)
        }
    }

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) {
            surfaceContainer = container
            startFetch()
        }
        override fun onSurfaceDestroyed(container: SurfaceContainer) {
            surfaceContainer = null
        }
    }

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
        startLocationUpdates()
        // Screen破棄時にコルーチンとGPSをクリーンアップ
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                scope.cancel()
                fusedClient.removeLocationUpdates(locationCallback)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val fine = Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(carContext, fine) != PackageManager.PERMISSION_GRANTED) {
            // Auto環境では権限はスマホ側アプリで付与済みの想定。未付与なら初期位置のまま。
            return
        }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .build()
        fusedClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
    }

    override fun onGetTemplate(): Template {
        val modeLabel   = if (settings.mode   == NdMode.ARC)           "ROSE" else "ARC"
        val orientLabel = if (settings.orient == NdOrient.HEADING_UP)  "N-UP" else "H-UP"

        return NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(Action.Builder().setTitle(modeLabel).setOnClickListener {
                        settings = settings.copy(mode = if (settings.mode == NdMode.ARC) NdMode.ROSE else NdMode.ARC)
                        invalidate()
                    }.build())
                    .addAction(Action.Builder().setTitle(orientLabel).setOnClickListener {
                        settings = settings.copy(orient = if (settings.orient == NdOrient.HEADING_UP) NdOrient.NORTH_UP else NdOrient.HEADING_UP)
                        invalidate()
                    }.build())
                    .addAction(Action.Builder().setTitle("RNG+").setOnClickListener {
                        val steps = listOf(10,20,40,80,160,320)
                        val i = steps.indexOf(settings.rangeNm)
                        if (i < steps.size - 1) { settings = settings.copy(rangeNm = steps[i+1]); startFetch(); invalidate() }
                    }.build())
                    .addAction(Action.Builder().setTitle("RNG-").setOnClickListener {
                        val steps = listOf(10,20,40,80,160,320)
                        val i = steps.indexOf(settings.rangeNm)
                        if (i > 0) { settings = settings.copy(rangeNm = steps[i-1]); startFetch(); invalidate() }
                    }.build())
                    .build()
            )
            .build()
    }

    /** 外部（テストや別データ源）から自機状態を注入する場合に使用 */
    fun updateOwnship(state: OwnshipState) {
        ownship = state
    }

    private fun startFetch() {
        fetchJob?.cancel()
        fetchJob = scope.launch {
            while (isActive) {
                when (val result = repo.fetch(ownship.lat, ownship.lon, settings.rangeKm)) {
                    is FetchResult.Success -> {
                        radar = result.data
                        val t = result.basetime
                        val hhmm = if (t.length >= 12) "${t.substring(8,10)}:${t.substring(10,12)}" else "--:--"
                        statusText = when {
                            result.hasMissingTiles ->
                                "△ $hhmm 欠測${result.tilesRequested - result.tilesReceived}"
                            result.hasPrecip -> "● $hhmm 降水あり"
                            else -> "● $hhmm 降水なし"
                        }
                    }
                    is FetchResult.NetworkError -> {
                        statusText = "✕ 受信失敗"
                    }
                }
                renderToSurface()
                delay(5 * 60 * 1000L)
            }
        }
    }

    private fun renderToSurface() {
        val container = surfaceContainer ?: return
        val surface   = container.surface ?: return
        try {
            val canvas = surface.lockCanvas(null) ?: return
            NDDrawer.draw(canvas, radar, ownship, settings, sweepAngle, statusText)
            surface.unlockCanvasAndPost(canvas)
        } catch (_: Exception) {}
    }
}

// ─────────────────────────────────────────────────────────
//  NDDrawer: A320 NDスタイル描画 (Surface Canvas 直接描画)
//  RadarView と同一ロジック
// ─────────────────────────────────────────────────────────
object NDDrawer {

    private val bgPaint      = Paint().apply { color = Color.BLACK }
    private val ringPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80,0,160,200); style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val compassMaj   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val compassMin   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100,255,255,255); style = Paint.Style.STROKE; strokeWidth = 0.8f }
    private val compassTxt   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.MONOSPACE }
    private val hdgLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 2.5f }
    private val trackPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0,230,0); style = Paint.Style.STROKE; strokeWidth = 1.5f
        pathEffect = DashPathEffect(floatArrayOf(8f,6f), 0f) }
    private val ownPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f }
    private val labelPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140,0,200,220); typeface = Typeface.MONOSPACE }
    private val wxrLabel     = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0,230,0)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
    private val infoP        = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0,200,255); typeface = Typeface.MONOSPACE; textAlign = Paint.Align.LEFT }

    fun draw(canvas: Canvas, radar: RadarData?, own: OwnshipState, cfg: NdSettings, sweepAngle: Float, statusText: String) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val side   = min(w, h)
        val isArc  = cfg.mode == NdMode.ARC
        val cx     = w / 2f
        val ownY   = if (isArc) h * 0.78f else h / 2f
        val radius = if (isArc) side * 0.72f else side * 0.46f

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val rotDeg = if (cfg.orient == NdOrient.HEADING_UP) -own.headingDeg else 0f

        // クリップ
        canvas.save()
        canvas.clipPath(clipPath(cx, ownY, radius, isArc))

        // WXR
        if (cfg.wxrOn && radar != null) drawWxr(canvas, radar, own, cfg, cx, ownY, radius, rotDeg)

        // スイープ
        if (cfg.wxrOn) drawSweep(canvas, cx, ownY, radius, rotDeg, sweepAngle)

        canvas.restore()

        // ARCマスク
        if (isArc) drawArcMask(canvas, cx, ownY, radius, w, h)

        // 距離リング
        drawRings(canvas, cx, ownY, radius, cfg, side)

        // コンパス
        drawCompass(canvas, cx, ownY, radius, rotDeg, cfg, own, side)

        // 自機
        drawOwnship(canvas, cx, ownY, radius, cfg, own, side)

        // ラベル
        val fs = (side * 0.025f).coerceAtLeast(9f)
        wxrLabel.textSize = fs
        infoP.textSize    = fs * 0.9f
        if (cfg.wxrOn) canvas.drawText("WXR", 12f, h - 12f, wxrLabel)
        canvas.drawText("HDG ${own.headingDeg.toInt().toString().padStart(3,'0')}°  GS ${own.speedKmh.toInt()}km/h  RNG ${cfg.rangeNm}NM", 12f, 22f, infoP)
        // データ受信状態を右上に表示
        val statusP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = fs * 0.9f
            textAlign = Paint.Align.RIGHT
            color = when {
                statusText.startsWith("✕") -> Color.rgb(255, 64, 64)
                statusText.startsWith("△") -> Color.rgb(255, 204, 0)
                statusText.startsWith("●") -> Color.rgb(0, 224, 0)
                else -> Color.rgb(150, 150, 150)
            }
        }
        canvas.drawText(statusText, w - 12f, 22f, statusP)
    }

    private fun clipPath(cx: Float, ownY: Float, radius: Float, isArc: Boolean): Path {
        val p = Path()
        if (isArc) {
            p.moveTo(cx, ownY)
            p.arcTo(RectF(cx-radius,ownY-radius,cx+radius,ownY+radius), -150f, 120f)
            p.close()
        } else {
            p.addCircle(cx, ownY, radius, Path.Direction.CW)
        }
        return p
    }

    private fun drawWxr(canvas: Canvas, radar: RadarData, own: OwnshipState, cfg: NdSettings,
                        cx: Float, ownY: Float, radius: Float, rotDeg: Float) {
        val g        = radar.gridSize
        val pxPerKm  = radius / cfg.rangeKm.toFloat()
        val kmPerDeg = 111.0
        val cosLat   = cos(Math.toRadians(own.lat))
        val b        = radar.bounds
        val latSpan  = ((b.latMax - b.latMin) * kmPerDeg).toFloat()
        val lonSpan  = ((b.lonMax - b.lonMin) * kmPerDeg * cosLat).toFloat()
        val dw = lonSpan * pxPerKm
        val dh = latSpan * pxPerKm
        val ox = ((own.lon - b.lonMin) * kmPerDeg * cosLat).toFloat() * pxPerKm
        val oy = ((b.latMax - own.lat) * kmPerDeg).toFloat() * pxPerKm

        canvas.save()
        canvas.translate(cx, ownY); canvas.rotate(rotDeg); canvas.translate(-cx, -ownY)

        val cellW = dw / g; val cellH = dh / g
        val p = Paint()
        for (gy in 0 until g) {
            for (gx in 0 until g) {
                val color = wxrColor(radar.getAt(gx, gy))
                if (color == Color.TRANSPARENT) continue
                p.color = color
                val x = cx - ox + gx * cellW
                val y = ownY - oy + gy * cellH
                canvas.drawRect(x, y, x + cellW + 0.5f, y + cellH + 0.5f, p)
            }
        }
        canvas.restore()
    }

    private fun wxrColor(v: Float): Int = when {
        v <  1f -> Color.TRANSPARENT
        v < 10f -> Color.argb(150,  0,160,  0)
        v < 30f -> Color.argb(200,  0,200,  0)
        v < 50f -> Color.argb(210,200,200,  0)
        v < 80f -> Color.argb(230,200,  0,  0)
        else    -> Color.argb(255,200,  0,200)
    }

    private fun drawSweep(canvas: Canvas, cx: Float, ownY: Float, radius: Float, rotDeg: Float, sweepAngle: Float) {
        val shader = SweepGradient(cx, ownY,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(70,0,200,60)),
            floatArrayOf(0f, 0.85f, 1f))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.shader = shader }
        canvas.save()
        canvas.translate(cx, ownY); canvas.rotate(rotDeg + sweepAngle - 90f); canvas.translate(-cx, -ownY)
        canvas.drawCircle(cx, ownY, radius, p)
        canvas.restore()
    }

    private fun drawArcMask(canvas: Canvas, cx: Float, ownY: Float, radius: Float, w: Float, h: Float) {
        val p = Paint().apply { color = Color.BLACK }
        val path = Path()
        path.addRect(0f, 0f, w, h, Path.Direction.CW)
        path.moveTo(cx, ownY)
        path.arcTo(RectF(cx-radius-1,ownY-radius-1,cx+radius+1,ownY+radius+1), -150f+120f, 360f-120f)
        path.close()
        path.fillType = Path.FillType.EVEN_ODD
        canvas.drawPath(path, p)
    }

    private fun drawRings(canvas: Canvas, cx: Float, ownY: Float, radius: Float, cfg: NdSettings, side: Float) {
        val rings = if (cfg.mode == NdMode.ARC) 2 else 4
        val isArc = cfg.mode == NdMode.ARC
        labelPaint.textSize = (side * 0.025f).coerceAtLeast(9f)
        for (i in 1..rings) {
            val r  = radius * i / rings
            val nm = cfg.rangeNm * i / rings
            if (isArc) {
                canvas.drawArc(RectF(cx-r,ownY-r,cx+r,ownY+r), -150f, 120f, false, ringPaint)
            } else {
                canvas.drawCircle(cx, ownY, r, ringPaint)
            }
            val la = if (isArc) Math.toRadians(-150.0 + 15.0) else Math.toRadians(-90.0 + 7.0)
            canvas.drawText("$nm", cx + cos(la).toFloat()*r + 5f, ownY + sin(la).toFloat()*r - 3f, labelPaint)
        }
    }

    private fun drawCompass(canvas: Canvas, cx: Float, ownY: Float, radius: Float,
                            rotDeg: Float, cfg: NdSettings, own: OwnshipState, side: Float) {
        val isArc = cfg.mode == NdMode.ARC
        val fs = (side * 0.028f).coerceAtLeast(9f)
        compassTxt.textSize = fs
        val rotRad = Math.toRadians(rotDeg.toDouble())
        for (a in 0 until 360 step 10) {
            val isMaj = a % 30 == 0
            if (isArc) {
                var rel = (a - own.headingDeg).toDouble()
                while (rel >  180) rel -= 360; while (rel < -180) rel += 360
                if (abs(rel) > 68) continue
            }
            val ang = Math.toRadians(a.toDouble()) + rotRad - Math.PI / 2
            val c = cos(ang).toFloat(); val s = sin(ang).toFloat()
            val len = if (isMaj) 14f else 7f
            canvas.drawLine(cx+c*(radius-len),ownY+s*(radius-len),cx+c*radius,ownY+s*radius, if(isMaj) compassMaj else compassMin)
            if (isMaj) {
                val label = when(a){0->"N";90->"E";180->"S";270->"W"; else->(a/10).toString().padStart(2,'0')}
                val lr = radius - 24f
                canvas.save()
                canvas.translate(cx+c*lr, ownY+s*lr)
                canvas.rotate(Math.toDegrees(ang).toFloat()+90f)
                compassTxt.isFakeBoldText = listOf("N","E","S","W").contains(label)
                canvas.drawText(label, 0f, fs*0.4f, compassTxt)
                canvas.restore()
            }
        }
    }

    private fun drawOwnship(canvas: Canvas, cx: Float, ownY: Float, radius: Float,
                            cfg: NdSettings, own: OwnshipState, side: Float) {
        val s    = (side * 0.036f).coerceAtLeast(12f)
        val isArc = cfg.mode == NdMode.ARC
        val hdgLen = if (isArc) radius * 0.94f else radius * 0.90f

        // ヘディングライン
        canvas.drawLine(cx, ownY, cx, ownY - hdgLen, hdgLinePaint)
        val tri = Path().apply {
            moveTo(cx, ownY-hdgLen); lineTo(cx-5f, ownY-hdgLen+10f); lineTo(cx+5f, ownY-hdgLen+10f); close()
        }
        canvas.drawPath(tri, Paint(Paint.ANTI_ALIAS_FLAG).apply{ color=Color.YELLOW; style=Paint.Style.FILL })

        // グラウンドトラック
        val trkAng = if (cfg.orient == NdOrient.HEADING_UP) -Math.PI.toFloat()/2f
                     else (own.headingDeg * Math.PI.toFloat()/180f) - Math.PI.toFloat()/2f
        val vec = (radius*0.28f).coerceAtMost(own.speedKmh*0.3f + 10f)
        canvas.drawLine(cx, ownY, cx+cos(trkAng)*(vec+s), ownY+sin(trkAng)*(vec+s), trackPaint)

        // 機体
        ownPaint.strokeWidth = (side*0.003f).coerceAtLeast(1.5f)
        canvas.save(); canvas.translate(cx, ownY)
        canvas.drawLine(0f,-s,0f,s*0.7f,ownPaint)
        canvas.drawLine(-s*1.1f,s*0.15f,0f,-s*0.1f,ownPaint)
        canvas.drawLine( s*1.1f,s*0.15f,0f,-s*0.1f,ownPaint)
        canvas.drawLine(-s*0.45f,s*0.55f,0f,s*0.3f,ownPaint)
        canvas.drawLine( s*0.45f,s*0.55f,0f,s*0.3f,ownPaint)
        canvas.drawCircle(0f,0f,3.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;style=Paint.Style.FILL})
        canvas.restore()
    }
}
