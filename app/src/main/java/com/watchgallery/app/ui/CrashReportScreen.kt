package com.watchgallery.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.watchgallery.app.util.CrashLogger
import kotlinx.coroutines.flow.Flow

/**
 * 崩溃报告页 —— 检测到上次异常退出时，启动后第一屏强制显示。
 * 因为应用可能一打开就崩，用户进不了设置页，所以这里必须自带「复制日志」。
 * 日志区用 [ScalingScrollColumn] 承载，支持表冠滚动。
 */
@Composable
fun CrashReportScreen(
    isRound: Boolean,
    crownEvents: Flow<Float>,
    onContinue: () -> Unit
) {
    val context = LocalContext.current
    var logText by remember { mutableStateOf(CrashLogger.read(context)) }

    RoundSafeArea(isRound = isRound) {
        ScalingScrollColumn(crownEvents = crownEvents) {
            item(key = "crash_title") {
                Box(Modifier.fillMaxWidth()) {
                    Column {
                        Text(
                            "检测到上次异常退出",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "请把日志复制发给开发者，帮助定位问题。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item(key = "crash_actions") {
                Box(Modifier.fillMaxWidth()) {
                    Column {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = { copyLog(context, logText) },
                                modifier = Modifier.weight(1f).height(38.dp)
                            ) {
                                Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("复制日志", fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    CrashLogger.clear(context)
                                    logText = CrashLogger.read(context)
                                    Toast.makeText(context, "日志已清除", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.height(38.dp)
                            ) {
                                Icon(Icons.Filled.Delete, null, modifier = Modifier.size(16.dp))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Button(
                            onClick = {
                                CrashLogger.consumePending(context)
                                onContinue()
                            },
                            modifier = Modifier.fillMaxWidth().height(38.dp),
                            colors = ButtonDefaults.filledTonalButtonColors()
                        ) {
                            Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("继续进入应用", fontSize = 12.sp)
                        }
                    }
                }
            }
            item(key = "crash_body") {
                Box(Modifier.fillMaxWidth()) {
                    Text(
                        logText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 13.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}

private fun copyLog(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("WatchGallery 崩溃日志", text))
    Toast.makeText(context, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
}
