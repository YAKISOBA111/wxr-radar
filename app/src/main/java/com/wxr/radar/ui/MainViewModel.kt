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
import kotlin.math.abs

class MainViewModel(app: Application) : AndroidViewModel(app) {

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

    private val _alertSevere = MutableLiveData(false)
    val alertSevere: LiveData<Boolean> = _alertSevere

    // ──────────────────────────────────────────
    //  内部状態
    // ──────────────────────────────────────────
    private val repo           = JmaRepository()
    private var fetchJob: Job? = null
    private var autoFetchJob: Job? = null

    // GPS
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(app)
    private var lastGpsLocation: Location? = null
    private var lastGpsTime    = 0L
    private var gpsCallback: LocationCallback? = null
    private var sensorListener: SensorEventListener? = null

    // コンパスセンサー
    private val sensorManager = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var sensorHeading  = 0f
    private val rotationMatrix = FloatArray(9)
    private val orientValues   = FloatArray(3)
    private val accelValues    = FloatArray(3)
    private val magnetValues   = FloatArray(3)

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
    fun setMode(mode: NdMode) {
        _settings.value = _settings.value?.copy(mode = mode)
    }

    fun setOrient(orient: NdOrient) {
        _settings.value = _settings.value?.copy(orient = orient)
    }

    fun setRange(nm: Int) {
        _settings.value = _settings.value?.copy(rangeNm = nm)
        triggerFetch()  // レンジ変更でタイル範囲が変わるので再取得
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
    //  レーダーデータ取得
    // ──────────────────────────────────────────

    /** 即時取得をキックする */
    fun triggerFetch() {
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            _fetchState.value = FetchState.Fetching
            val state   = _ownship.value ?: OwnshipState()
            val rangeKm = _settings.value?.rangeKm ?: 148.0
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
                    _alertSevere.postValue(false)
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

    private fun checkSevereAlert(data: RadarData) {
        // 前方エリア (グリッドの上半分中央) に 80mm/h 超があれば警告
        var severe = false
        val g = data.gridSize
        outer@ for (gy in 0 until g / 2) {
            for (gx in g / 3 until 2 * g / 3) {
                if (data.getAt(gx, gy) > 80f) { severe = true; break@outer }
            }
        }
        _alertSevere.postValue(severe)
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

                val cur     = _ownship.value ?: OwnshipState()
                val speedKmh = (loc.speed * 3.6f).coerceAtLeast(0f)

                // GPS速度 > 3km/h かつ bearing が有効なら GPS ヘディング優先
                val (heading, source) = if (speedKmh > 3f && loc.hasBearing()) {
                    loc.bearing to HeadingSource.GPS
                } else {
                    // 停車中はコンパスセンサーを使用 (スマホ向きではなく磁北)
                    sensorHeading to HeadingSource.SENSOR
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
            }
        }
        gpsCallback = callback
        fusedClient.requestLocationUpdates(req, callback, android.os.Looper.getMainLooper())
        Unit
    } catch (e: SecurityException) {
        // 競合等で権限が剥奪されていた場合の保険（クラッシュさせない）
        gpsStarted = false
    }

    // ──────────────────────────────────────────
    //  コンパスセンサー (停車中のヘッドアップ用)
    // ──────────────────────────────────────────
    private fun startSensor() {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER ->
                        System.arraycopy(event.values, 0, accelValues, 0, 3)
                    Sensor.TYPE_MAGNETIC_FIELD ->
                        System.arraycopy(event.values, 0, magnetValues, 0, 3)
                }
                if (SensorManager.getRotationMatrix(rotationMatrix, null, accelValues, magnetValues)) {
                    SensorManager.getOrientation(rotationMatrix, orientValues)
                    val azimuthDeg = Math.toDegrees(orientValues[0].toDouble()).toFloat()
                    sensorHeading  = (azimuthDeg + 360f) % 360f

                    // GPS速度が低い時だけセンサー値を反映
                    val cur = _ownship.value ?: return
                    if (cur.speedKmh <= 3f) {
                        _ownship.postValue(
                            cur.copy(
                                headingDeg    = sensorHeading,
                                headingSource = HeadingSource.SENSOR
                            )
                        )
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorListener = listener
        sensorManager.registerListener(listener,
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
            SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(listener,
            sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD),
            SensorManager.SENSOR_DELAY_UI)
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
