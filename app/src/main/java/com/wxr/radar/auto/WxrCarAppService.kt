package com.wxr.radar.auto

import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Android Auto CarAppService
 *
 * Android Auto への接続エントリポイント。
 * Session → Screen の流れで WxrScreen を表示する。
 */
class WxrCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        // デバッグビルドは全ホスト許可 / リリースビルドは Google のみ
        return if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(): Session = WxrSession()
}
