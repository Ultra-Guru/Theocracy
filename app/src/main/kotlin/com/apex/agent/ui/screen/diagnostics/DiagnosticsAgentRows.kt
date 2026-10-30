package com.apex.agent.ui.screen.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.tools.ToolAuditLogger

/**
 * 诊断中心「Agent 能力区」行组件（从 DiagnosticsScreen 按职责缝拆出，
 * 参见 orchestrator/ 拆分模式）：MCP 服务器行 / 技能行 / 工具审计行，
 * 以及它们共享的展示数据类。
 *
 * 设计约束：
 * - 全部为无状态展示组件，数据由 DiagnosticsViewModel 快照注入；
 * - 连接/启用/失败等状态除颜色外均有文字徽标互补（色盲可读）；
 * - 失败行整体可点击（errorContainer 低透明度底色），由调用方弹出详情。
 */









/**
 * 工具审计行：时间 + 工具名（等宽）+ 决策徽标 + 耗时；失败行错误色强调且可点击
 * 打开详情弹窗（[onOpenDetail]）。
 */
@Composable
internal fun ToolAuditListRow(
    entry: ToolAuditLogger.AuditEntry,
    timeLabel: String,
    isFailure: Boolean = false,
    onOpenDetail: () -> Unit = {}
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isFailure) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
        else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .let { if (isFailure) it.clickable(onClick = onOpenDetail) else it }
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isFailure) Icons.Outlined.Warning else Icons.Outlined.Terminal,
                contentDescription = stringResource(R.string.diag_view),
                tint = if (isFailure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(
                    buildString {
                        append(entry.tool)
                        entry.tier?.let { append("  [").append(it).append(']') }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isFailure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    buildString {
                        append(timeLabel)
                        append("  ·  ").append(entry.decision)
                        entry.durationMs?.let { append("  ·  ").append(it).append("ms") }
                        entry.command?.let { append('\n').append(it) }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
