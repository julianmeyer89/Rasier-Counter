package de.rasier.speicher.app.ui

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val ImportColor = Color(0xFFD84315)
val ExportColor = Color(0xFF1565C0)
val SolarColor = Color(0xFFF9A825)
val BatteryColor = Color(0xFF2E7D32)

@Composable
fun RasierTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF81C784))
        else -> lightColorScheme(primary = Color(0xFF1B5E20))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun ValueRow(label: String, value: String, color: Color? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = color ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun BigValue(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun SocBar(percent: Double, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { (percent / 100.0).toFloat().coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(10.dp),
        color = BatteryColor,
    )
}

@Composable
fun InfoCard(text: String, color: Color = MaterialTheme.colorScheme.secondaryContainer) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = color),
    ) {
        Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Simple line chart; each series is a list of (x, y) with x in epoch ms. */
@Composable
fun LineChart(
    series: List<Pair<Color, List<Pair<Long, Float>>>>,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    zeroLine: Boolean = true,
) {
    val axis = MaterialTheme.colorScheme.outline
    Canvas(modifier.fillMaxWidth().height(height)) {
        val all = series.flatMap { it.second }
        if (all.size < 2) return@Canvas
        val minX = all.minOf { it.first }
        val maxX = all.maxOf { it.first }.coerceAtLeast(minX + 1)
        var minY = all.minOf { it.second }
        var maxY = all.maxOf { it.second }
        if (zeroLine) {
            minY = minOf(minY, 0f)
            maxY = maxOf(maxY, 0f)
        }
        if (maxY - minY < 1f) maxY = minY + 1f
        fun x(v: Long) = (v - minX).toFloat() / (maxX - minX) * size.width
        fun y(v: Float) = size.height - (v - minY) / (maxY - minY) * size.height
        if (zeroLine) drawLine(axis, Offset(0f, y(0f)), Offset(size.width, y(0f)), strokeWidth = 1f)
        for ((color, points) in series) {
            if (points.size < 2) continue
            val path = Path()
            points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.first), y(p.second)) else path.lineTo(x(p.first), y(p.second)) }
            drawPath(path, color, style = Stroke(width = 3f))
        }
    }
}

/** Grouped bar chart: one group per label, bars stacked side by side. */
@Composable
fun BarChart(groups: List<List<Pair<Color, Float>>>, modifier: Modifier = Modifier, height: Dp = 160.dp) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (groups.isEmpty()) return@Canvas
        val maxV = groups.flatten().maxOfOrNull { it.second }?.coerceAtLeast(0.001f) ?: return@Canvas
        val groupW = size.width / groups.size
        groups.forEachIndexed { gi, bars ->
            if (bars.isEmpty()) return@forEachIndexed
            val barW = groupW * 0.8f / bars.size
            bars.forEachIndexed { bi, (color, v) ->
                val h = v / maxV * size.height
                drawRect(
                    color,
                    topLeft = Offset(gi * groupW + groupW * 0.1f + bi * barW, size.height - h),
                    size = androidx.compose.ui.geometry.Size(barW * 0.9f, h),
                )
            }
        }
    }
}

@Composable
fun Legend(vararg items: Pair<Color, String>) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((c, label) in items) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.width(10.dp).height(10.dp)) { drawRect(c) }
                Spacer(Modifier.width(4.dp))
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private val de = Locale.GERMANY
fun watts(w: Int?) = if (w == null) "–" else "$w W"
fun kwh(wh: Double?) = if (wh == null) "–" else String.format(de, "%.2f kWh", wh / 1000.0)
fun euro(v: Double?) = if (v == null) "–" else String.format(de, "%.2f €", v)
fun pct(v: Double?) = if (v == null) "–" else String.format(de, "%.0f %%", v)
fun time(ms: Long?) = if (ms == null) "–" else SimpleDateFormat("dd.MM. HH:mm", de).format(Date(ms))
fun ago(ms: Long?, now: Long = System.currentTimeMillis()): String {
    if (ms == null) return "nie"
    val s = (now - ms) / 1000
    return when {
        s < 60 -> "vor $s s"
        s < 3600 -> "vor ${s / 60} min"
        s < 86400 -> "vor ${s / 3600} h"
        else -> time(ms)
    }
}
