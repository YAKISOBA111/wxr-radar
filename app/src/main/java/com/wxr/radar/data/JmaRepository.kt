package com.wxr.radar.data

import android.graphics.BitmapFactory
import android.graphics.Color
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.*

/**
 * 気象庁ナウキャスト データ取得
 *
 * 取得フロー:
 * 1. nowcast_basetime.json → 最新観測時刻を取得
 * 2. 自機周辺タイル (zoom=8, PNG) を並列取得
 * 3. ピクセルカラー → 降水強度 (mm/h) に変換
 * 4. GRID×GRID の FloatArray にリサンプリング
 */
class JmaRepository(
    private val client: OkHttpClient = OkHttpClient()
) {
    companion object {
        private const val BASE_URL   = "https://www.jma.go.jp/bosai/nowc/data"
        private const val TILE_ZOOM  = 8
        private const val GRID_SIZE  = 300

        /** 気象庁カラーパレット → 降水強度 (mm/h) マッピング */
        private val COLOR_TABLE = listOf(
            Triple(Color.rgb(160,210,255),  0f,  1f),
            Triple(Color.rgb( 33,140,255),  1f,  5f),
            Triple(Color.rgb(  0, 65,255),  5f, 10f),
            Triple(Color.rgb( 80,255, 80), 10f, 20f),
            Triple(Color.rgb( 30,180, 30), 20f, 30f),
            Triple(Color.rgb(255,255, 80), 30f, 50f),
            Triple(Color.rgb(255,153,  0), 50f, 80f),
            Triple(Color.rgb(255, 40,  0), 80f,100f),
            Triple(Color.rgb(180,  0,104),100f,999f),
        )
    }

    // ──────────────────────────────────────────
    //  公開API
    // ──────────────────────────────────────────

    /** 自機位置周辺のレーダーデータを取得。失敗時は null を返す */
    suspend fun fetchRadar(
        lat: Double, lon: Double, rangeKm: Double
    ): RadarData? = withContext(Dispatchers.IO) {
        try {
            val basetime = fetchBasetime() ?: return@withContext null
            val tiles    = fetchTiles(lat, lon, rangeKm, basetime)
            compositeToGrid(tiles, lat, lon)
        } catch (e: Exception) {
            null
        }
    }

    // ──────────────────────────────────────────
    //  内部実装
    // ──────────────────────────────────────────

    private fun fetchBasetime(): String? {
        val req  = Request.Builder()
            .url("$BASE_URL/nowcast_basetime.json")
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val json = JsonParser.parseString(resp.body!!.string()).asJsonObject
            json.getAsJsonObject("radar")?.get("basetime")?.asString
                ?: json.get("basetime")?.asString
        }
    }

    /** 必要なタイル範囲を計算して並列取得 */
    private suspend fun fetchTiles(
        lat: Double, lon: Double, rangeKm: Double, basetime: String
    ): TileComposite = coroutineScope {
        val tileKm   = 40075.0 / (1 shl TILE_ZOOM) * cos(Math.toRadians(lat))
        val half     = ceil(rangeKm / tileKm).toInt() + 1
        val center   = latLonToTile(lat, lon, TILE_ZOOM)
        val x0 = center.first  - half; val x1 = center.first  + half
        val y0 = center.second - half; val y1 = center.second + half

        // タイルを並列取得
        val jobs = mutableListOf<Deferred<Pair<String, ByteArray?>>>()
        for (ty in y0..y1) for (tx in x0..x1) {
            jobs += async {
                val key  = "${tx}_${ty}"
                val url  = "$BASE_URL/radar/$basetime/$TILE_ZOOM/$tx/$ty.png"
                val data = try {
                    val req  = Request.Builder().url(url).build()
                    client.newCall(req).execute().use { r ->
                        if (r.isSuccessful) r.body?.bytes() else null
                    }
                } catch (e: Exception) { null }
                key to data
            }
        }
        val map = jobs.awaitAll().associate { it.first to it.second }

        TileComposite(
            tileMap    = map,
            txMin = x0, txMax = x1,
            tyMin = y0, tyMax = y1,
            zoom  = TILE_ZOOM
        )
    }

    /** タイル群を GRID×GRID FloatArray にリサンプル */
    private fun compositeToGrid(tiles: TileComposite, lat: Double, lon: Double): RadarData {
        val tW = (tiles.txMax - tiles.txMin + 1) * 256
        val tH = (tiles.tyMax - tiles.tyMin + 1) * 256

        // 全タイルをピクセル配列に展開
        val pixels = IntArray(tW * tH) // ARGB
        for (ty in tiles.tyMin..tiles.tyMax) {
            for (tx in tiles.txMin..tiles.txMax) {
                val raw = tiles.tileMap["${tx}_${ty}"] ?: continue
                val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: continue
                val ox  = (tx - tiles.txMin) * 256
                val oy  = (ty - tiles.tyMin) * 256
                for (py in 0 until bmp.height) {
                    for (px in 0 until bmp.width) {
                        pixels[(oy + py) * tW + (ox + px)] = bmp.getPixel(px, py)
                    }
                }
                bmp.recycle()
            }
        }

        // GRID×GRID にダウンサンプル & 色変換
        val grid  = FloatArray(GRID_SIZE * GRID_SIZE)
        for (gy in 0 until GRID_SIZE) {
            for (gx in 0 until GRID_SIZE) {
                val bx  = (gx.toFloat() / GRID_SIZE * tW).toInt().coerceIn(0, tW - 1)
                val by  = (gy.toFloat() / GRID_SIZE * tH).toInt().coerceIn(0, tH - 1)
                val pix = pixels[by * tW + bx]
                val a   = Color.alpha(pix)
                grid[gy * GRID_SIZE + gx] = if (a < 30) 0f
                else colorToMmh(Color.red(pix), Color.green(pix), Color.blue(pix))
            }
        }

        // 地理的範囲
        val tl = tileToLatLon(tiles.txMin,     tiles.tyMin,     tiles.zoom)
        val br = tileToLatLon(tiles.txMax + 1, tiles.tyMax + 1, tiles.zoom)
        val bounds = RadarBounds(
            latMin = br.first,  latMax = tl.first,
            lonMin = tl.second, lonMax = br.second
        )
        return RadarData(grid = grid, gridSize = GRID_SIZE, bounds = bounds)
    }

    // ──────────────────────────────────────────
    //  地理計算ユーティリティ
    // ──────────────────────────────────────────

    private fun latLonToTile(lat: Double, lon: Double, zoom: Int): Pair<Int, Int> {
        val n  = 1 shl zoom
        val tx = floor((lon + 180.0) / 360.0 * n).toInt()
        val ty = floor(
            (1.0 - ln(tan(Math.toRadians(lat)) + 1.0 / cos(Math.toRadians(lat))) / PI) / 2.0 * n
        ).toInt()
        return tx to ty
    }

    private fun tileToLatLon(tx: Int, ty: Int, zoom: Int): Pair<Double, Double> {
        val n   = 1 shl zoom
        val lon = tx.toDouble() / n * 360.0 - 180.0
        val lat = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * ty.toDouble() / n))))
        return lat to lon
    }

    /** ピクセル色 → 降水強度 (mm/h) 最近傍マッチ */
    private fun colorToMmh(r: Int, g: Int, b: Int): Float {
        var bestDist = Int.MAX_VALUE
        var bestMid  = 0f
        for ((color, mn, mx) in COLOR_TABLE) {
            val dr = r - Color.red(color)
            val dg = g - Color.green(color)
            val db = b - Color.blue(color)
            val d  = dr * dr + dg * dg + db * db
            if (d < bestDist) { bestDist = d; bestMid = (mn + mx) / 2f }
        }
        return if (bestDist > 8000) 0f else bestMid
    }

    private data class TileComposite(
        val tileMap: Map<String, ByteArray?>,
        val txMin: Int, val txMax: Int,
        val tyMin: Int, val tyMax: Int,
        val zoom: Int
    )
}
