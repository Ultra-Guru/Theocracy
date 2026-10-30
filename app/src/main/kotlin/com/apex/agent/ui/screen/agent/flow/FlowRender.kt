package com.apex.agent.ui.screen.agent.flow

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

// ─────────────────────────────────────────────────────────────────────────────
// Theocracy Flow —— 流水（流式输出）渲染体系 v2（2026-10 重做）
//
// 设计语言：Rikka 系的干净 MD3 基底（大圆角、细光标、安静层次）之上，
// 四项 Theocracy 自己的创新：
//
// ① 节奏感知光标（Tempo Cursor）
//    光标不再恒速呼吸——高度与亮度随 token 到达速率自适应：流速高 →
//    光标挺拔饱满（快速吐字的"手感"）；流速低 → 光标收窄低亮慢呼吸
//    （模型在组织长思路）。用户无需读字就能感知模型的节奏状态。
//
// ② 段落凝实（Paragraph Coalescence）
//    新定稿段落以 55% 透明度淡入至全不透明（160ms）——"墨水凝实"
//    的隐喻：流式文本是湿的，落定即干。块以原文为 key，只在自己
//    首次出现的组合期做一次入场动画，动画完成后图层 alpha 恒 1。
//
// ③ 代码围栏即时终端化（Live Fence Terminalization）
//    定稿块若为完整 ``` 围栏代码，流式期间即刻进入"终端卡"形态
//    （等宽字体 + 左侧 3dp 主题渐变色条 + 微暗底 + 压缩密度）——
//    不等完成态的 MarkdownText，代码在流水中就是最终的样子。
//
// ④ 流速丝带（Tempo Ribbon）
//    气泡底部 2dp 渐变丝带：宽度 ∝ min(字数 / RIBBON_FULL, 1) ——
//    长回复丝带渐进满宽，给用户"这条回复的体量"的直觉量度；
//    primary → tertiary 水平渐变（Rikka 式的清爽撞色）。
//
// 性能纪律（继承 StreamingParagraphSlicer 的增量架构）：
// - 定稿块 Text 参数跨 flush 实例不变 → Compose 跳过重排；
// - 所有动画值经 graphicsLayer / draw 块延迟读取，不打散重组范围；
// - 凝实动画由 animateFloatAsState 驱动，结束即静止。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 流速跟踪器（纯 Kotlin，无 Compose 依赖，可直接 JVM 单测）。
 *
 * 滑动窗口记录字符到达样本，归一化为 0..1 的"节奏值"：
 * - 窗口内 ≥ [fastCps] 字符/秒 → 1f（疾速）；
 * - ≤ [slowCps] 字符/秒 → 0f（迟缓/深思）；
 * - 之间线性插值。
 *
 * 惰性评估：[tempo] 每次读取时重算；无定时器线程。
 */
internal class FlowTempoTracker {

    /** 采样窗口（ms）：窗口外的旧样本自动失效。 */
    private val windowMs = 2_000L

    /** 疾速阈值（字符/秒）。 */
    private val fastCps = 90

    /** 迟缓阈值（字符/秒）。 */
    private val slowCps = 8

    /** (timestamp, 本批增量字符数) 样本队列。 */
    private val samples = ArrayDeque<Pair<Long, Int>>()

    /** 已见全文长度（用于计算 flush 间增量）。 */
    private var totalLen = 0

    /** 文本更新（每次 flush）时调用：len 为当前全文长度。 */
    fun onText(len: Int) {
        val now = System.currentTimeMillis()
        val delta = len - totalLen
        if (delta > 0) {
            samples.addLast(now to delta)
        } else if (len < totalLen) {
            samples.clear() // 新一轮回复（长度收缩）：节奏窗口重置
        }
        totalLen = len
        while (samples.isNotEmpty() && now - samples.first().first > windowMs) {
            samples.removeFirst()
        }
    }

    /** 当前节奏值 0..1（无样本 → 0，视为静默）。 */
    val tempo: Float
        get() {
            if (samples.isEmpty()) return 0f
            val now = System.currentTimeMillis()
            val spanMs = (now - samples.first().first).coerceAtLeast(1L)
            val chars = samples.sumOf { it.second }
            val cps = chars * 1000f / spanMs
            return ((cps - slowCps) / (fastCps - slowCps)).coerceIn(0f, 1f)
        }
}

/**
 * 节奏感知光标（创新 ①）。
 *
 * 视觉：圆角竖条。三层节奏表达——
 * - 高度：10dp（缓）→ 14dp（疾），挺拔感；
 * - 亮度：alpha 0.35（缓）→ 0.95（疾）；
 * - 呼吸：700ms 循环，振幅被 tempo 压缩（疾=几乎恒亮；缓=明显呼吸）。
 *
 * 颜色在 draw 块内合成（延迟读取纪律，#262）。
 */
@Composable
internal fun FlowCursor(tempo: Float, color: Color, modifier: Modifier = Modifier) {
    val breathe = rememberInfiniteTransition(label = "flow-cursor").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "cursor-breathe"
    )
    // 呼吸对亮度的调制幅度：tempo=1 → 不呼吸（恒亮）；tempo=0 → 全幅呼吸
    val breatheDepth = (1f - tempo).coerceIn(0f, 1f) * 0.45f
    val baseAlpha = 0.35f + tempo * 0.6f
    val barHeight = 10.dp + tempo * 4.dp
    Canvas(modifier = modifier.size(width = 5.dp, height = barHeight)) {
        val w = this.size.width
        val h = this.size.height
        val barW = 2.5.dp.toPx().coerceAtMost(w)
        val alpha = (baseAlpha + breatheDepth * breathe.value).coerceIn(0.2f, 1f)
        drawRoundRect(
            color = color.copy(alpha = alpha),
            topLeft = androidx.compose.ui.geometry.Offset((w - barW) / 2f, 0f),
            size = androidx.compose.ui.geometry.Size(barW, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2f),
        )
    }
}

/**
 * 段落凝实容器（创新 ②）。
 *
 * 块首次进入组合 → 160ms 透明度 0.55 → 1 的一次性入场；此后静止。
 * key = 块原文（跨 flush 实例稳定）；重组同一块不重播动画。
 */
@Composable
internal fun FlowCoalescingParagraph(
    blockKey: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var appeared by remember(blockKey) { mutableStateOf(false) }
    LaunchedEffect(blockKey) { appeared = true }
    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.55f,
        animationSpec = tween(durationMillis = 160, easing = LinearEasing),
        label = "coalesce"
    )
    Box(modifier = modifier.graphicsLayer { this.alpha = alpha }) { content() }
}

/** 判断定稿块是否为完整的 ``` 围栏代码块（首行以 ``` 开头且尾行闭合）。 */
internal fun isCompleteCodeFence(block: String): Boolean {
    val trimmedStart = block.trimStart()
    if (!trimmedStart.startsWith("```")) return false
    val lines = block.trimEnd().lines()
    return lines.size >= 2 && lines.last().trimEnd().endsWith("```")
}

/** 从围栏块提取代码体（剥离首行语言标注与尾行围栏；无语言行则原样）。 */
internal fun codeFenceBody(block: String): String {
    val lines = block.trimEnd().lines().toMutableList()
    if (lines.isNotEmpty()) lines.removeAt(0)   // ``` 或 ```lang
    if (lines.isNotEmpty() && lines.last().trimEnd().endsWith("```")) lines.removeAt(lines.lastIndex)
    return lines.joinToString("\n")
}

/**
 * 代码围栏终端卡（创新 ③）。
 *
 * 流式期间定稿的 ``` 块即刻终端化：等宽 + 左侧 3dp 主题渐变色条 +
 * surfaceContainerHighest 微暗底 + 压缩的竖向密度。
 */
@Composable
internal fun FlowCodeFenceCard(
    code: String,
    modifier: Modifier = Modifier,
    codeStyle: TextStyle = MaterialTheme.typography.bodySmall,
) {
    val accent = MaterialTheme.colorScheme.primary
    val accentEnd = MaterialTheme.colorScheme.tertiary
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(topStart = 2.dp, topEnd = 8.dp, bottomStart = 8.dp, bottomEnd = 8.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // 左侧色条：primary → tertiary 垂直渐变（终端身份标识）
            Canvas(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
            ) {
                drawRect(
                    brush = Brush.verticalGradient(listOf(accent, accentEnd)),
                    size = this.size,
                )
            }
            Text(
                text = code,
                style = codeStyle,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .fillMaxWidth()
            )
        }
    }
}

/**
 * 流速丝带（创新 ④）。
 *
 * 气泡底部 2dp 渐变条：宽度 ∝ min(len / RIBBON_FULL, 1)。
 * 纯布局驱动（无动画协程）；宽度用 fillMaxWidth(fraction) 声明式表达。
 */
@Composable
internal fun FlowTempoRibbon(
    textLength: Int,
    modifier: Modifier = Modifier,
) {
    val fraction = (textLength / RIBBON_FULL_CHARS).coerceIn(0.05f, 1f)
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .height(2.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
        ) {
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(primary, tertiary)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(this.size.width, this.size.height),
            )
        }
    }
}

/** 丝带满宽字符数（经验值：约等于一条很长的回复）。 */
private const val RIBBON_FULL_CHARS = 4000f
