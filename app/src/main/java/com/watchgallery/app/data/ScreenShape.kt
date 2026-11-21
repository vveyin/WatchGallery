package com.watchgallery.app.data

/**
 * 屏幕形状偏好。
 *
 * 圆屏要额外做两件事：把列表/弹窗收进内切圆、让中间一排照片放大并让上下边缘淡出。
 * 有些表（或模拟器）系统上报的是圆屏，实际显示却是方屏；也有人喜欢方屏上不加
 * 「居中放大」这套效果。所以给一个手动开关：
 *
 *  - [AUTO]   跟随系统 `SCREENLAYOUT_ROUND`；
 *  - [ROUND]  强制按圆屏排版；
 *  - [SQUARE] 强制按方屏排版 —— 列表不做居中放大、控件铺满整屏。
 */
enum class ScreenShape(val key: String, val label: String) {
    AUTO("auto", "自动"),
    ROUND("round", "圆屏"),
    SQUARE("square", "方屏");

    companion object {
        fun fromKey(key: String?): ScreenShape =
            values().firstOrNull { it.key == key } ?: AUTO

        /** 点一下切到下一个：自动 → 圆屏 → 方屏 → 自动 */
        fun next(shape: ScreenShape): ScreenShape = when (shape) {
            AUTO -> ROUND
            ROUND -> SQUARE
            SQUARE -> AUTO
        }
    }
}
