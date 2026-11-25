package com.watchgallery.app.data

import android.content.Context

/**
 * 恢复图片的「原始拍摄日期」映射表。
 *
 * 背景：Android 10+ 起 MediaStore.DATE_ADDED / DATE_MODIFIED 由系统维护，
 * App 通过 ContentValues 写入的值会被强制覆盖成当前时间，无法把旧日期写回去。
 * 因此改为在应用层记住「恢复后新分配的 mediaId → 原图 dateAdded(秒)」，
 * 扫描相册时按 id 查表覆盖 Photo.dateAdded，从而让相册排序、日期分组、
 * 详细信息都显示原来的拍摄日期。
 *
 * 关闭「恢复保留原日期」开关时：恢复后不写映射（或删除旧映射），
 * 相册就沿用系统给 DATE_ADDED 的当前时间，即恢复时间。
 */
object RestoreDateStore {
    private const val PREFS = "watch_gallery_restore_dates"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 记录一张恢复图的新 id 对应的原日期。seconds<=0 时视为清除。 */
    fun put(context: Context, mediaId: Long, dateAddedSeconds: Long) {
        if (mediaId <= 0L) return
        if (dateAddedSeconds <= 0L) {
            remove(context, mediaId)
        } else {
            prefs(context).edit().putLong(mediaId.toString(), dateAddedSeconds).apply()
        }
    }

    /** 取出恢复图应显示的日期（秒）；0 表示没有映射，沿用系统值。 */
    fun get(context: Context, mediaId: Long): Long =
        if (mediaId <= 0L) 0L else prefs(context).getLong(mediaId.toString(), 0L)

    fun remove(context: Context, mediaId: Long) {
        if (mediaId <= 0L) return
        prefs(context).edit().remove(mediaId.toString()).apply()
    }
}
