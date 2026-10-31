package com.apex.agent.ui.screen.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.agent.terminalmcp.McpServerManager.McpServerState

/**
 * T-CTRL — MCP 控制中心（terminal 十连 PR 9/10）。
 *
 * 让人类能管（开关/端口/令牌再生成）、能看（状态/端点/待确认队列）、能接
 * （adb reverse / Claude Code / Cursor / RikkaHub 接入引导一键复制）。
 */
@Composable
fun McpControlSheet(viewModel: TerminalViewModel, onDismiss: () -> Unit) {
    val state by viewModel.mcpState.collectAsStateWithLifecycle()
    val pending by viewModel.mcpPending.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val config = remember { viewModel.mcpConfig }
    var portField by remember { mutableStateOf(config.port.toString()) }
    var tokenVisible by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── 状态与开关 ──
        McpSectionCard(title = stringResource(R.string.mcp_section_server)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = when (state) {
                            is McpServerState.Running ->
                                stringResource(R.string.mcp_state_running, (state as McpServerState.Running).port)
                            is McpServerState.Disabled -> stringResource(R.string.mcp_state_disabled)
                            is McpServerState.Failed ->
                                stringResource(R.string.mcp_state_failed, (state as McpServerState.Failed).reason)
                            else -> stringResource(R.string.mcp_state_stopped)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Switch(
                    checked = state is McpServerState.Running,
                    onCheckedChange = { viewModel.setMcpEnabled(it) },
                )
            }
        }

        // ── 端点与令牌 ──
        McpSectionCard(title = stringResource(R.string.mcp_section_endpoint)) {
            val endpoint = (state as? McpServerState.Running)?.endpoint
                ?: "http://127.0.0.1:${config.port}/mcp"
            CopyableLine(
                label = stringResource(R.string.mcp_endpoint_label),
                value = endpoint,
                onCopy = { clipboard.setText(AnnotatedString(endpoint)) },
            )
            val token = if (tokenVisible) config.authToken else "•".repeat(16)
            CopyableLine(
                label = stringResource(R.string.mcp_token_label),
                value = token,
                onCopy = { clipboard.setText(AnnotatedString(config.authToken)) },
                trailing = {
                    TextButton(onClick = { tokenVisible = !tokenVisible }) {
                        Text(stringResource(if (tokenVisible) R.string.mcp_token_hide else R.string.mcp_token_show))
                    }
                    IconButton(onClick = { viewModel.regenerateMcpToken() }) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.mcp_token_regen))
                    }
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = portField,
                    onValueChange = { portField = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.mcp_port_label)) },
                    singleLine = true,
                    modifier = Modifier.width(120.dp),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { portField.toIntOrNull()?.let { viewModel.setMcpPort(it) } },
                ) { Text(stringResource(R.string.mcp_port_apply)) }
            }
        }

        // ── 接入引导（三客户端）──
        McpSectionCard(title = stringResource(R.string.mcp_section_connect)) {
            Text(stringResource(R.string.mcp_connect_usb_hint), style = MaterialTheme.typography.bodySmall)
            val adbCmd = "adb reverse tcp:${config.port} tcp:${config.port}"
            CopyableLine("USB", adbCmd) { clipboard.setText(AnnotatedString(adbCmd)) }
            val claudeCmd = "claude mcp add --transport http theocracy http://127.0.0.1:${config.port}/mcp --header \"Authorization: Bearer ${config.authToken}\""
            CopyableLine("Claude Code", claudeCmd) { clipboard.setText(AnnotatedString(claudeCmd)) }
            val rikkaHint = "RikkaHub → MCP → 添加 Streamable HTTP: http://127.0.0.1:${config.port}/mcp (Header: Authorization: Bearer <token>)"
            CopyableLine("RikkaHub", rikkaHint) { clipboard.setText(AnnotatedString(rikkaHint)) }
        }

        // ── 门控待确认（Dangerous 命令的人工裁决队列）──
        if (pending.isNotEmpty()) {
            McpSectionCard(title = stringResource(R.string.mcp_section_pending, pending.size)) {
                pending.forEach { item ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                item.command,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { viewModel.resolveConfirmation(item.confirmId, true) }) {
                                Icon(Icons.Outlined.CheckCircle, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.mcp_confirm_approve))
                            }
                            TextButton(onClick = { viewModel.resolveConfirmation(item.confirmId, false) }) {
                                Icon(Icons.Outlined.Close, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.mcp_confirm_deny))
                            }
                        }
                    }
                }
            }
        }

        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.mcp_close))
        }
    }
}

@Composable
private fun McpSectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ConsoleTokens.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun CopyableLine(
    label: String,
    value: String,
    onCopy: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = ConsoleTokens.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onCopy) {
                Icon(Icons.Filled.ContentCopy, stringResource(R.string.mcp_copy), Modifier.size(16.dp))
            }
            trailing?.invoke()
        }
    }
}
