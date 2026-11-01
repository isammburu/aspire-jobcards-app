package com.aspirelending.jobcards.drive

import android.content.Context
import androidx.work.*
import com.aspirelending.jobcards.core.RegistrationDetector
import com.aspirelending.jobcards.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class UploadWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val dao = AppDb.get(applicationContext).cards()
        Prefs.init(applicationContext)
        if (!Prefs.connected.value) return@withContext Result.success()
        val queue = dao.uploadable(); if (queue.isEmpty()) return@withContext Result.success()
        val auth = runCatching { Auth.authorize(applicationContext).await() }.getOrNull()
        if (auth == null || auth.hasResolution() || auth.accessToken == null) {
            Prefs.setConnected(applicationContext, false)
            dao.failAll("Google Drive access expired. Reconnect Google Drive.")
            return@withContext Result.success()
        }
        val api = DriveApi(auth.accessToken!!); val folder = Prefs.folderId(applicationContext)
        var retry = false
        for (c in queue) {
            try {
                dao.setStatus(c.id, CardStatus.UPLOADING)
                val f = File(c.filePath)
                if (!f.exists() || f.length() == 0L) { dao.setStatus(c.id, CardStatus.UPLOAD_FAILED, "Local file is missing or empty."); continue }
                val name = c.driveName ?: RegistrationDetector.sanitizeFilename(c.registration)
                if (c.driveFileId != null) {      // already in Drive: update content and, if the registration was edited, the name
                    val other = api.find(folder, name).firstOrNull { it.id != c.driveFileId }
                    if (other != null) { dao.duplicate(c.id, other.id); continue }
                    api.replace(c.driveFileId, f); api.rename(c.driveFileId, name)
                    dao.uploaded(c.id, c.driveFileId, now()); continue
                }
                val existing = api.find(folder, name)
                val mine = existing.firstOrNull { it.localId == c.id }   // earlier attempt succeeded but reply was lost
                when {
                    mine != null -> dao.uploaded(c.id, mine.id, now())
                    existing.isNotEmpty() -> dao.duplicate(c.id, existing.first().id)
                    else -> dao.uploaded(c.id, api.create(folder, name, f, c.id), now())
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e   // work was replaced or stopped; status stays UPLOADING and the next run resumes it safely
            } catch (e: DriveHttpException) {
                val msg = when (e.code) {
                    401 -> "Google authorization expired. Reconnect Google Drive."
                    403, 404 -> "Google Drive couldn't accept the file. This account may not have access to the job-card folder."
                    else -> "Google Drive error (${e.code}). Will retry."
                }
                dao.setStatus(c.id, CardStatus.UPLOAD_FAILED, msg)
                if (e.code >= 500 || e.code == 429) retry = true
            } catch (e: IOException) {
                dao.setStatus(c.id, CardStatus.PENDING_UPLOAD, "Saved locally. Waiting for internet."); retry = true
            } catch (e: Exception) {
                dao.setStatus(c.id, CardStatus.UPLOAD_FAILED, "Something went wrong while uploading. Tap Retry upload."); retry = true
            }
        }
        if (retry) Result.retry() else Result.success()
    }
    private fun now() = System.currentTimeMillis()

    companion object {
        fun enqueue(c: Context) = Prefs.init(c).let { WorkManager.getInstance(c).enqueueUniqueWork("upload", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (Prefs.wifiOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()) }
    }
}
