package com.aspirelending.jobcards.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aspirelending.jobcards.R
import kotlinx.coroutines.delay

/** Sci-fi reveal: grid boots up, a scan beam sweeps down revealing the logo, then the greeting types in. */
@Composable
fun SplashScreen(userName: String, onFinished: () -> Unit) {
    val grid = remember { Animatable(0f) }
    val scan = remember { Animatable(0f) }      // 0..1 beam position
    val glow = remember { Animatable(0f) }
    val wordmark = remember { Animatable(0f) }
    var typed by remember { mutableIntStateOf(0) }
    val greeting = "Hi, $userName. Welcome back."

    LaunchedEffect(Unit) {
        grid.animateTo(1f, tween(600))
        scan.animateTo(1f, tween(1300, easing = FastOutSlowInEasing))
        glow.animateTo(1f, tween(500))
        wordmark.animateTo(1f, tween(500))
        for (i in 1..greeting.length) { typed = i; delay(35) }
        delay(900)
        onFinished()
    }
    val pulse by rememberInfiniteTransition(label = "p").animateFloat(
        0.6f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "pulse")

    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF0A3A1C), Aspire.Night))),
        contentAlignment = Alignment.Center) {
        // HUD grid
        Canvas(Modifier.fillMaxSize().alpha(grid.value * 0.35f)) {
            val step = 40.dp.toPx()
            var x = 0f; while (x < size.width) { drawLine(Aspire.Mid, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
            var y = 0f; while (y < size.height) { drawLine(Aspire.Mid, Offset(0f, y), Offset(size.width, y), 1f); y += step }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            var h by remember { mutableIntStateOf(1) }
            Box(Modifier.size(220.dp).onSizeChanged { h = it.height }) {
                // glow behind
                Canvas(Modifier.fillMaxSize().alpha(glow.value * pulse * 0.6f)) {
                    drawCircle(Brush.radialGradient(listOf(Aspire.Bright.copy(alpha = .5f), Color.Transparent)))
                }
                Image(painterResource(R.drawable.aspire_icon), "Aspire Lending",
                    Modifier.fillMaxSize().drawWithContent {
                        val y = size.height * scan.value
                        clipRect(bottom = y) { this@drawWithContent.drawContent() }
                        if (scan.value < 1f) { // scan beam
                            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Aspire.Bright, Color.Transparent),
                                startY = y - 24f, endY = y + 24f), topLeft = Offset(-40f, y - 24f),
                                size = androidx.compose.ui.geometry.Size(size.width + 80f, 48f))
                        }
                    })
            }
            Spacer(Modifier.height(28.dp))
            Text("ASPIRE JOBCARDS", color = Aspire.Bright, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 6.sp, modifier = Modifier.alpha(wordmark.value))
            Text("Capture · Read · File", color = Color(0xFF7FA088), fontSize = 12.sp, letterSpacing = 3.sp,
                modifier = Modifier.alpha(wordmark.value).padding(top = 4.dp))
            Spacer(Modifier.height(36.dp))
            Text(greeting.take(typed) + if (typed < greeting.length) "▌" else "", color = Color(0xFFE6F2E9),
                fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.heightIn(min = 28.dp))
        }
    }
}
