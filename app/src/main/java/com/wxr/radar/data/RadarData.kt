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

/**
 * ND表示設定。
 *
 * レンジは【常に km 基準】で保持する（データ取得・カメラ zoom 算出も km）。
 * NM はあくまで表示単位の切替であり、内部表現には影響しない。
 */
data class NdSettings(
    val mode: NdMode       = NdMode.ARC,
    val orient: NdOrient   = NdOrient.HEADING_UP,
    /** レンジ [km]。RANGE_STEPS_KM のいずれか */
    val rangeKm: Int       = DEFAULT_RANGE_KM,
    val wxrOn: Boolean     = true,
    val terrOn: Boolean    = false,
    val arptOn: Boolean    = false,
    /** 表示単位（表示専用。データ取得は常に km 基準） */
    val distanceUnit: DistanceUnit = DistanceUnit.KM,
    /** 車速連動オートレンジ ON/OFF */
    val autoRange: Boolean = false
) {
    companion object {
        /** レンジ選択肢 [km]（8段） */
        val RANGE_STEPS_KM = listOf(1, 3, 5, 10, 30, 50, 100, 300)
        /** 初期レンジ [km]（約5マイル） */
        const val DEFAULT_RANGE_KM = 10

        /** km → NM 換算係数 */
        const val KM_PER_NM = 1.852

        /**
         * 車速 [km/h] → 推奨レンジ [km]。
         * 低速=詳細(近く) / 高速=広域(先まで)
         */
        fun autoRangeForSpeed(speedKmh: Float): Int = when {
            speedKmh < 10f -> 3
            speedKmh < 30f -> 5
            speedKmh < 60f -> 10
            speedKmh < 90f -> 30
            else           -> 50
        }

        /** 数値の表示整形（10未満は小数1桁、それ以上は整数） */
        fun formatDistance(v: Double): String =
            if (v >= 9.95) Math.round(v).toString()
            else {
                val s = String.format("%.1f", v)
                if (s.endsWith(".0")) s.dropLast(2) else s
            }
    }

    /** 現在の単位でのレンジ表示値 */
    fun rangeValueInUnit(): String = when (distanceUnit) {
        DistanceUnit.KM -> rangeKm.toString()
        DistanceUnit.NM -> formatDistance(rangeKm / KM_PER_NM)
    }

    /** "10 km" / "5.4 NM" のような表示文字列 */
    fun rangeLabel(): String = "${rangeValueInUnit()} ${distanceUnit.label}"

    /** 距離リング i/total 段目のラベル（現在単位） */
    fun ringLabel(i: Int, total: Int): String {
        val km = rangeKm.toDouble() * i / total
        return when (distanceUnit) {
            DistanceUnit.KM -> formatDistance(km)
            DistanceUnit.NM -> formatDistance(km / KM_PER_NM)
        }
    }
}

enum class NdMode   { ARC, ROSE }
enum class NdOrient { HEADING_UP, NORTH_UP }
enum class DistanceUnit(val label: String) { NM("NM"), KM("km") }
