package com.aspirelending.jobcards

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aspirelending.jobcards.core.*
import com.aspirelending.jobcards.data.*
import com.aspirelending.jobcards.drive.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class Draft(
    val id: String,
    val path: String,
    val candidates: List<RegCandidate>,
    val source: String,
    val fields: JobFields = JobFields(),
    val jobCardLike: Boolean = true,
    val sharpness: Float = 999f,
    val hash: String? = null,
    val alreadyFiled: String? = null,
    val original: String? = null,
) {
    val best get() = candidates.firstOrNull()
    val tier get() = best?.let { RegistrationDetector.tier(it.score) } ?: Tier.LOW
}

private const val SCANNER = "Camera/Scanner"

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val app = app
    private val dao = AppDb.get(app).cards()

    init {
        Prefs.init(app)
        recoverOrphans()
        UploadWorker.enqueue(app)
    }

    val connected = Prefs.connected
    val query = MutableStateFlow("")
    val filter = MutableStateFlow("")

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val history = combine(query, filter) { q, f -> q.trim() to f }
        .flatMapLatest { (q, f) -> dao.search(q, f) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val testResult = MutableStateFlow<String?>(null)

    fun testConnection() = viewModelScope.launch {
        testResult.value = "Testing…"
        testResult.value = withContext(Dispatchers.IO) {
            runCatching {
                val r = Auth.authorize(app).await()
                if (r.hasResolution() || r.accessToken == null) {
                    "Please reconnect Google Drive."
                } else {
                    val token = r.accessToken ?: return@withContext "Please reconnect Google Drive."
                    "Connected to folder: " + DriveApi(token).folderName(Prefs.folderId(app))
                }
            }.getOrElse {
                if (it is DriveHttpException && it.code in listOf(403, 404)) {
                    "This Google account can't access the configured folder."
                } else {
                    "Couldn't reach Google Drive. Check your internet connection."
                }
            }
        }
    }

    fun deleteLocal(c: JobCardEntity) = viewModelScope.launch {
        File(c.filePath).delete()
        DocumentCleaner.originalFor(c.filePath).delete()
        dao.delete(c.id)
    }

    val duplicates = dao.duplicates().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val failed = dao.count(CardStatus.UPLOAD_FAILED).stateIn(viewModelScope, SharingStarted.Lazily, 0)
    private val dir = File(app.filesDir, "cards").apply { mkdirs() }
    val drafts = MutableStateFlow<List<Draft>>(emptyList())
    val processing = MutableStateFlow<Pair<Int, Int>?>(null)
    val recent = dao.recent().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val today = dao.capturedSince(java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)
    val pending = dao.count(CardStatus.PENDING_UPLOAD).stateIn(viewModelScope, SharingStarted.Lazily, 0)
    val uploaded = dao.count(CardStatus.UPLOADED).stateIn(viewModelScope, SharingStarted.Lazily, 0)

    val notice = MutableStateFlow<String?>(null)
    val lastProcessMs = MutableStateFlow<Long?>(null)

    fun process(uris: List<Uri>, source: String) = viewModelScope.launch {
        val ctx = getApplication<Application>()
        processing.value = 0 to uris.size
        var skipped = 0

        uris.forEachIndexed { index, uri ->
            val id = UUID.randomUUID().toString()
            val file = File(dir, "$id.jpeg")
            val own = source != SCANNER
            val raw = if (own) File(dir, "$id.orig.jpg") else file

            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    val input = ctx.contentResolver.openInputStream(uri) ?: return@withContext false
                    raw.outputStream().use { output -> input.copyTo(output) }
                    readable(raw)
                }
            }.getOrDefault(false)

            if (!ok) {
                raw.delete()
                skipped++
            } else {
                if (own) prepare(raw, file)
                analyze(file, id, source)
            }

            processing.value = (index + 1) to uris.size
        }

        processing.value = null
        if (skipped > 0) {
            notice.value = "$skipped photo${if (skipped > 1) "s" else ""} couldn't be read and ${if (skipped > 1) "were" else "was"} skipped."
        }
    }

    private suspend fun prepare(raw: File, out: File) {
        val res = runCatching { DocumentCleaner.clean(raw, out) }.getOrNull()
        if (res == null) {
            out.delete()
            val ok = runCatching { DocumentCleaner.exportOriginal(raw, out) }.getOrDefault(false)
            if (!ok) {
                out.delete()
                runCatching { raw.copyTo(out, true) }
            }
            if (out.exists()) raw.delete()
        }
    }

    private fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readable(f: File): Boolean {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.path, opts)
        return f.length() > 0 && opts.outWidth >= 200 && opts.outHeight >= 200
    }

    private suspend fun analyze(file: File, id: String, source: String) {
        val ctx = getApplication<Application>()
        val t0 = System.currentTimeMillis()

        val lines = runCatching { Ocr.read(ctx, Uri.fromFile(file)) }.getOrDefault(emptyList())
        val lines2 = runCatching { Quality.recognitionVariant(file.path)?.let { bitmap -> Ocr.readBitmap(bitmap).also { bitmap.recycle() } } }.getOrNull().orEmpty()
        val candidates = runCatching {
            RegistrationDetector.merge(listOf(RegistrationDetector.detect(lines), RegistrationDetector.detect(lines2)))
        }.getOrDefault(emptyList())

        val fields = runCatching { FieldExtractor.extract(lines.ifEmpty { lines2 }) }.getOrDefault(JobFields())
        val sharp = runCatching { Quality.sharpness(file.path) }.getOrDefault(999f)
        val original = DocumentCleaner.originalFor(file.path).takeIf { it.exists() }
        val hash = runCatching { sha256(original ?: file) }.getOrNull()
        val duplicate = hash?.let { h -> runCatching { dao.byHash(h) }.getOrNull() }?.takeIf { it.id != id }?.registration

        drafts.update {
            it + Draft(
                id = id,
                path = file.path,
                candidates = candidates,
                source = source,
                fields = fields,
                jobCardLike = (lines + lines2).isEmpty() || FieldExtractor.looksLikeJobCard(lines + lines2),
                sharpness = sharp,
                hash = hash,
                alreadyFiled = duplicate,
                original = original?.path
            )
        }

        lastProcessMs.value = System.currentTimeMillis() - t0
    }

    private fun recoverOrphans() {
        val files = dir.listFiles { file -> file.extension == "jpeg" }?.toList().orEmpty()
        val unfinished = dir.listFiles { file -> file.name.endsWith(".orig.jpg") }
            ?.filter { !File(dir, it.name.removeSuffix(".orig.jpg") + ".jpeg").exists() }
            .orEmpty()

        if (files.isEmpty() && unfinished.isEmpty()) return

        viewModelScope.launch {
            val known = dao.ids().toSet()

            unfinished.filter { it.name.removeSuffix(".orig.jpg") !in known && readable(it) }
                .sortedBy { it.lastModified() }
                .forEach { original ->
                    val id = original.name.removeSuffix(".orig.jpg")
                    val output = File(dir, "$id.jpeg")
                    prepare(original, output)
                    if (output.exists()) analyze(output, id, "Recovered")
                }

            files.filter { it.nameWithoutExtension !in known && readable(it) }
                .sortedBy { it.lastModified() }
                .forEach { file -> analyze(file, file.nameWithoutExtension, "Recovered") }
        }
    }

    fun confirm(draft: Draft, reg: String) = viewModelScope.launch {
        val clean = reg.trim().uppercase().replace(Regex("\\s+"), " ")
        val existing = dao.byId(draft.id)

        dao.upsert(
            if (existing != null) existing.copy(
                registration = clean,
                rawRegistration = draft.best?.value,
                registrationConfidence = draft.best?.score ?: 0f,
                driveName = null,
                error = null,
                status = if (existing.status == CardStatus.UPLOADED && existing.registration != clean) CardStatus.PENDING_UPLOAD else existing.status,
                jobCardNo = draft.fields.jobCardNo ?: existing.jobCardNo,
                customer = draft.fields.customer ?: existing.customer,
                phone = draft.fields.phone ?: existing.phone,
                imei = draft.fields.imei ?: existing.imei,
                jobDate = draft.fields.date ?: existing.jobDate
            ) else JobCardEntity(
                id = draft.id,
                registration = clean,
                rawRegistration = draft.best?.value,
                registrationConfidence = draft.best?.score ?: 0f,
                filePath = draft.path,
                source = draft.source,
                jobCardNo = draft.fields.jobCardNo,
                customer = draft.fields.customer,
                phone = draft.fields.phone,
                imei = draft.fields.imei,
                jobDate = draft.fields.date,
                hash = draft.hash
            )
        )

        drafts.update { it - draft }
        UploadWorker.enqueue(app)
    }

    fun reprocess(card: JobCardEntity) = viewModelScope.launch { analyze(File(card.filePath), card.id, "Reprocess") }

    fun editRegistration(card: JobCardEntity, reg: String) = viewModelScope.launch {
        val clean = reg.trim().uppercase().replace(Regex("\\s+"), " ")
        if (clean.isBlank()) return@launch
        dao.editReg(card.id, clean)
        UploadWorker.enqueue(app)
    }

    fun storageBytes(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun clearTemp() { File(app.cacheDir, "share").deleteRecursively() }
    fun uploadNow() = UploadWorker.enqueue(app)

    fun resolve(card: JobCardEntity, action: String) = viewModelScope.launch {
        when (action) {
            "replace" -> dao.resolve(card.id, card.duplicateOfId, card.driveName)
            "new" -> dao.resolve(card.id, null, RegistrationDetector.sanitizeFilename(card.registration).removeSuffix(".jpeg") + " (2).jpeg")
            else -> dao.setStatus(card.id, CardStatus.UPLOAD_FAILED, "Held: a file with this registration already exists in Drive.")
        }
        if (action != "cancel") UploadWorker.enqueue(app)
    }

    fun discard(draft: Draft) = viewModelScope.launch {
        if (dao.byId(draft.id) == null) {
            File(draft.path).delete()
            draft.original?.let { File(it).delete() }
        }
        drafts.update { it - draft }
    }

    fun useOriginal(draft: Draft) = viewModelScope.launch {
        val original = draft.original?.let { File(it) } ?: return@launch
        val tmp = File(dir, "${draft.id}.tmp")
        if (DocumentCleaner.exportOriginal(original, tmp) && tmp.length() > 0) {
            tmp.copyTo(File(draft.path), true)
            tmp.delete()
            original.delete()
            drafts.update { list -> list.map { if (it.id == draft.id) it.copy(original = null) else it } }
        } else {
            tmp.delete()
        }
    }
}
