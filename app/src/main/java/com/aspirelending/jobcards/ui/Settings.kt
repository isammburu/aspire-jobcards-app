package com.aspirelending.jobcards.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aspirelending.jobcards.AppViewModel
import com.aspirelending.jobcards.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(vm: AppViewModel, onConnect: () -> Unit) {
    val ctx = LocalContext.current
    val connected by vm.connected.collectAsState(); val name by Prefs.userName.collectAsState()
    val wifi by Prefs.wifiOnly.collectAsState(); val test by vm.testResult.collectAsState()
    var folder by remember { mutableStateOf("") }; var folderMsg by remember { mutableStateOf<String?>(null) }
    var nm by remember(name) { mutableStateOf(name) }
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        Text("Profile", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(nm, { nm = it; if (it.isNotBlank()) Prefs.setName(ctx, it.trim()) }, label = { Text("Your name (shown on welcome)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Google Drive", style = MaterialTheme.typography.titleSmall)
        Text(if (connected) "Connected. Files go to the Aspire job-cards folder." else "Not connected.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!connected) Button(onConnect) { Text("Connect") }
            else { OutlinedButton(vm::testConnection) { Text("Test connection") }; OutlinedButton({ Prefs.setConnected(ctx, false) }) { Text("Disconnect") } }
        }
        test?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        OutlinedTextField(folder, { folder = it; folderMsg = null }, label = { Text("Change folder (paste Drive link or ID)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button({ folderMsg = if (Prefs.setFolder(ctx, folder)) { folder = ""; "Folder updated." } else "That doesn't look like a Drive folder link." }, enabled = folder.isNotBlank()) { Text("Save folder") }
            folderMsg?.let { Spacer(Modifier.width(10.dp)); Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Upload on Wi-Fi only"); Text("Saves mobile data. Cards wait until Wi-Fi.", style = MaterialTheme.typography.bodySmall) }
            Switch(wifi, { Prefs.setWifiOnly(ctx, it) })
        }
        Text("Diagnostics", style = MaterialTheme.typography.titleSmall)
        val ms by vm.lastProcessMs.collectAsState(); val pend by vm.pending.collectAsState(); val fail by vm.failed.collectAsState(); val up by vm.uploaded.collectAsState()
        val bytes by produceState(0L) { value = withContext(Dispatchers.IO) { vm.storageBytes() } }
        Text("Reading: ML Kit on-device (scanner and text recognition)\nLast processing time: ${ms?.let { "%.1f s".format(it / 1000.0) } ?: "n/a"}\n" +
            "Uploads: $up done, $pend pending, $fail failed\nStorage used by saved cards: ${"%.1f".format(bytes / 1048576.0)} MB\nDrive: ${if (connected) "connected" else "not connected"}", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(vm::clearTemp) { Text("Clear temporary files") }
        Text("Aspire JobCards v0.1.0", style = MaterialTheme.typography.labelSmall)
    }
}
