package com.wxr.radar.map

import android.content.Context
import com.wxr.radar.data.NdMode
import com.wxr.radar.data.NdOrient
import com.wxr.radar.data.NdSettings
import com.wxr.radar.data.OwnshipState
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * 3層構成のうち下2層（道路基図 + JMA雨雲ラスター）と
 * ND計器に同期するカメラ（bearing / zoom / padding）を管理する。
 *
 * - 基図: assets/japan.pmtiles を pmtiles:// で参照するローカルベクタータイル。
 *         PMTiles が無い場合は背景色のみのフォールバックスタイルで起動する
 *         （受け入れ基準: オフラインで基図表示。雨雲・計器は基図無しでも動作）。
 * - 雨雲: 気象庁ナウキャストの XYZ ラスタータイル。URL に basetime/validtime を
 *         含むため、更新の度にソース/レイヤーを作り直して差し替える。
 * - カメラ: 計器(NdOverlayView)と同一のジオメトリ計算(NdGeometry)を用い、
 *         自機画面位置(padding)・レンジ(zoom)・ヘディングアップ(bearing)を同期。
 */
class MapController(private val context: Context) {

    companion object {
        private const val STYLE_ASSET   = "wxr_basemap_dark_style.json"
        private const val PMTILES_ASSET = "japan.pmtiles"

        private const val RAIN_SOURCE_ID = "jma-rain-src"
        private const val RAIN_LAYER_ID  = "jma-rain-layer"
        private const val RAIN_OPACITY   = 0.7f

        /** JMA ナウキャストタイルの提供ズーム範囲（実装時確認: hrpns は z=2..10） */
        private const val RAIN_MIN_ZOOM = 2f
        private const val RAIN_MAX_ZOOM = 10f

        private const val JMA_TILE_BASE =
            "https://www.jma.go.jp/bosai/jmatile/data/nowc"

        /** 赤道周長 [m]（Web Mercator スケール計算用） */
        private const val EARTH_CIRCUMFERENCE_M = 40075016.686
        /** MapLibre のズーム基準タイルサイズ */
        private const val BASE_TILE_PX = 512.0

        /** 基図が無い場合のフォールバックスタイル（背景のみ） */
        private const val FALLBACK_STYLE_JSON =
            """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#05070a"}}]}"""
    }

    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null

    /** 基図(PMTiles)が読み込めたか。UI 側の表示判断用 */
    var basemapAvailable = false
        private set

    private var pendingRain: Pair<String, String>? = null
    private var rainVisible = true

    // moveCamera 抑制用の前回適用値
    private var lastBearing = Double.NaN
    private var lastZoom    = Double.NaN
    private var lastLat     = Double.NaN
    private var lastLon     = Double.NaN
    private var lastPadTop  = Double.NaN

    // ──────────────────────────────────────────
    //  初期化
    // ──────────────────────────────────────────

    fun attach(mapView: MapView, onReady: (() -> Unit)? = null) {
        this.mapView = mapView
        mapView.getMapAsync { m ->
            map = m
            // 計器として使うため操作・標準UIを全て無効化（カメラはコードからのみ制御）
            m.uiSettings.apply {
                setAllGesturesEnabled(false)
                isAttributionEnabled = false   // 帰属表示は ND オーバーレイ側で常時描画
                isLogoEnabled        = false
                isCompassEnabled     = false
            }

            val builder = if (hasAsset(PMTILES_ASSET)) {
                basemapAvailable = true
                Style.Builder().fromUri("asset://$STYLE_ASSET")
            } else {
                // PMTiles 未同梱でも起動可能にする（開発時・初期ビルド用）
                basemapAvailable = false
                Style.Builder().fromJson(FALLBACK_STYLE_JSON)
            }

            m.setStyle(builder) { s ->
                style = s
                // スタイル確定前に届いていた雨雲更新を適用
                pendingRain?.let { (b, v) -> updateRain(b, v) }
                pendingRain = null
                onReady?.invoke()
            }
        }
    }

    private fun hasAsset(name: String): Boolean = try {
        context.assets.open(name).use { }
        true
    } catch (_: Exception) {
        false
    }

    // ──────────────────────────────────────────
    //  JMA 雨雲レイヤー
    // ──────────────────────────────────────────

    /**
     * 最新の basetime/validtime で雨雲ラスターを差し替える。
     * URL 自体が時刻を含むため、古いタイルが画面に残ることはない。
     */
    fun updateRain(basetime: String, validtime: String) {
        val s = style ?: run { pendingRain = basetime to validtime; return }

        // 既存のレイヤー/ソースを除去してから追加（差し替え方式）
        s.getLayer(RAIN_LAYER_ID)?.let { s.removeLayer(it) }
        s.getSource(RAIN_SOURCE_ID)?.let { s.removeSource(it) }

        val url =
            "$JMA_TILE_BASE/$basetime/none/$validtime/surf/hrpns/{z}/{x}/{y}.png"
        val tileSet = TileSet("2.1.0", url).apply {
            setMinZoom(RAIN_MIN_ZOOM)
            setMaxZoom(RAIN_MAX_ZOOM)
        }
        s.addSource(RasterSource(RAIN_SOURCE_ID, tileSet, 256))
        val layer = RasterLayer(RAIN_LAYER_ID, RAIN_SOURCE_ID).withProperties(
            PropertyFactory.rasterOpacity(RAIN_OPACITY),
            PropertyFactory.rasterFadeDuration(0f),
            PropertyFactory.visibility(
                if (rainVisible) org.maplibre.android.style.layers.Property.VISIBLE
                else org.maplibre.android.style.layers.Property.NONE
            )
        )
        // addLayer は最上位に積む = 基図の上・計器オーバーレイ(View)の下
        s.addLayer(layer)
    }

    /** WXR トグル連動（レイヤーの表示/非表示のみ切替） */
    fun setRainVisible(visible: Boolean) {
        rainVisible = visible
        val s = style ?: return
        s.getLayer(RAIN_LAYER_ID)?.setProperties(
            PropertyFactory.visibility(
                if (visible) org.maplibre.android.style.layers.Property.VISIBLE
                else org.maplibre.android.style.layers.Property.NONE
            )
        )
    }

    // ──────────────────────────────────────────
    //  カメラ同期
    // ──────────────────────────────────────────

    /**
     * 計器表示と地図カメラを同期する。30fps の描画ループから呼ばれる前提で、
     * 前回適用値と実質同じ場合は何もしない。
     *
     * @param displayHeading 平滑化済みヘディング（NdOverlayView と同じ値）
     */
    fun applyCamera(own: OwnshipState, settings: NdSettings, displayHeading: Float) {
        val m  = map ?: return
        val mv = mapView ?: return
        val w  = mv.width.toDouble()
        val h  = mv.height.toDouble()
        if (w <= 0 || h <= 0) return

        val geo = NdGeometry.compute(w, h, settings.mode)

        // レンジ[km] が自機→リング外周(radiusPx) に一致する zoom を求める
        //   metersPerPixel(z) = C * cos(lat) / (512 * 2^z)
        //   rangeM / radiusPx = metersPerPixel  →  z = log2(C*cos(lat)*radiusPx / (512*rangeM))
        val rangeM  = settings.rangeKm * 1000.0
        val cosLat  = cos(Math.toRadians(own.lat)).coerceAtLeast(0.01)
        val zoomRaw = log2(EARTH_CIRCUMFERENCE_M * cosLat * geo.radiusPx / (BASE_TILE_PX * rangeM))
        val zoom    = zoomRaw.coerceIn(0.0, 18.0)

        val bearing = if (settings.orient == NdOrient.HEADING_UP)
            displayHeading.toDouble() else 0.0

        // 自機を画面上の (cx, ownY) に置くための padding
        //   パディング後の中心 y = (top + (h - bottom)) / 2 = ownY
        val padTop    = max(0.0, 2.0 * geo.ownYPx - h)
        val padBottom = max(0.0, h - 2.0 * geo.ownYPx)

        // 実質変化が無ければ skip（毎フレーム moveCamera を避ける）
        if (!lastBearing.isNaN()
            && abs(angleDiff(bearing, lastBearing)) < 0.1
            && abs(zoom - lastZoom) < 0.01
            && abs(own.lat - lastLat) < 1e-6
            && abs(own.lon - lastLon) < 1e-6
            && abs(padTop - lastPadTop) < 0.5
        ) return

        lastBearing = bearing; lastZoom = zoom
        lastLat = own.lat; lastLon = own.lon; lastPadTop = padTop

        val pos = CameraPosition.Builder()
            .target(LatLng(own.lat, own.lon))
            .zoom(zoom)
            .bearing(bearing)
            .padding(0.0, padTop, 0.0, padBottom)
            .build()
        m.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
    }

    private fun log2(v: Double): Double = ln(v) / ln(2.0)

    private fun angleDiff(a: Double, b: Double): Double {
        var d = a - b
        while (d > 180) d -= 360
        while (d < -180) d += 360
        return d
    }
}

/**
 * ND 計器のジオメトリ（自機位置・リング半径）。
 * NdOverlayView の描画と MapController のカメラ計算で共有する。
 */
object NdGeometry {

    data class Geometry(
        val cxPx: Double,
        val ownYPx: Double,
        val radiusPx: Double
    )

    fun compute(w: Double, h: Double, mode: NdMode): Geometry {
        val side  = min(w, h)
        val isArc = mode == NdMode.ARC
        val ownY  = if (isArc) h * 0.78 else h / 2.0
        val radius = if (isArc) side * 0.72 else side * 0.46
        return Geometry(cxPx = w / 2.0, ownYPx = ownY, radiusPx = radius)
    }
}
