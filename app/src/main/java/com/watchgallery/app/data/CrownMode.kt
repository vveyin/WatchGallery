package com.watchgallery.app.data

/**
 * 表冠两种事件来源模式 —— 完整借鉴 f-x 的实现思路。
 *
 * 不同厂家的手表把「表冠旋转」上报成不同的输入事件，一套代码吃不下两种：
 * - [OPPO]：旋转走普通 MotionEvent 的滚轮轴（AXIS_SCROLL/AXIS_VSCROLL/WHEEL），
 *   不做 action/source 过滤（这些表的 source 位不可靠，任何过滤都可能把表冠挡掉）。
 * - HUAWEI_SAMSUNG：华为/三星走标准旋转编码器，action==ACTION_SCROLL 且
 *   source 带 SOURCE_ROTARY_ENCODER，原始值 ±1 一格，需放大到接近 OPPO 手感。
 *
 * 用错模式就是「表冠完全没反应」，所以放到设置里让用户切换。
 */
enum class CrownMode(val label: String, val description: String) {
    OPPO("OPPO", "OPPO / 一加等：普通滚轮事件，不过滤来源"),
    HUAWEI_SAMSUNG("华为 / 三星", "标准旋转编码器：ACTION_SCROLL + 编码器来源");

    fun next(): CrownMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromOrdinal(value: Int): CrownMode = entries.getOrNull(value) ?: OPPO
    }
}
