package com.wxr.radar.data

/** レーダーグリッド1枚分のデータ */
data class RadarData(
    /** GRID×GRID の降水強度 (mm/h)。インデックス = y*GRID + x */
    val grid: FloatArray,
    val gridSize: Int,
    /** データの地理的範囲 */
    val bounds: RadarBounds,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun getAt(gx: Int, gy: Int): Float = grid[gy * gridSize + gx]
}

data class RadarBounds(
    val latMin: Double,
    val latMax: Double,
    val lonMin: Double,
    val lonMax: Double
)

/** GPS/センサーから得る自機状態 */
data class OwnshipState(
    val lat: Double = 35.6895,
    val lon: Double = 139.6917,
    /** 進行方向 (degrees, true north = 0) */
    val headingDeg: Float = 0f,
    /** 速度 km/h */
    val speedKmh: Float = 0f,
    /** ヘディングのソース */
    val headingSource: HeadingSource = HeadingSource.SENSOR
)

enum class HeadingSource { GPS, SENSOR, SIMULATED }

/** ND表示設定 */
data class NdSettings(
    val mode: NdMode       = NdMode.ARC,
    val orient: NdOrient   = NdOrient.HEADING_UP,
    val rangeNm: Int       = 80,
    val wxrOn: Boolean     = true,
    val terrOn: Boolean    = false,
    val arptOn: Boolean    = false,
    val distanceUnit: DistanceUnit = DistanceUnit.NM
) {
    /** データ取得に使う実距離は常にkm */
    val rangeKm: Double get() = rangeNm * 1.852

    /** 現在の単位でのレンジ値（整数） */
    fun rangeInUnit(): Int = when (distanceUnit) {
        DistanceUnit.NM -> rangeNm
        DistanceUnit.KM -> Math.round(rangeNm * 1.852).toInt()
    }

    /** "80 NM" / "148 km" のような表示文字列 */
    fun rangeLabel(): String = "${rangeInUnit()} ${distanceUnit.label}"

    /** 距離リング i/total 段目のラベル（現在単位） */
    fun ringLabel(i: Int, total: Int): String {
        val v = when (distanceUnit) {
            DistanceUnit.NM -> rangeNm * i / total
            DistanceUnit.KM -> Math.round(rangeNm * 1.852 * i / total).toInt()
        }
        return v.toString()
    }
}

enum class NdMode   { ARC, ROSE }
enum class NdOrient { HEADING_UP, NORTH_UP }
enum class DistanceUnit(val label: String) { NM("NM"), KM("km") }
