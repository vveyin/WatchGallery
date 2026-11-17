package com.watchgallery.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.watchgallery.app.data.CrownMode
import com.watchgallery.app.data.CrownPrefs
import com.watchgallery.app.theme.ThemeMode

/**
 * 设置页 —— 圆屏安全区内布局，分主菜单与「表冠适配」子页。
 * 借鉴 f-x：表冠模式 + 灵敏度是独立子页，点击即可切换/调节。
 */
@Composable
fun SettingsScreen(
    viewModel: GalleryViewModel,
    isRound: Boolean,
    onBack: () -> Unit,
    onCrownPrefsChanged: () -> Unit
) {
    var subPage by remember { mutableStateOf<SubPage>(SubPage.Main) }

    when (subPage) {
        SubPage.Main -> SettingsMain(
            viewModel = viewModel,
            isRound = isRound,
            onBack = onBack,
            onOpenCrown = { subPage = SubPage.Crown },
            onOpenTheme = { subPage = SubPage.Theme }
        )
        SubPage.Crown -> CrownSettings(
            viewModel = viewModel,
            isRound = isRound,
            onBack = { subPage = SubPage.Main },
            onPrefsChanged = onCrownPrefsChanged
        )
        SubPage.Theme -> ThemeSettings(
            viewModel = viewModel,
            isRound = isRound,
            onBack = { subPage = SubPage.Main }
        )
    }
}

private sealed class SubPage { data object Main : SubPage(); data object Crown : SubPage(); data object Theme : SubPage() }

/** 设置主页 */
@Composable
private fun SettingsMain(
    viewModel: GalleryViewModel,
    isRound: Boolean,
    onBack: () -> Unit,
    onOpenCrown: () -> Unit,
    onOpenTheme: () -> Unit
) {
    val themeMode by viewModel.themeMode.collectAsState()
    val crownMode by viewModel.crownMode.collectAsState()
    val crownSpeed by viewModel.crownSpeed.collectAsState()

    RoundSafeArea(isRound) {
        Column(Modifier.fillMaxSize()) {
            SettingsHeader("设置", onBack)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SettingRow(
                    icon = Icons.Filled.Cached,
                    title = "表冠适配",
                    subtitle = "${crownMode.label} · ${String.format("%.1f", crownSpeed)}x",
                    onClick = onOpenCrown
                )
                SettingRow(
                    icon = when (themeMode) {
                        ThemeMode.DARK -> Icons.Filled.DarkMode
                        ThemeMode.LIGHT -> Icons.Filled.LightMode
                        else -> Icons.Filled.BrightnessAuto
                    },
                    title = "主题",
                    subtitle = when (themeMode) {
                        ThemeMode.DARK -> "夜间"
                        ThemeMode.LIGHT -> "白天"
                        else -> "跟随系统"
                    },
                    onClick = onOpenTheme
                )
                SettingRow(
                    icon = Icons.Filled.Info,
                    title = "关于",
                    subtitle = "相册 v0.1 · 圆屏手表版",
                    onClick = {}
                )
            }
        }
    }
}

/** 表冠适配子页 */
@Composable
private fun CrownSettings(
    viewModel: GalleryViewModel,
    isRound: Boolean,
    onBack: () -> Unit,
    onPrefsChanged: () -> Unit
) {
    var mode by remember { mutableStateOf(viewModel.crownMode.value) }
    var speed by remember { mutableFloatStateOf(viewModel.crownSpeed.value) }

    fun commit() {
        viewModel.setCrownConfig(mode, speed)
        onPrefsChanged()
    }

    RoundSafeArea(isRound) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SettingsHeader("表冠适配", onBack)
            Spacer(Modifier.height(4.dp))
            Text(
                "不同手表表冠上报方式不同，选错会导致表冠无反应。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            Text("模式", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            CrownMode.entries.forEach { m ->
                val selected = mode == m
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clickable { mode = m; commit() },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected, onClick = { mode = m; commit() },
                            modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(m.label, style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold)
                            Text(m.description, style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("灵敏度  ${String.format("%.1f", speed)}x",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Slider(
                value = speed,
                valueRange = CrownPrefs.SPEED_MIN..CrownPrefs.SPEED_MAX,
                onValueChange = { speed = it },
                onValueChangeFinished = { commit() },
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("慢", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("快", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 主题设置子页 */
@Composable
private fun ThemeSettings(
    viewModel: GalleryViewModel,
    isRound: Boolean,
    onBack: () -> Unit
) {
    val current by viewModel.themeMode.collectAsState()
    val options = listOf(
        ThemeMode.DARK to "夜间（黑底）",
        ThemeMode.LIGHT to "白天（白纸）",
        ThemeMode.SYSTEM to "跟随系统"
    )
    RoundSafeArea(isRound) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SettingsHeader("主题", onBack)
            Spacer(Modifier.height(8.dp))
            options.forEach { (mode, label) ->
                val selected = current == mode
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { viewModel.setThemeMode(mode) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected, onClick = { viewModel.setThemeMode(mode) },
                            modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
        }
        Spacer(Modifier.width(4.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
