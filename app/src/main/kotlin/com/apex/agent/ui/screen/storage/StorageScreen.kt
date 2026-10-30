package com.apex.agent.ui.screen.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.agent.ui.screen.terminal.formatBytes
import kotlinx.coroutines.delay

/**
 * 存储与数据管理页（抽屉「存储」目的地）。
 *
 * 三个区块（全部真实数据源）：
 *  1. 附件沙箱 —— AttachmentCleanupManager 用量/清理；
 *  2. 会话历史 —— 全局对话条数/导出（文本分享）/清空；
 *  3. 运行日志 —— 条数/内存占用，跳转日志页由用户自行导出。
 * （历史：Ubuntu rootfs 占用/删除卡已随 Ubuntu/PRoot 栈剥离移除。）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(
    viewModel: StorageViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmAction by remember { mutableStateOf<String?>(null) }

    // 结果反馈自动消隐
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(4000)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        // 内层 Scaffold 置零 insets：状态栏已由根 Scaffold 顶栏承担，避免双重叠加
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                // 顶栏置零 windowInsets，避免与根 Scaffold 状态栏双重叠加
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(stringResource(R.string.storage_title)) },
                actions = {
                    if (state.busy) {
                        CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.storage_refresh), tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
            if (state.loading) {
                Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            // ═══ 1. 附件沙箱 ═══
            StorageCard(
                icon = Icons.Default.AttachFile,
                title = stringResource(R.string.storage_attachments),
                tint = MaterialTheme.colorScheme.primary
            ) {
                StorageMetricRow(stringResource(R.string.storage_space_used), formatBytes(state.attachmentsSize))
                StorageMetricRow(stringResource(R.string.storage_files), stringResource(R.string.storage_unit_files, state.attachmentsCount))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { confirmAction = "attachments" },
                        enabled = !state.busy && state.attachmentsCount > 0,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Delete, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.storage_clear_attachments), color = MaterialTheme.colorScheme.error)
                    }
                }
                Text(
                    stringResource(R.string.storage_attachments_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ═══ 2. 会话历史 ═══
            StorageCard(
                icon = Icons.Default.Chat,
                title = stringResource(R.string.storage_conversation_history),
                tint = MaterialTheme.colorScheme.secondary
            ) {
                StorageMetricRow(stringResource(R.string.storage_messages), stringResource(R.string.storage_unit_msgs, state.conversationCount))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = viewModel::exportConversation,
                        enabled = !state.busy && state.conversationCount > 0,
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.storage_export_text)) }
                    OutlinedButton(
                        onClick = { confirmAction = "conversation" },
                        enabled = !state.busy && state.conversationCount > 0,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.storage_clear), color = MaterialTheme.colorScheme.error)
                    }
                }
                Text(
                    stringResource(R.string.storage_conversation_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ═══ 3. 运行日志 ═══
            StorageCard(
                icon = Icons.Default.Info,
                title = stringResource(R.string.storage_log_title),
                tint = MaterialTheme.colorScheme.primary
            ) {
                StorageMetricRow(stringResource(R.string.storage_records), stringResource(R.string.storage_unit_msgs, state.logCount))
                StorageMetricRow(stringResource(R.string.storage_usage), formatBytes(state.logBytes))
                val ratio = (state.logBytes.toFloat() / 8_388_608f).coerceIn(0f, 1f) // 8MB 上限（LogViewer 同源）
                if (ratio > 0f) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { ratio },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (ratio > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    stringResource(R.string.storage_log_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 底部反馈条（Box 层级，不随内容滚动）
        state.message?.let { msg ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .padding(16.dp)
                    .align(Alignment.BottomCenter)
            ) {
                Text(
                    msg,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
        }
    }

    // ═══ 破坏性操作二次确认 ═══
    if (confirmAction != null) {
        val (title, text) = when (confirmAction) {
            "attachments" -> stringResource(R.string.storage_confirm_attachments_title) to
                stringResource(
                    R.string.storage_confirm_attachments_text,
                    state.attachmentsCount,
                    formatBytes(state.attachmentsSize)
                )
            else -> stringResource(R.string.storage_confirm_conversation_title) to
                stringResource(R.string.storage_confirm_conversation_text, state.conversationCount)
        }
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = {
                    when (confirmAction) {
                        "attachments" -> viewModel.clearAttachments()
                        "conversation" -> viewModel.clearConversation()
                    }
                    confirmAction = null
                }) { Text(stringResource(R.string.storage_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) { Text(stringResource(R.string.storage_cancel)) }
            }
        )
    }
}

@Composable
private fun StorageCard(
    icon: ImageVector,
    title: String,
    tint: Color,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = CircleShape, color = tint.copy(alpha = 0.15f), modifier = Modifier.size(34.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(18.dp), tint = tint) }
                }
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun StorageMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
    }
}
