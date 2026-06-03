package com.wxr.radar.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.wxr.radar.data.NdMode
import com.wxr.radar.data.NdOrient
import com.wxr.radar.databinding.ActivityMainBinding
import com.wxr.radar.map.MapController

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()
    private lateinit var mapController: MapController

    // GPS権限リクエスト
    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            vm.triggerFetch()
        } else {
            Toast.makeText(this, "位置情報の権限が必要です", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ステータスバー非表示 (没入モード)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMap(savedInstanceState)
        setupControls()
        observeViewModel()
        checkLocationPermission()
    }

    // ──────────────────────────────────────────
    //  地図 (道路基図 + 雨雲) のセットアップ
    // ──────────────────────────────────────────
    private fun setupMap(savedInstanceState: Bundle?) {
        binding.mapView.onCreate(savedInstanceState)
        mapController = MapController(this)
        mapController.attach(binding.mapView) {
            // 基図(PMTiles)未同梱なら計器上に注意表示
            binding.radarView.basemapMissing = !mapController.basemapAvailable
        }
        // 計器の平滑化ヘディングに地図カメラを毎フレーム同期
        binding.radarView.onFrame = { displayHeading ->
            val own = vm.ownship.value
            val cfg = vm.settings.value
            if (own != null && cfg != null) {
                mapController.applyCamera(own, cfg, displayHeading)
            }
        }
    }

    // ──────────────────────────────────────────
    //  コントロールのセットアップ
    // ──────────────────────────────────────────
    private fun setupControls() {
        with(binding) {
            // MODE
            btnArc.setOnClickListener  { vm.setMode(NdMode.ARC);  updateModeButtons(NdMode.ARC) }
            btnRose.setOnClickListener { vm.setMode(NdMode.ROSE); updateModeButtons(NdMode.ROSE) }

            // ORIENT
            btnHup.setOnClickListener { vm.setOrient(NdOrient.HEADING_UP); updateOrientButtons(NdOrient.HEADING_UP) }
            btnNup.setOnClickListener { vm.setOrient(NdOrient.NORTH_UP);   updateOrientButtons(NdOrient.NORTH_UP) }

            // RANGE ▲▼
            btnRangeUp.setOnClickListener {
                val steps = listOf(10, 20, 40, 80, 160, 320)
                val cur   = vm.settings.value?.rangeNm ?: 80
                val next  = steps.firstOrNull { it > cur } ?: steps.last()
                vm.setRange(next)
            }
            btnRangeDown.setOnClickListener {
                val steps = listOf(10, 20, 40, 80, 160, 320)
                val cur   = vm.settings.value?.rangeNm ?: 80
                val prev  = steps.lastOrNull { it < cur } ?: steps.first()
                vm.setRange(prev)
            }

            // レイヤートグル
            btnWxr.setOnClickListener  { vm.toggleWxr();  updateLayerButton(btnWxr,  vm.settings.value?.wxrOn  == true) }
            btnTerr.setOnClickListener { vm.toggleTerr(); updateLayerButton(btnTerr, vm.settings.value?.terrOn == true) }
            btnArpt.setOnClickListener { vm.toggleArpt(); updateLayerButton(btnArpt, vm.settings.value?.arptOn == true) }
            btnUnit.setOnClickListener { vm.toggleUnit() }

            // 縦画面ボタン (bottomPanel内の重複ボタン)
            btnArcPortrait.setOnClickListener  { vm.setMode(NdMode.ARC);  updateModeButtons(NdMode.ARC) }
            btnRosePortrait.setOnClickListener { vm.setMode(NdMode.ROSE); updateModeButtons(NdMode.ROSE) }
            btnHupPortrait.setOnClickListener  { vm.setOrient(NdOrient.HEADING_UP); updateOrientButtons(NdOrient.HEADING_UP) }
            btnNupPortrait.setOnClickListener  { vm.setOrient(NdOrient.NORTH_UP);   updateOrientButtons(NdOrient.NORTH_UP) }
            btnRangeDownP.setOnClickListener   {
                val steps = listOf(10, 20, 40, 80, 160, 320)
                val cur   = vm.settings.value?.rangeNm ?: 80
                val prev  = steps.lastOrNull { it < cur } ?: steps.first()
                vm.setRange(prev)
            }
            btnRangeUpP.setOnClickListener     {
                val steps = listOf(10, 20, 40, 80, 160, 320)
                val cur   = vm.settings.value?.rangeNm ?: 80
                val next  = steps.firstOrNull { it > cur } ?: steps.last()
                vm.setRange(next)
            }

            // 初期状態
            updateModeButtons(NdMode.ARC)
            updateOrientButtons(NdOrient.HEADING_UP)
            updateLayerButton(btnWxr, true)
        }
    }

    // ──────────────────────────────────────────
    //  ViewModel 購読
    // ──────────────────────────────────────────
    private fun observeViewModel() {
        // ownship/設定が更新されたら計器オーバーレイを再描画
        fun refresh() {
            val ownship  = vm.ownship.value   ?: return
            val settings = vm.settings.value  ?: return
            binding.radarView.update(ownship, settings)
        }

        vm.ownship.observe(this)   { own ->
            refresh()
            // ステータス表示更新
            binding.tvHdg.text  = "${own.headingDeg.toInt().toString().padStart(3,'0')}°"
            binding.tvGs.text   = "${own.speedKmh.toInt()} km/h"
            binding.tvLat.text  = latStr(own.lat)
            binding.tvLon.text  = lonStr(own.lon)
            // ヘディングソースインジケーター
            binding.tvHdgSrc.text = when (own.headingSource) {
                com.wxr.radar.data.HeadingSource.GPS       -> "GPS"
                com.wxr.radar.data.HeadingSource.SENSOR    -> "CMP"
                com.wxr.radar.data.HeadingSource.SIMULATED -> "SIM"
            }
        }
        vm.settings.observe(this) { s ->
            refresh()
            binding.tvRange.text = s.rangeLabel()
            binding.btnUnit.text = s.distanceUnit.label
            // WXR トグルを雨雲レイヤーの表示/非表示に連動
            mapController.setRainVisible(s.wxrOn)
        }
        vm.fetchState.observe(this) { state ->
            when (state) {
                is FetchState.Idle -> {
                    binding.tvSrc.text = "IDLE"
                    binding.statusBanner.text = "● 受信待機"
                    binding.statusBanner.setTextColor(0xFF888888.toInt())
                }
                is FetchState.Fetching -> {
                    binding.tvSrc.text = "FETCH..."
                    binding.statusBanner.text = "◌ 受信中..."
                    binding.statusBanner.setTextColor(0xFFFFAA00.toInt())
                }
                is FetchState.Live -> {
                    binding.tvSrc.text = "JMA LIVE"
                    // 最新時刻で雨雲ラスターを差し替え
                    mapController.updateRain(state.basetime, state.validtime)
                    // 観測時刻 yyyyMMddHHmmss → HH:mm
                    val t = state.basetime
                    val hhmm = if (t.length >= 12) "${t.substring(8,10)}:${t.substring(10,12)}" else "--:--"
                    val precip = if (state.hasPrecip) "降水あり" else "降水なし"
                    if (state.hasMissingTiles) {
                        // 一部タイル欠落 = 圏外境界など
                        binding.statusBanner.text =
                            "△ $hhmm 受信(欠測 ${state.tilesRequested - state.tilesReceived}/${state.tilesRequested})  $precip"
                        binding.statusBanner.setTextColor(0xFFFFCC00.toInt())
                    } else {
                        binding.statusBanner.text = "● $hhmm 受信OK  $precip"
                        binding.statusBanner.setTextColor(0xFF00E000.toInt())
                    }
                }
                is FetchState.Error -> {
                    binding.tvSrc.text = "ERROR"
                    binding.statusBanner.text = "✕ 受信失敗: ${state.message}"
                    binding.statusBanner.setTextColor(0xFFFF4040.toInt())
                }
            }
        }
        vm.alertSevere.observe(this) { severe ->
            binding.alertBanner.visibility = if (severe) View.VISIBLE else View.GONE
        }
    }

    // ──────────────────────────────────────────
    //  MapView ライフサイクル委譲
    // ──────────────────────────────────────────
    override fun onStart()  { super.onStart();  binding.mapView.onStart()  }
    override fun onResume() { super.onResume(); binding.mapView.onResume() }
    override fun onPause()  { binding.mapView.onPause();  super.onPause()  }
    override fun onStop()   { binding.mapView.onStop();   super.onStop()   }
    override fun onDestroy() { binding.mapView.onDestroy(); super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); binding.mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.mapView.onSaveInstanceState(outState)
    }

    // ──────────────────────────────────────────
    //  ボタン状態
    // ──────────────────────────────────────────
    private fun updateModeButtons(mode: NdMode) = with(binding) {
        btnArc.isSelected  = mode == NdMode.ARC
        btnRose.isSelected = mode == NdMode.ROSE
    }
    private fun updateOrientButtons(orient: NdOrient) = with(binding) {
        btnHup.isSelected = orient == NdOrient.HEADING_UP
        btnNup.isSelected = orient == NdOrient.NORTH_UP
    }
    private fun updateLayerButton(btn: android.widget.Button, on: Boolean) {
        btn.isSelected = on
    }

    // ──────────────────────────────────────────
    //  権限
    // ──────────────────────────────────────────
    private fun checkLocationPermission() {
        val fine   = Manifest.permission.ACCESS_FINE_LOCATION
        val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
        if (ContextCompat.checkSelfPermission(this, fine) == PackageManager.PERMISSION_GRANTED) {
            vm.triggerFetch()
        } else {
            locationPermLauncher.launch(arrayOf(fine, coarse))
        }
    }

    // ──────────────────────────────────────────
    //  表示フォーマット
    // ──────────────────────────────────────────
    private fun latStr(d: Double): String {
        val deg = d.toInt().absoluteValue
        val min = ((Math.abs(d) - Math.abs(d).toInt()) * 60).toInt()
        return "${deg}°${min.toString().padStart(2,'0')}${if (d >= 0) "N" else "S"}"
    }
    private fun lonStr(d: Double): String {
        val deg = Math.abs(d).toInt()
        val min = ((Math.abs(d) - deg) * 60).toInt()
        return "${deg}°${min.toString().padStart(2,'0')}${if (d >= 0) "E" else "W"}"
    }
    private val Int.absoluteValue get() = if (this < 0) -this else this
}
