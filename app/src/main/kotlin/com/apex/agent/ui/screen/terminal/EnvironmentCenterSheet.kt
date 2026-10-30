@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.apex.agent.ui.screen.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * 环境中心 Sheet（T96 自旧版全量转移 + M3 化重写；Theocracy 收口）。
 *
 * Ubuntu rootfs 卡与依赖安装卡（apt 路由）已随 Ubuntu/PRoot 栈整体移除 ——
 * 现仅保留 Android 本地 Shell 卡（内置徽章）。后续终端重构可在此扩展
 * 新环境能力卡片。
 */
@Composable
internal fun EnvironmentCenterSheet(
    viewModel: TerminalViewModel,
    onDismiss: () -> Unit
) {
    TerminalConsoleTheme {
        ModalBottomSheet(onDismissRequest = onDismiss, containerColor = ConsoleTokens.background) {
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        stringResource(com.apex.agent.R.string.term_env_center_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = ConsoleTokens.onSurface,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        stringResource(com.apex.agent.R.string.term_env_center_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = ConsoleTokens.onSurfaceVariant
                    )
                }
                item {
                    TerminalHostCard()
                }
            }
        }
    }
}

// ═══════════════ 本地终端宿主卡（原 Android Shell —— Theocracy 更名）═══

@Composable
private fun TerminalHostCard() {
    SectionCard(title = stringResource(com.apex.agent.R.string.term_host_shell_title)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(com.apex.agent.R.string.term_host_shell_desc),
                style = MaterialTheme.typography.bodySmall,
                color = ConsoleTokens.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                stringResource(com.apex.agent.R.string.term_builtin),
                style = MaterialTheme.typography.labelMedium,
                color = ConsoleTokens.amber,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

// ═══════════════ 共享小部件（Settings/History Sheet 复用）═══════════════

@Composable
internal fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = ConsoleTokens.surfaceContainer)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = ConsoleTokens.onSurface,
                fontFamily = FontFamily.Monospace
            )
            content()
        }
    }
}

@Composable
internal fun ToggleRow(
    title: String,
    desc: String?,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = ConsoleTokens.onSurface)
            desc?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = ConsoleTokens.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}
