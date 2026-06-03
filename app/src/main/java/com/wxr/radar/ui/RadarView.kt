package com.wxr.radar.ui

/**
 * 旧 RadarView は WXR Moto ピボットで NdOverlayView に置き換えられた。
 *
 * - 雨雲・道路の描画 → MapLibre (map/MapController.kt)
 * - 計器(コンパス/リング/自機/マスク)の描画 → ui/NdOverlayView.kt
 *
 * このファイルは履歴保全のため残置（削除しないこと）。
 */
@Deprecated(
    message = "NdOverlayView + MapController に移行済み",
    replaceWith = ReplaceWith("NdOverlayView", "com.wxr.radar.ui.NdOverlayView")
)
typealias RadarView = NdOverlayView
