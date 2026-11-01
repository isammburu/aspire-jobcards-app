package com.aspirelending.jobcards

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.aspirelending.jobcards.data.Prefs
import com.aspirelending.jobcards.drive.Auth
import com.aspirelending.jobcards.drive.UploadWorker
import com.aspirelending.jobcards.ui.*
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scan()
        else Toast.makeText(this, "Camera permission is required to scan job cards.", Toast.LENGTH_LONG).show()
    }

    private val launcher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val imageUris = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages?.map { it.imageUri }
            ?.takeIf { !it.isNullOrEmpty() } ?: return@registerForActivityResult
        vm.process(imageUris, "Camera/Scanner")
    }

    private val authLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK) onDriveConnected()
    }

    private fun onDriveConnected() {
        Prefs.setConnected(this, true)
        UploadWorker.enqueue(this)
    }

    private fun connectDrive() {
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) != ConnectionResult.SUCCESS) {
            Toast.makeText(this, "Google Play services are required for Drive sync.", Toast.LENGTH_LONG).show()
            return
        }

        Auth.authorize(this)
            .addOnSuccessListener { res ->
                val pi = res.pendingIntent
                if (res.hasResolution() && pi != null) {
                    authLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } else {
                    onDriveConnected()
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Could not connect to Google Drive. Please try again.", Toast.LENGTH_LONG).show()
            }
    }

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun requestCameraIfNeeded() {
        if (hasCameraPermission()) scan()
        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun scan() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(20)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { intent ->
                launcher.launch(IntentSenderRequest.Builder(intent).build())
            }
            .addOnFailureListener {
                Toast.makeText(this, "Document scanner is unavailable on this device.", Toast.LENGTH_LONG).show()
            }
    }

    private fun handleShare(intent: Intent?) {
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { listOf(it) } ?: emptyList()
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) vm.process(uris, "Share")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        if (savedInstanceState == null) handleShare(intent)

        setContent {
            AspireTheme {
                val userName by Prefs.userName.collectAsState()
                var splash by rememberSaveable { mutableStateOf(true) }
                val drafts by vm.drafts.collectAsState()
                val onboarded by Prefs.onboarded.collectAsState()

                Crossfade(splash, label = "root") { s ->
                    if (s) {
                        SplashScreen(userName) { splash = false }
                    } else if (!onboarded) {
                        Onboarding(::connectDrive) { Prefs.setOnboarded(this@MainActivity, true) }
                    } else {
                        drafts.firstOrNull()?.let { draft ->
                            ReviewScreen(draft, drafts.size, { vm.confirm(draft, it) }, { vm.discard(draft) }, { vm.useOriginal(draft) })
                        } ?: Shell(userName, vm, ::requestCameraIfNeeded, ::connectDrive)
                    }
                }
            }
        }
    }
}
