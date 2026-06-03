package com.wxr.radar

import android.app.Application
import org.maplibre.android.MapLibre

class WxrApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // MapView を inflate する前に必ず初期化が必要
        MapLibre.getInstance(this)
    }
}
