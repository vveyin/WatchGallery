package com.watchgallery.app.util

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * 主线程卡顿（ANR）看门狗。
 *
 * 背景：Compose 的指针输入协程跑在 AndroidUiDispatcher.Main 上，一旦在里面出现
 * 没有挂起点的忙循环，主线程会被彻底占满 —— 表现出来就是「界面卡死几秒后应用被杀」，
 * 而系统只会留下一个 ANR，**不会**经过 UncaughtExceptionHandler，所以崩溃日志里
 * 只有一行 START，完全看不到堆栈。这正是 v0.27 那次「松手后卡死再崩溃」查不动的原因。
 *
 * 做法：后台守护线程每 1s 往主线程 post 一次心跳，若 2.6s 内心跳没被执行，
 * 说明主线程已被阻塞，立刻抓取主线程堆栈写进崩溃日志，并置位 crash.pending，
 * 让下次启动的崩溃报告页能把堆栈展示出来。
 */
object MainThreadWatchdog {

    private const val HEARTBEAT_MS = 1_000L
    private const val BLOCK_THRESHOLD_MS = 2_600L
    private const val COOLDOWN_MS = 5_000L

    private val lastBeat = AtomicLong(0)

    @Volatile
    private var installed = false

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun install(app: Application) {
        if (installed) return
        installed = true
        val handler = Handler(Looper.getMainLooper())
        lastBeat.set(SystemClock.uptimeMillis())
        thread(isDaemon = true, name = "wg-main-watchdog") {
            while (true) {
                // 投递心跳：主线程正常时会在毫秒级执行并刷新 lastBeat
                handler.post { lastBeat.set(SystemClock.uptimeMillis()) }
                try {
                    Thread.sleep(HEARTBEAT_MS)
                } catch (_: InterruptedException) {
                    return@thread
                }
                val blocked = SystemClock.uptimeMillis() - lastBeat.get()
                if (blocked > BLOCK_THRESHOLD_MS) {
                    dump(app, blocked)
                    try {
                        Thread.sleep(COOLDOWN_MS)
                    } catch (_: InterruptedException) {
                        return@thread
                    }
                }
            }
        }
    }

    private fun dump(app: Application, blockedMs: Long) {
        try {
            val main = Looper.getMainLooper().thread
            val sb = StringBuilder()
            sb.appendLine("=== FREEZE ${timeFmt.format(Date())} 主线程阻塞约 ${blockedMs}ms ===")
            sb.appendLine("thread=${main.name} state=${main.state} prio=${main.priority}")
            sb.appendLine("stack:")
            main.stackTrace.forEach { sb.appendLine("    at $it") }
            sb.appendLine()
            CrashLogger.log(app, sb.toString())
            // 让下次启动能看到这份堆栈
            runCatching { File(app.filesDir, "crash.pending").writeText("freeze") }
        } catch (_: Throwable) {
        }
    }
}
