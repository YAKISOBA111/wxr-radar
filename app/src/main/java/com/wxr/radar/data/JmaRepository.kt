package com.wxr.radar.data

import android.graphics.BitmapFactory
import android.graphics.Color
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.*

/**
 * 気象庁 高解像度降水ナウキャスト データ取得
 *
 * 正しいエンドポイント:
 *  時刻一覧: https://www.jma.go.jp/bosai/jmatile/data/nowc/targetTimes_N1.json
 *           （配列。先頭 [0] が最新の basetime）
 *  タイル:   https://www.jma.go.jp/bosai/jmatile/data/nowc/{basetime}/none/{validtime}/surf/hrpns/{z}/{x}/{y}.png
 *
 * 取得フロー:
 *  1. targetTimes_N1.json → 最新の basetime / validtime を取得
 *  2. 自機周辺タイル (zoom=8, PNG) を並列取得
 *  3. ピクセルカラー → 降水強度 (mm/h) に変換
 *  4. GRID×GRID の FloatArray にリサンプリング
 */
class JmaRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TIMES_URL =
            "https://www.jma.go.jp/bosai/jmatile/data/nowc/targetTimes_N1.json"
        private const val TILE_BASE =
            "https://www.jma.go.jp/bosai/jmatile/data/nowc"
        private const val TILE_ZOOM = 8
        private const val GRID_SIZE = 300

        /** 気象庁標準 降水強度カラーパレット → mm/h */
        private val COLOR_TABLE = listOf(
            Triple(Color.rgb(242, 242, 255),  0f,  1f),   // ごく弱い(ほぼ無色)
            Triple(Color.rgb(160, 210, 255),  1f,  5f),   // 水色
            Triple(Color.rgb( 33, 140, 255),  5f, 10f),   // 青
            Triple(Color.rgb(  0,  65, 255), 10f, 20f),   // 濃い青
            Triple(Color.rgb(250, 245,   0), 20f, 30f),   // 黄
            Triple(Color.rgb(255, 153,   0), 30f, 50f),   // 橙
            Triple(Color.rgb(255,  40,   0), 50f, 80f),   // 赤
            Triple(Color.rgb(180,   0, 104), 80f,200f),   // 紫(猛烈)
        )
    }

    // ──────────────────────────────────────────
    //  公開API
    // ──────────────────────────────────────────

    /**
     * 自機位置周辺のレーダーデータを取得。
     * 戻り値は診断情報込みの [FetchResult]。
     */
    suspend fun fetch(
        lat: Double, lon: Double, rangeKm: Double
    ): FetchResult = withContext(Dispatchers.IO) {
        // 1) 最新時刻取得
        val times = try {
            fetchLatestTimes()
        } catch (e: Exception) {
            return@withContext FetchResult.NetworkError("時刻一覧の取得失敗: ${e.message}")
        } ?: return@withContext FetchResult.NetworkError("時刻一覧が空")

        // 2) タイル取得
        val tiles = try {
            fetchTiles(lat, lon, rangeKm, times.basetime, times.validtime)
        } catch (e: Exception) {
            return@withContext FetchResult.NetworkError("タイル取得失敗: ${e.message}")
        }

        // タイルが1枚も取れなかった = 通信問題 or 圏外
        if (tiles.received == 0) {
            return@withContext FetchResult.NetworkError(
                "タイル0枚 (要求${tiles.requested}枚)"
            )
        }

        // 3) グリッド化
        val data = compositeToGrid(tiles, lat, lon, times.basetime)

        // 4) 受信できたが降水ピクセルが全く無い → 欠測 or 雨なしを判定
        //    タイルは取れている前提なので「雨なし(無降水)」として正常扱い
        //    ただし全ピクセル透明 = データ欠測の可能性もあるため区別して通知
        val maxMmh = data.grid.maxOrNull() ?: 0f
        FetchResult.Success(
            data = data,
            basetime = times.basetime,
            validtime = times.validtime,
            tilesReceived = tiles.received,
            tilesRequested = tiles.requested,
            hasPrecip = maxMmh >= 1f
        )
    }

    // ──────────────────────────────────────────
    //  内部実装
    // ──────────────────────────────────────────

    private data class Times(val basetime: String, val validtime: String)

    private fun fetchLatestTimes(): Times? {
        val req = Request.Builder().url(TIMES_URL).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw RuntimeException("空レスポンス")
            val arr  = JsonParser.parseString(body).asJsonArray
            if (arr.size() == 0) return null
            // 先頭が最新
            val first = arr[0].asJsonObject
            return Times(
                basetime = first.get("basetime").asString,
                validtime = first.get("validtime").asString
            )
        }
    }

    private class TileComposite(
        val tileMap: Map<String, ByteArray?>,
        val txMin: Int, val txMax: Int,
        val tyMin: Int, val tyMax: Int,
        val zoom: Int,
        val requested: Int,
        val received: Int
    )

    private suspend fun fetchTiles(
        lat: Double, lon: Double, rangeKm: Double,
        basetime: String, validtime: String
    ): TileComposite = coroutineScope {
        val tileKm = 40075.0 / (1 shl TILE_ZOOM) * cos(Math.toRadians(lat))
        val half   = ceil(rangeKm / tileKm).toInt() + 1
        val center = latLonToTile(lat, lon, TILE_ZOOM)
        val x0 = center.first  - half; val x1 = center.first  + half
        val y0 = center.second - half; val y1 = center.second + half

        val jobs = mutableListOf<Deferred<Pair<String, ByteArray?>>>()
        for (ty in y0..y1) for (tx in x0..x1) {
            jobs += async {
                val key = "${tx}_${ty}"
                val url = "$TILE_BASE/$basetime/none/$validtime/surf/hrpns/$TILE_ZOOM/$tx/$ty.png"
                val data = try {
                    val req = Request.Builder().url(url).build()
                    client.newCall(req).execute().use { r ->
                        if (r.isSuccessful) r.body?.bytes() else null
                    }
                } catch (e: Exception) { null }
                key to data
            }
        }
        val map = jobs.awaitAll().associate { it.first to it.second }
        val received = map.values.count { it != null }

        TileComposite(
            tileMap = map,
            txMin = x0, txMax = x1, tyMin = y0, tyMax = y1,
            zoom = TILE_ZOOM,
            requested = map.size,
            received = received
        )
    }

    private fun compositeToGrid(
        tiles: TileComposite, lat: Double, lon: Double, basetime: String
    ): RadarData {
        val tW = (tiles.txMax - tiles.txMin + 1) * 256
        val tH = (tiles.tyMax - tiles.tyMin + 1) * 256

        val pixels = IntArray(tW * tH)
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

        val grid = FloatArray(GRID_SIZE * GRID_SIZE)
        for (gy in 0 until GRID_SIZE) {
            for (gx in 0 until GRID_SIZE) {
                val bx = (gx.toFloat() / GRID_SIZE * tW).toInt().coerceIn(0, tW - 1)
                val by = (gy.toFloat() / GRID_SIZE * tH).toInt().coerceIn(0, tH - 1)
                val pix = pixels[by * tW + bx]
                val a   = Color.alpha(pix)
                grid[gy * GRID_SIZE + gx] = if (a < 30) 0f
                else colorToMmh(Color.red(pix), Color.green(pix), Color.blue(pix))
            }
        }

        val tl = tileToLatLon(tiles.txMin,     tiles.tyMin,     tiles.zoom)
        val br = tileToLatLon(tiles.txMax + 1, tiles.tyMax + 1, tiles.zoom)
        val bounds = RadarBounds(
            latMin = br.first,  latMax = tl.first,
            lonMin = tl.second, lonMax = br.second
        )
        return RadarData(grid = grid, gridSize = GRID_SIZE, bounds = bounds)
    }

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
}

/**
 * 取得結果。状態を明確に区別する。
 *  - Success    : 受信成功（hasPrecip で雨の有無、tilesReceived/Requested で欠測タイル数が分かる）
 *  - NetworkError: 通信失敗（時刻一覧 or タイルが取れない）
 */
sealed class FetchResult {
    data class Success(
        val data: RadarData,
        val basetime: String,
        /** タイルURLに必要 (MapLibre雨雲ソースの差し替えに使用) */
        val validtime: String,
        val tilesReceived: Int,
        val tilesRequested: Int,
        val hasPrecip: Boolean
    ) : FetchResult() {
        /** 一部タイルが欠落しているか */
        val hasMissingTiles: Boolean get() = tilesReceived < tilesRequested
    }

    data class NetworkError(val message: String) : FetchResult()
}
