package com.aspirelending.jobcards.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.aspirelending.jobcards.Draft
import com.aspirelending.jobcards.core.RegistrationDetector
import com.aspirelending.jobcards.core.Tier

@Composable
fun ReviewScreen(d: Draft, remaining: Int, onConfirm: (String) -> Unit, onDiscard: () -> Unit) {
    var reg by remember(d.id) { mutableStateOf(d.best?.value ?: "") }
    val bmp = remember(d.id) { BitmapFactory.decodeFile(d.path, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap() }
    val (msg, ok) = when (d.tier) {
        Tier.HIGH -> "Registration detected: High confidence" to true
        Tier.MEDIUM -> "We detected ${d.best?.value}. Please check it." to false
        Tier.LOW -> "We couldn't confidently read the registration. Pick a suggestion or type it." to false
    }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Review job card" + if (remaining > 1) " (${remaining - 1} more)" else "", style = MaterialTheme.typography.titleMedium)
        d.alreadyFiled?.let { Text("! This photo was already filed as $it.", style = MaterialTheme.typography.bodySmall) }
        if (!d.jobCardLike) Text("! This doesn't look like a job card. You can still save it.", style = MaterialTheme.typography.bodySmall)
        if (d.sharpness < com.aspirelending.jobcards.core.Quality.BLUR_THRESHOLD)
            Text("! Image looks blurry, so the registration may be misread. Retake if it looks wrong.", style = MaterialTheme.typography.bodySmall)
        bmp?.let { Image(it, "Processed job card", Modifier.fillMaxWidth().heightIn(max = 380.dp)) }
        Text((if (ok) "✓ " else "! ") + msg, style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(reg, { reg = it.uppercase() }, label = { Text("Vehicle registration") }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), modifier = Modifier.fillMaxWidth())
        if (d.candidates.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(d.candidates.take(5)) { c -> AssistChip({ reg = c.value }, { Text(c.value) }) }
        }
        listOf("Job card #" to d.fields.jobCardNo, "Customer" to d.fields.customer, "Phone" to d.fields.phone, "IMEI" to d.fields.imei, "Date" to d.fields.date)
            .filter { it.second != null }.takeIf { it.isNotEmpty() }?.let { l ->
                Text(l.joinToString("  ·  ") { "${it.first} ${it.second}" }, style = MaterialTheme.typography.bodySmall)
            }
        Text("Filename: ${if (reg.isBlank()) "—" else RegistrationDetector.sanitizeFilename(reg)}", style = MaterialTheme.typography.bodySmall)
        Button({ onConfirm(reg) }, enabled = reg.isNotBlank(), modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
            Text("CONFIRM & SAVE")
        }
        TextButton(onDiscard, Modifier.fillMaxWidth()) { Text(if (d.sharpness < com.aspirelending.jobcards.core.Quality.BLUR_THRESHOLD) "Discard and retake" else "Discard") }
    }
}
