package com.watchgallery.app.data

import android.content.Context

/**
 * 表冠偏好持久化 —— 用两个独立 key 存，读取极快。
 * 表冠事件每帧都可能来，不能为此解析整份设置（借鉴 f-x 的 loadCrownPrefs 思路）。
 */
object CrownPrefs {
    private const val PREF = "watch_gallery"
    private const val KEY_MODE = "crown_mode"
    private const val KEY_SPEED = "crown_speed"

    /** 灵敏度范围 0.1x ~ 3.0x，默认 1.0x */
    const val SPEED_MIN = 0.1f
    const val SPEED_MAX = 3.0f

    fun load(context: Context): Pair<CrownMode, Float> {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val mode = CrownMode.fromOrdinal(p.getInt(KEY_MODE, 0))
        val speed = p.getFloat(KEY_SPEED, 1.0f).coerceIn(SPEED_MIN, SPEED_MAX)
        return mode to speed
    }

    fun save(context: Context, mode: CrownMode, speed: Float) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MODE, mode.ordinal)
            .putFloat(KEY_SPEED, speed.coerceIn(SPEED_MIN, SPEED_MAX))
            .apply()
    }
}
