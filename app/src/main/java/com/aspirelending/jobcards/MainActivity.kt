package com.aspirelending.jobcards

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.*
import com.aspirelending.jobcards.data.Prefs
import com.aspirelending.jobcards.drive.Auth
import com.aspirelending.jobcards.drive.UploadWorker
import com.aspirelending.jobcards.ui.*
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val launcher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        GmsDocumentScanningResult.fromActivityResultIntent(r.data)?.pages?.map { it.imageUri }
            ?.takeIf { it.isNotEmpty() }?.let { vm.process(it, "Camera/Scanner") }
    }

    private val authLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == RESULT_OK) onDriveConnected()
    }
    private fun onDriveConnected() { Prefs.setConnected(this, true); UploadWorker.enqueue(this) }
    private fun connectDrive() {
        Auth.authorize(this).addOnSuccessListener { res ->
            val pi = res.pendingIntent
            if (res.hasResolution() && pi != null) authLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            else onDriveConnected()
        }
    }

    private fun scan() {
        val o = GmsDocumentScannerOptions.Builder().setGalleryImportAllowed(true).setPageLimit(20)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL).build()
        GmsDocumentScanning.getClient(o).getStartScanIntent(this)
            .addOnSuccessListener { launcher.launch(IntentSenderRequest.Builder(it).build()) }
    }

    private fun handleShare(i: Intent?) {
        val uris = when (i?.action) {
            Intent.ACTION_SEND -> listOfNotNull(i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) vm.process(uris, "Share")
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleShare(intent) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            AspireTheme {
                val userName by Prefs.userName.collectAsState()
                var splash by rememberSaveable { mutableStateOf(true) }
                val drafts by vm.drafts.collectAsState(); val onboarded by Prefs.onboarded.collectAsState()
                Crossfade(splash, label = "root") { s ->
                    if (s) SplashScreen(userName) { splash = false }
                    else if (!onboarded) Onboarding(::connectDrive) { Prefs.setOnboarded(this@MainActivity, true) }
                    else drafts.firstOrNull()?.let { d ->
                        ReviewScreen(d, drafts.size, { vm.confirm(d, it) }, { vm.discard(d) })
                    } ?: Shell(userName, vm, ::scan, ::connectDrive)
                }
            }
        }
    }
}
