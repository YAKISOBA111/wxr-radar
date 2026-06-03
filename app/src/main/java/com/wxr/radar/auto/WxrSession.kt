package com.wxr.radar.auto

import androidx.car.app.Screen
import androidx.car.app.Session

class WxrSession : Session() {
    override fun onCreateScreen(intent: android.content.Intent): Screen {
        return WxrScreen(carContext)
    }
}
