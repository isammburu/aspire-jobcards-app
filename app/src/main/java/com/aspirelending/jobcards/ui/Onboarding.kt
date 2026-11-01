package com.aspirelending.jobcards.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val pages = listOf(
    "Capture job cards faster" to "Scan a job card. The app crops, straightens and cleans it for you.",
    "Named by registration" to "The vehicle registration is read automatically. You confirm it, and the file is named like KBW 890M.jpeg.",
    "Filed to Google Drive" to "Connect the Google account that can open the Aspire job-cards folder. Cards are saved offline and uploaded when you have internet.")

@Composable
fun Onboarding(onConnect: () -> Unit, onDone: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(pages[step].first, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        Text(pages[step].second, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        if (step < pages.lastIndex) Button({ step++ }, Modifier.fillMaxWidth()) { Text("Next") }
        else {
            Button(onConnect, Modifier.fillMaxWidth()) { Text("Connect Google Drive") }
            TextButton(onDone, Modifier.fillMaxWidth()) { Text("Finish") }
        }
        Text("${step + 1} / ${pages.size}", style = MaterialTheme.typography.labelSmall)
    }
}
