package com.aspirelending.jobcards.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aspirelending.jobcards.R

@Composable
fun HomeScreen(userName: String, vm: com.aspirelending.jobcards.AppViewModel, onScan: () -> Unit, onConnect: () -> Unit) {
    val connected by vm.connected.collectAsState(); val failed by vm.failed.collectAsState()
    val dups by vm.duplicates.collectAsState()
    dups.firstOrNull()?.let { c ->
        AlertDialog(onDismissRequest = {}, title = { Text("Already in Drive") },
            text = { Text("A job card named ${c.registration}.jpeg already exists in the destination folder.") },
            confirmButton = { TextButton({ vm.resolve(c, "replace") }) { Text("Replace") } },
            dismissButton = { Row { TextButton({ vm.resolve(c, "new") }) { Text("Keep both") }; TextButton({ vm.resolve(c, "cancel") }) { Text("Cancel") } } })
    }
    val today by vm.today.collectAsState(); val pending by vm.pending.collectAsState()
    val uploaded by vm.uploaded.collectAsState(); val recent by vm.recent.collectAsState()
    val busy by vm.processing.collectAsState(); val notice by vm.notice.collectAsState()
    Scaffold { pad ->
        Column(Modifier.padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(painterResource(R.drawable.aspire_icon), null, Modifier.size(44.dp))
                Column {
                    Text("ASPIRE JOBCARDS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Hi $userName · Drive: ${if (connected) "connected" else "not connected"}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Button(onScan, Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Default.DocumentScanner, null); Spacer(Modifier.width(10.dp)); Text("SCAN JOB CARD")
            }
            OutlinedButton(onScan, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(10.dp)); Text("IMPORT PHOTOS")
            }
            Text("Today", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("Captured" to today, "Uploaded" to uploaded, "Pending" to pending).forEach { (it, n) ->
                    Card(Modifier.weight(1f)) { Column(Modifier.padding(12.dp)) {
                        Text("$n", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(it, style = MaterialTheme.typography.labelSmall) } }
                }
            }
            if (!connected) FilledTonalButton(onConnect, Modifier.fillMaxWidth()) { Text("Connect Google Drive") }
            else if (pending + failed > 0) TextButton(vm::uploadNow) { Text("Upload now ($pending pending, $failed failed)") }
            notice?.let { n -> TextButton({ vm.notice.value = null }) { Text("$n  (dismiss)") } }
            busy?.let { Text("Processing ${it.first} of ${it.second}…", style = MaterialTheme.typography.bodyMedium) }
            if (recent.isEmpty()) {
                Text("No job cards yet", style = MaterialTheme.typography.titleSmall)
                Text("Scan your first job card. It is cleaned, named by registration and filed.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Recent", style = MaterialTheme.typography.titleSmall)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(recent.take(20)) { c ->
                        val fmt = java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault())
                        ListItem(headlineContent = { Text(c.registration, fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text((c.error ?: c.status.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }) + " · " + fmt.format(c.createdAt)) })
                    }
                }
            }
        }
    }
}
