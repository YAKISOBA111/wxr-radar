package com.wxr.radar.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.*
import com.wxr.radar.data.*
import kotlinx.coroutines.*

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /**
         * GPS方位を採用する最低速度 [km/h]。
         * これ未満では方位を更新せず「直前の有効なGPS方位」で凍結する。
         * （停車中の磁気センサー乱れによる 180° 反転・プルプルの対策）
         */
        private const val HEADING_FREEZE_SPEED_KMH = 5f

        /** オートレンジ: 推奨レンジがこの時間 [ms] 安定したら切り替える */
        private const val AUTO_RANGE_STABLE_MS = 3000L
    }

    // ──────────────────────────────────────────
    //  公開 LiveData
    // ──────────────────────────────────────────
    private val _radarData  = MutableLiveData<RadarData?>()
    val radarData: LiveData<RadarData?> = _radarData

    private val _ownship    = MutableLiveData(OwnshipState())
    val ownship: LiveData<OwnshipState> = _ownship

    private val _settings   = MutableLiveData(NdSettings())
    val settings: LiveData<NdSettings> = _settings

    private val _fetchState = MutableLiveData<FetchState>(FetchState.Idle)
    val fetchState: LiveData<FetchState> = _fetchState

    /** 警報バナーを表示すべきか（強雨接近 かつ 未消音） */
    private val _alertActive = MutableLiveData(false)
    val alertActive: LiveData<Boolean> = _alertActive

    // ──────────────────────────────────────────
    //  内部状態
    // ──────────────────────────────────────────
    private val repo           = JmaRepository()
    private var fetchJob: Job? = null
    private var autoFetchJob: Job? = null

    // 警報: 消音されたら降水が一旦解消するまで再表示しない
    private var alertSevereNow = false
    private var alertMuted     = false

    // GPS
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(app)
    private var lastGpsLocation: Location? = null
    private var lastGpsTime    = 0L
    private var gpsCallback: LocationCallback? = null
    private var sensorListener: SensorEventListener? = null

    /**
     * 直前に GPS で得た有効な進行方向。
     * 一度確定した後は、低速・停車時もこの値で方位を凍結する。
     * null = 起動後まだ一度も GPS 方位が確定していない（この間のみセンサー方位を使用）。
     */
    private var lastGpsHeading: Float? = null

    // 方位センサー (TYPE_ROTATION_VECTOR: ジャイロ統合済みの安定値)
    private val sensorManager  = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var sensorHeading  = 0f
    private val rotationMatrix = FloatArray(9)
    private val orientValues   = FloatArray(3)

    // オートレンジの安定判定
    private var autoRangeCandidate     = -1
    private var autoRangeCandidateSince = 0L

    init {
        // 権限付与前に requestLocationUpdates を呼ぶと SecurityException で
        // クラッシュするため、権限がある場合のみ開始する。
        // 未付与時は MainActivity が権限取得後に startLocationUpdates() を呼ぶ。
        startLocationUpdates()
        startSensor()
        startAutoFetch()
    }

    // ──────────────────────────────────────────
    //  位置情報の開始制御
    // ──────────────────────────────────────────
    private var gpsStarted = false

    private fun hasLocationPermission(): Boolean {
        val app = getApplication<Application>()
        return app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
               app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }

    /** 権限がある場合のみ GPS 更新を開始（多重開始は無視）。権限取得後にも呼ぶこと */
    fun startLocationUpdates() {
        if (gpsStarted || !hasLocationPermission()) return
        gpsStarted = true
        startGps()
    }

    // ──────────────────────────────────────────
    //  設定変更
    // ──────────────────────────────────────────
    fun toggleMode() {
        _settings.value = _settings.value?.let {
            it.copy(mode = if (it.mode == NdMode.ARC) NdMode.ROSE else NdMode.ARC)
        }
    }

    fun toggleOrient() {
        _settings.value = _settings.value?.let {
            it.copy(orient = if (it.orient == NdOrient.HEADING_UP) NdOrient.NORTH_UP
                             else NdOrient.HEADING_UP)
        }
    }

    /**
     * 手動レンジ操作（dir = +1 / -1）。
     * 手動で操作したらオートレンジは自動 OFF。
     */
    fun stepRange(dir: Int) {
        val cur   = _settings.value ?: return
        val steps = NdSettings.RANGE_STEPS_KM
        val idx   = steps.indexOf(cur.rangeKm).let { if (it < 0) steps.indexOf(NdSettings.DEFAULT_RANGE_KM) else it }
        val next  = (idx + dir).coerceIn(0, steps.size - 1)
        if (steps[next] == cur.rangeKm && !cur.autoRange) return
        _settings.value = cur.copy(rangeKm = steps[next], autoRange = false)
        triggerFetch()  // レンジ変更でタイル範囲が変わるので再取得
    }

    /** オートレンジ ON/OFF。ON にした瞬間に現在車速で即適用する */
    fun toggleAutoRange() {
        val cur = _settings.value ?: return
        val on  = !cur.autoRange
        if (on) {
            val speed  = _ownship.value?.speedKmh ?: 0f
            val target = NdSettings.autoRangeForSpeed(speed)
            val changed = target != cur.rangeKm
            _settings.value = cur.copy(autoRange = true, rangeKm = target)
            autoRangeCandidate = target
            if (changed) triggerFetch()
        } else {
            _settings.value = cur.copy(autoRange = false)
        }
    }

    fun toggleWxr()  { _settings.value = _settings.value?.let { it.copy(wxrOn  = !it.wxrOn)  } }
    fun toggleTerr() { _settings.value = _settings.value?.let { it.copy(terrOn = !it.terrOn) } }
    fun toggleArpt() { _settings.value = _settings.value?.let { it.copy(arptOn = !it.arptOn) } }

    /** 距離単位 NM ↔ km を切り替える（再取得不要・表示のみ変更） */
    fun toggleUnit() {
        _settings.value = _settings.value?.let {
            val next = if (it.distanceUnit == DistanceUnit.NM) DistanceUnit.KM else DistanceUnit.NM
            it.copy(distanceUnit = next)
        }
    }

    // ──────────────────────────────────────────
    //  警報（強雨接近）
    // ──────────────────────────────────────────

    /**
     * 警報を消音する。降水が一旦解消する（severe が false になる）まで
     * 再表示しない。解消後の次の強雨では再び表示される。
     */
    fun muteAlert() {
        alertMuted = true
        _alertActive.value = false
    }

    private fun checkSevereAlert(data: RadarData) {
        // 前方エリア (グリッドの上半分中央) に 80mm/h 超があれば警告
        var severe = false
        val g = data.gridSize
        outer@ for (gy in 0 until g / 2) {
            for (gx in g / 3 until 2 * g / 3) {
                if (data.getAt(gx, gy) > 80f) { severe = true; break@outer }
            }
        }
        if (!severe) alertMuted = false   // 降水解消 → 消音状態をリセット
        alertSevereNow = severe
        _alertActive.postValue(severe && !alertMuted)
    }

    // ──────────────────────────────────────────
    //  レーダーデータ取得
    // ──────────────────────────────────────────

    /** 即時取得をキックする */
    fun triggerFetch() {
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            _fetchState.value = FetchState.Fetching
            val state   = _ownship.value ?: OwnshipState()
            val rangeKm = (_settings.value?.rangeKm ?: NdSettings.DEFAULT_RANGE_KM).toDouble()
            when (val result = repo.fetch(state.lat, state.lon, rangeKm)) {
                is FetchResult.Success -> {
                    _radarData.value  = result.data
                    _fetchState.value = FetchState.Live(
                        basetime       = result.basetime,
                        validtime      = result.validtime,
                        hasPrecip      = result.hasPrecip,
                        tilesReceived  = result.tilesReceived,
                        tilesRequested = result.tilesRequested
                    )
                    checkSevereAlert(result.data)
                }
                is FetchResult.NetworkError -> {
                    // 通信失敗時は古いデータを残しつつ状態だけエラーに
                    _fetchState.value = FetchState.Error(result.message)
                }
            }
        }
    }

    /** 5分間隔で自動取得 */
    private fun startAutoFetch() {
        autoFetchJob = viewModelScope.launch {
            while (isActive) {
                triggerFetch()
                delay(5 * 60 * 1000L)
            }
        }
    }

    // ──────────────────────────────────────────
    //  GPS
    // ──────────────────────────────────────────
    @SuppressLint("MissingPermission")
    private fun startGps() = try {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                lastGpsLocation = loc
                lastGpsTime     = SystemClock.elapsedRealtime()

                val cur      = _ownship.value ?: OwnshipState()
                val speedKmh = (loc.speed * 3.6f).coerceAtLeast(0f)

                // ── 方位の決定 ──
                // 走行中 (>=5km/h) かつ bearing 有効: GPS方位を採用・記憶
                // 低速/停車: 直前の有効な GPS 方位で凍結（センサーでは更新しない）
                // GPS方位が一度も確定していない間のみ: 暫定的にセンサー方位
                val moving = speedKmh >= HEADING_FREEZE_SPEED_KMH && loc.hasBearing()
                if (moving) lastGpsHeading = loc.bearing
                val frozen = lastGpsHeading
                val (heading, source) = when {
                    moving           -> loc.bearing to HeadingSource.GPS
                    frozen != null   -> frozen      to HeadingSource.GPS
                    else             -> sensorHeading to HeadingSource.SENSOR
                }

                _ownship.postValue(
                    cur.copy(
                        lat           = loc.latitude,
                        lon           = loc.longitude,
                        headingDeg    = heading,
                        speedKmh      = speedKmh,
                        headingSource = source
                    )
                )

                applyAutoRange(speedKmh)
            }
        }
        gpsCallback = callback
        fusedClient.requestLocationUpdates(req, callback, android.os.Looper.getMainLooper())
        Unit
    } catch (e: SecurityException) {
        // 競合等で権限が剥奪されていた場合の保険（クラッシュさせない）
        gpsStarted = false
    }

    /**
     * 車速連動オートレンジ。
     * 境界速度付近でのチャタリングを防ぐため、推奨レンジが
     * AUTO_RANGE_STABLE_MS の間安定してから切り替える。
     */
    private fun applyAutoRange(speedKmh: Float) {
        val cur = _settings.value ?: return
        if (!cur.autoRange) return

        val target = NdSettings.autoRangeForSpeed(speedKmh)
        val now    = SystemClock.elapsedRealtime()

        if (target != autoRangeCandidate) {
            autoRangeCandidate      = target
            autoRangeCandidateSince = now
            return
        }
        if (target == cur.rangeKm) return
        if (now - autoRangeCandidateSince < AUTO_RANGE_STABLE_MS) return

        _settings.value = cur.copy(rangeKm = target)
        triggerFetch()
    }

    // ──────────────────────────────────────────
    //  方位センサー (TYPE_ROTATION_VECTOR)
    //  ※ GPS方位が一度も得られていない起動直後のみ表示に使用する。
    //    生の地磁気+加速度 (getRotationMatrix) は乱れに弱く 180° 反転の
    //    原因になるため使用しない。
    // ──────────────────────────────────────────
    private fun startSensor() {
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: return  // 搭載なし: GPS方位のみで運用

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientValues)
                val azimuthDeg = Math.toDegrees(orientValues[0].toDouble()).toFloat()
                sensorHeading  = (azimuthDeg + 360f) % 360f

                // GPS方位が一度でも確定したら、以後センサーで方位は更新しない
                if (lastGpsHeading != null) return
                val cur = _ownship.value ?: return
                _ownship.postValue(
                    cur.copy(
                        headingDeg    = sensorHeading,
                        headingSource = HeadingSource.SENSOR
                    )
                )
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorListener = listener
        sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
    }

    // ──────────────────────────────────────────
    override fun onCleared() {
        super.onCleared()
        gpsCallback?.let { fusedClient.removeLocationUpdates(it) }
        sensorListener?.let { sensorManager.unregisterListener(it) }
        autoFetchJob?.cancel()
        fetchJob?.cancel()
    }
}

/**
 * 取得状態。UI側で「受信中／受信成功／通信エラー」を明確に区別する。
 */
sealed class FetchState {
    object Idle : FetchState()
    object Fetching : FetchState()
    /** 受信成功。観測時刻・雨の有無・欠測タイル数を保持 */
    data class Live(
        val basetime: String,
        /** MapLibre 雨雲タイルソースの差し替えに使用 */
        val validtime: String,
        val hasPrecip: Boolean,
        val tilesReceived: Int,
        val tilesRequested: Int
    ) : FetchState() {
        val hasMissingTiles: Boolean get() = tilesReceived < tilesRequested
    }
    /** 通信エラー */
    data class Error(val message: String) : FetchState()
}
