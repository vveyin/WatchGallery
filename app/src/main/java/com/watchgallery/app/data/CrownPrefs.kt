package com.watchgallery.app.data

import android.content.Context
import kotlin.math.ln
import kotlin.math.pow

/**
 * 表冠偏好持久化 —— 用两个独立 key 存，读取极快。
 * 表冠事件每帧都可能来，不能为此解析整份设置（借鉴 f-x 的 loadCrownPrefs 思路）。
 */
object CrownPrefs {
    private const val PREF = "watch_gallery"
    private const val KEY_MODE = "crown_mode"
    private const val KEY_SPEED = "crown_speed"
    private const val KEY_ZOOM_INVERT = "crown_zoom_invert"

    /** 灵敏度范围 0.1x ~ 10.0x，默认 1.0x */
    const val SPEED_MIN = 0.1f
    const val SPEED_MAX = 10.0f
    const val SPEED_DEFAULT = 1.0f

    /**
     * 对数轴映射：滑杆位置 0~1 ↔ 灵敏度 0.1x~10x。
     *
     * 线性轴做 0.1~10 时，前半段（0.1~1.0）只占 9%，手指稍微一动就跳过，
     * 而低频段恰恰是最需要细调的地方。取对数后，滑杆每一段移动对应的
     * 「倍数变化比例」相同（等比），0.1→0.2 与 5→10 需要拨动的距离一样，
     * 低倍率和高倍率都能精细调。
     */
    fun sliderToSpeed(t: Float): Float {
        val v = t.coerceIn(0f, 1f)
        return (SPEED_MIN * (SPEED_MAX / SPEED_MIN).pow(v))
            .coerceIn(SPEED_MIN, SPEED_MAX)
    }

    /** 灵敏度 → 滑杆位置 0~1（sliderToSpeed 的逆运算） */
    fun speedToSlider(speed: Float): Float {
        val s = speed.coerceIn(SPEED_MIN, SPEED_MAX)
        return (ln(s / SPEED_MIN) / ln(SPEED_MAX / SPEED_MIN)).coerceIn(0f, 1f)
    }

    fun load(context: Context): Pair<CrownMode, Float> {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val mode = CrownMode.fromOrdinal(p.getInt(KEY_MODE, 0))
        val speed = p.getFloat(KEY_SPEED, SPEED_DEFAULT).coerceIn(SPEED_MIN, SPEED_MAX)
        return mode to speed
    }

    fun save(context: Context, mode: CrownMode, speed: Float) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MODE, mode.ordinal)
            .putFloat(KEY_SPEED, speed.coerceIn(SPEED_MIN, SPEED_MAX))
            .apply()
    }

    /** 表冠缩放方向反转：关闭=false（默认，与现有逻辑一致），开启=true（放大缩小方向相反）。
     *  独立 key，不影响 [load]/[save] 的模式与灵敏度读写。 */
    fun loadZoomInvert(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_ZOOM_INVERT, false)

    fun saveZoomInvert(context: Context, invert: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ZOOM_INVERT, invert)
            .apply()
    }
}
