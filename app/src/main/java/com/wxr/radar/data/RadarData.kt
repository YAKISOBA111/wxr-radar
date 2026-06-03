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
    val arptOn: Boolean    = false
) {
    val rangeKm: Double get() = rangeNm * 1.852
}

enum class NdMode   { ARC, ROSE }
enum class NdOrient { HEADING_UP, NORTH_UP }
