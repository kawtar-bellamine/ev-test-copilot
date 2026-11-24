package fr.artsetmetiers.evtestcopilot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.artsetmetiers.evtestcopilot.Source
import fr.artsetmetiers.evtestcopilot.UiState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

val Traction = Color(0xFF4FC3F7)
val Regen = Color(0xFF66BB6A)
val Warn = Color(0xFFFFB74D)
private val Bg = Color(0xFF0E1217)
private val CardBg = Color(0xFF181E26)
private val Muted = Color(0xFF8A96A3)

private val Scheme = darkColorScheme(
    primary = Traction,
    secondary = Regen,
    background = Bg,
    surface = CardBg,
    onBackground = Color(0xFFE8EDF2),
    onSurface = Color(0xFFE8EDF2),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Scheme, content = content)

private fun f1(x: Double) = String.format(Locale.FRANCE, "%.1f", x)
private fun f0(x: Double) = String.format(Locale.FRANCE, "%.0f", x)
private val gears = mapOf(0 to "P", 1 to "R", 2 to "N", 3 to "D")

@Composable
fun Dashboard(
    s: UiState,
    onDemo: () -> Unit,
    onBluetooth: () -> Unit,
    onMic: () -> Unit,
    onMark: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(s, onDemo, onBluetooth)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Big("Vitesse", f0(s.speedKmh), "km/h", Modifier.weight(1f), sub = "Rapport ${gears[s.gear] ?: "?"}")
            val regen = s.powerKw < 0
            Big(
                if (regen) "Régénération" else "Traction", f0(abs(s.powerKw)), "kW", Modifier.weight(1f),
                color = if (regen) Regen else Traction,
            )
        }
        Battery(s)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Small("Consommation", f0(s.whPerKm), "Wh/km", Modifier.weight(1f))
            Small("Autonomie", f0(s.rangeKm), "km", Modifier.weight(1f))
            Small("Distance", f1(s.distanceKm), "km", Modifier.weight(1f))
        }
        EnergyCard(s)
        PowerChart(s.powerHistory)
        VoiceCard(s, onMic, onMark)
        if (s.events.isNotEmpty()) EventsCard(s)
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
    ) { Box(Modifier.padding(14.dp)) { content() } }
}

@Composable
private fun Label(text: String) = Text(text.uppercase(), color = Muted, fontSize = 11.sp, letterSpacing = 1.sp)

@Composable
private fun Header(s: UiState, onDemo: () -> Unit, onBluetooth: () -> Unit) {
    Column {
        Text("EV Test Copilot", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Scheme.onBackground)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (s.linkOk) Regen else Warn, CircleShape))
            Spacer(Modifier.size(6.dp))
            Text(s.linkState, color = Muted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBluetooth) { Text("Boîtier Bluetooth") }
            OutlinedButton(onClick = onDemo) { Text(if (s.source == Source.DEMO) "Relancer la démo" else "Démo") }
        }
    }
}

@Composable
private fun Big(
    label: String, value: String, unit: String, modifier: Modifier,
    color: Color = Scheme.onSurface, sub: String? = null,
) = Panel(modifier) {
    Column {
        Label(label)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 44.sp, fontWeight = FontWeight.Bold, color = color)
            Spacer(Modifier.size(4.dp))
            Text(unit, color = Muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (sub != null) Text(sub, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun Small(label: String, value: String, unit: String, modifier: Modifier) = Panel(modifier) {
    Column {
        Label(label)
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Scheme.onSurface)
        Text(unit, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun Battery(s: UiState) = Panel {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Label("Batterie")
            Text("${f1(s.socPct)} %  ·  cellules ${f0(s.tempC)} °C", color = Scheme.onSurface, fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { (s.socPct / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(10.dp),
            color = if (s.socPct < 20) Warn else Regen,
            trackColor = Color(0xFF2A323C),
        )
    }
}

@Composable
private fun EnergyCard(s: UiState) = Panel {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Label("Bilan énergétique")
        Line("Traction", "${f0(s.tractionWh)} Wh", Traction)
        Line("Récupérée", "${f0(s.regenWh)} Wh  (${f0(s.regenRatio * 100)} %)", Regen)
        Line("Nette", "${f0(s.netWh)} Wh", Scheme.onSurface)
        s.boxNetWh?.let { Line("Contrôle boîtier", "${f0(it)} Wh", Muted) }
    }
}

@Composable
private fun Line(k: String, v: String, c: Color) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(k, color = Muted, fontSize = 14.sp)
    Text(v, color = c, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
}

/** Puissance batterie sur les 60 dernières secondes : bleu en traction, vert en régénération. */
@Composable
private fun PowerChart(points: List<Float>) = Panel {
    Column {
        Label("Puissance, 60 dernières secondes")
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val maxAbs = max(20f, points.maxOfOrNull { abs(it) } ?: 20f)
            val zeroY = size.height / 2
            drawLine(Muted.copy(alpha = 0.5f), Offset(0f, zeroY), Offset(size.width, zeroY), strokeWidth = 1f)
            if (points.size < 2) return@Canvas
            val dx = size.width / (points.size - 1)
            fun y(p: Float) = zeroY - p / maxAbs * (size.height / 2)
            for (i in 1 until points.size) {
                val a = points[i - 1]
                val b = points[i]
                drawLine(
                    if ((a + b) / 2 < 0) Regen else Traction,
                    Offset((i - 1) * dx, y(a)), Offset(i * dx, y(b)), strokeWidth = 3f,
                )
            }
        }
    }
}

@Composable
private fun VoiceCard(s: UiState, onMic: () -> Unit, onMark: () -> Unit) = Panel {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Label("Assistant vocal")
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onMic,
            shape = CircleShape,
            modifier = Modifier.size(96.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Traction, contentColor = Bg),
        ) { Text("Parler", fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(8.dp))
        Text(s.voiceState, color = Muted, fontSize = 13.sp)
        if (s.lastQuestion.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("« ${s.lastQuestion} »", color = Muted, fontSize = 13.sp)
            Text(s.lastReply, color = Scheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onMark) { Text("Marquer un événement") }
    }
}

@Composable
private fun EventsCard(s: UiState) = Panel {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Label("Événements")
        s.events.takeLast(5).reversed().forEach { e ->
            Text("${e.tMs / 60000} min ${(e.tMs / 1000) % 60} s  ·  ${e.label}", color = Scheme.onSurface, fontSize = 13.sp)
        }
    }
}
