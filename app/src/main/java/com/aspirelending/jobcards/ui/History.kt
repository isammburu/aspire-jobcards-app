package com.aspirelending.jobcards.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.aspirelending.jobcards.AppViewModel
import com.aspirelending.jobcards.core.RegistrationDetector
import com.aspirelending.jobcards.data.CardStatus
import com.aspirelending.jobcards.data.JobCardEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun HistoryScreen(vm: AppViewModel) {
    val items by vm.history.collectAsState(); val q by vm.query.collectAsState(); val f by vm.filter.collectAsState()
    var sel by remember { mutableStateOf<JobCardEntity?>(null) }
    val fmt = remember { java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault()) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(q, { vm.query.value = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search registration, customer, job card, IMEI") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("" to "All", CardStatus.UPLOADED.name to "Uploaded", CardStatus.PENDING_UPLOAD.name to "Pending", CardStatus.UPLOAD_FAILED.name to "Failed").forEach { (k, l) ->
                FilterChip(f == k, { vm.filter.value = k }, { Text(l) })
            }
        }
        if (q.isNotBlank() && items.isNotEmpty()) Text("${items.size} job card${if (items.size > 1) "s" else ""} for \"$q\"", style = MaterialTheme.typography.labelMedium)
        if (items.isEmpty()) Text("No job cards found.", style = MaterialTheme.typography.bodyMedium)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(items, key = { it.id }) { c ->
                ListItem(modifier = Modifier.clip(MaterialTheme.shapes.medium).clickableItem { sel = c },
                    leadingContent = { Thumb(c.filePath) },
                    headlineContent = { Text(c.registration, fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text((c.jobCardNo?.let { "#$it · " } ?: "") + (c.customer?.let { "$it · " } ?: "") + (c.error ?: c.status.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }) + " · " + fmt.format(c.createdAt)) })
            }
        }
    }
    sel?.let { c -> CardActions(c, vm) { sel = null } }
}

private fun Modifier.clickableItem(onClick: () -> Unit) = this.then(Modifier.clickable(onClick = onClick))

@Composable
private fun Thumb(path: String) {
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 8 })?.asImageBitmap() }
    }
    bmp?.let { Image(it, null, Modifier.size(52.dp).clip(MaterialTheme.shapes.small), contentScale = ContentScale.Crop) } ?: Spacer(Modifier.size(52.dp))
}

@Composable
private fun CardActions(c: JobCardEntity, vm: AppViewModel, close: () -> Unit) {
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    if (editing) {
        var t by remember { mutableStateOf(c.registration) }
        AlertDialog(onDismissRequest = { editing = false }, title = { Text("Edit registration") },
            text = { OutlinedTextField(t, { t = it.uppercase() }, singleLine = true, label = { Text("Vehicle registration") }) },
            confirmButton = { TextButton({ vm.editRegistration(c, t); editing = false; close() }, enabled = t.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton({ editing = false }) { Text("Cancel") } })
    }
    AlertDialog(onDismissRequest = close, title = { Text(c.registration) }, confirmButton = { TextButton(close) { Text("Close") } },
        text = {
            Column {
                TextButton({ editing = true }) { Text("Edit registration") }
                TextButton({ vm.reprocess(c); close() }) { Text("Reprocess") }
                TextButton({
                    val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
                    val out = File(dir, RegistrationDetector.sanitizeFilename(c.registration)); File(c.filePath).copyTo(out, true)
                    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", out)
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/jpeg")
                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share job card"))
                }) { Text("Share") }
                c.driveFileId?.let { id -> TextButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://drive.google.com/file/d/$id/view"))) }) { Text("Open in Drive") } }
                if (c.status == CardStatus.UPLOAD_FAILED || c.status == CardStatus.PENDING_UPLOAD) TextButton({ vm.uploadNow(); close() }) { Text("Retry upload") }
                if (c.status == CardStatus.UPLOADED) TextButton({ vm.deleteLocal(c); close() }) { Text("Remove from this phone") }
            }
        })
}
