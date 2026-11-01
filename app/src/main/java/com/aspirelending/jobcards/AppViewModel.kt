package com.aspirelending.jobcards

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aspirelending.jobcards.core.*
import com.aspirelending.jobcards.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import com.aspirelending.jobcards.drive.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.aspirelending.jobcards.drive.UploadWorker
import java.io.File
import java.util.UUID

data class Draft(val id: String, val path: String, val candidates: List<RegCandidate>, val source: String,
                 val fields: JobFields = JobFields(), val jobCardLike: Boolean = true, val sharpness: Float = 999f,
                 val hash: String? = null, val alreadyFiled: String? = null,
                 val original: String? = null) {
    val best get() = candidates.firstOrNull()
    val tier get() = best?.let { RegistrationDetector.tier(it.score) } ?: Tier.LOW
}

private const val SCANNER = "Camera/Scanner"   // Google's scanner has already cropped and cleaned these

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val app = app
    private val dao = AppDb.get(app).cards()
    init { Prefs.init(app); recoverOrphans(); UploadWorker.enqueue(app) }   // resume any interrupted uploads
    val connected = Prefs.connected
    val query = MutableStateFlow(""); val filter = MutableStateFlow("")
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val history = combine(query, filter) { q, f -> q.trim() to f }
        .flatMapLatest { (q, f) -> dao.search(q, f) }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val testResult = MutableStateFlow<String?>(null)
    fun testConnection() = viewModelScope.launch {
        testResult.value = "Testing…"
        testResult.value = withContext(Dispatchers.IO) {
            runCatching {
                val r = Auth.authorize(app).await()
                if (r.hasResolution() || r.accessToken == null) "Please reconnect Google Drive."
                else "Connected to folder: " + DriveApi(r.accessToken!!).folderName(Prefs.folderId(app))
            }.getOrElse { if (it is DriveHttpException && it.code in listOf(403, 404)) "This Google account can't access the configured folder." else "Couldn't reach Google Drive. Check your internet." }
        }
    }
    fun deleteLocal(c: JobCardEntity) = viewModelScope.launch { File(c.filePath).delete(); DocumentCleaner.originalFor(c.filePath).delete(); dao.delete(c.id) }
    val duplicates = dao.duplicates().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val failed = dao.count(CardStatus.UPLOAD_FAILED).stateIn(viewModelScope, SharingStarted.Lazily, 0)
    private val dir = File(app.filesDir, "cards").apply { mkdirs() }
    val drafts = MutableStateFlow<List<Draft>>(emptyList())
    val processing = MutableStateFlow<Pair<Int, Int>?>(null) // done, total
    val recent = dao.recent().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val today = dao.capturedSince(java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)
    val pending = dao.count(CardStatus.PENDING_UPLOAD).stateIn(viewModelScope, SharingStarted.Lazily, 0)
    val uploaded = dao.count(CardStatus.UPLOADED).stateIn(viewModelScope, SharingStarted.Lazily, 0)

    /** Scanner pages and shared images both end up here; offline, sequential to keep memory low. */
    val notice = MutableStateFlow<String?>(null)
    val lastProcessMs = MutableStateFlow<Long?>(null)

    /**
     * Scanner pages and shared images both end up here; offline, sequential to keep memory low.
     * Scanner pages are already cropped and cleaned by Google's scanner. Anything else (shared from WhatsApp, Gallery...)
     * is kept untouched as "<id>.orig.jpg" and goes through our own crop / rotate / clean-up to become "<id>.jpeg".
     */
    fun process(uris: List<Uri>, source: String) = viewModelScope.launch {
        val ctx = getApplication<Application>()
        processing.value = 0 to uris.size
        var skipped = 0
        uris.forEachIndexed { i, u ->
            val id = UUID.randomUUID().toString(); val f = File(dir, "$id.jpeg")
            val own = source != SCANNER
            val raw = if (own) File(dir, "$id.orig.jpg") else f
            // the original is stored first, so nothing is lost if the process dies during clean-up or OCR
            val ok = runCatching { withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(u)!!.use { a -> raw.outputStream().use { a.copyTo(it) } }; readable(raw) } }.getOrDefault(false)
            if (!ok) { raw.delete(); skipped++ }
            else { if (own) prepare(raw, f); analyze(f, id, source) }
            processing.value = (i + 1) to uris.size
        }
        processing.value = null
        if (skipped > 0) notice.value = "$skipped photo${if (skipped > 1) "s" else ""} couldn't be read and ${if (skipped > 1) "were" else "was"} skipped."
    }

    /** Build the filed JPEG from an untouched photo. If clean-up fails, the photo itself is filed and the pointless copy is dropped. */
    private suspend fun prepare(raw: File, out: File) {
        val res = runCatching { DocumentCleaner.clean(raw, out) }.getOrNull()
        if (res == null) {
            out.delete()
            val ok = runCatching { DocumentCleaner.exportOriginal(raw, out) }.getOrDefault(false)
            if (!ok) { out.delete(); runCatching { raw.copyTo(out, true) } }
            if (out.exists()) raw.delete()
        }
    }

    private fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(65536); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readable(f: File): Boolean {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.path, o)
        return f.length() > 0 && o.outWidth >= 200 && o.outHeight >= 200
    }

    /** OCR + detection. Every step is guarded: a failure still yields a draft the user can finish by typing the registration. */
    private suspend fun analyze(f: File, id: String, source: String) {
        val ctx = getApplication<Application>(); val t0 = System.currentTimeMillis()
        val lines = runCatching { Ocr.read(ctx, Uri.fromFile(f)) }.getOrDefault(emptyList())
        val lines2 = runCatching { Quality.recognitionVariant(f.path)?.let { bm -> Ocr.readBitmap(bm).also { bm.recycle() } } }.getOrNull().orEmpty()
        val cands = runCatching { RegistrationDetector.merge(listOf(RegistrationDetector.detect(lines), RegistrationDetector.detect(lines2))) }.getOrDefault(emptyList())
        val fields = runCatching { FieldExtractor.extract(lines.ifEmpty { lines2 }) }.getOrDefault(JobFields())
        val sharp = runCatching { Quality.sharpness(f.path) }.getOrDefault(999f)
        val orig = DocumentCleaner.originalFor(f.path).takeIf { it.exists() }
        val hash = runCatching { sha256(orig ?: f) }.getOrNull()   // hash the photo as received, so the same photo sent twice is caught
        val dupe = hash?.let { h -> runCatching { dao.byHash(h) }.getOrNull() }?.takeIf { it.id != id }?.registration
        drafts.update { it + Draft(id, f.path, cands, source, fields, (lines + lines2).isEmpty() || FieldExtractor.looksLikeJobCard(lines + lines2), sharp, hash, dupe, orig?.path) }
        lastProcessMs.value = System.currentTimeMillis() - t0
    }

    /** If Android killed the app between scanning and confirming, unreviewed photos are still on disk: bring them back to review. */
    private fun recoverOrphans() {
        val files = dir.listFiles { x -> x.extension == "jpeg" }?.toList().orEmpty()   // listed before anything new is written
        // shared photos that were stored but never finished clean-up (the process died in between)
        val unfinished = dir.listFiles { x -> x.name.endsWith(".orig.jpg") }?.filter { !File(dir, it.name.removeSuffix(".orig.jpg") + ".jpeg").exists() }.orEmpty()
        if (files.isEmpty() && unfinished.isEmpty()) return
        viewModelScope.launch {
            val known = dao.ids().toSet()
            unfinished.filter { it.name.removeSuffix(".orig.jpg") !in known && readable(it) }.sortedBy { it.lastModified() }.forEach {
                val id = it.name.removeSuffix(".orig.jpg"); val out = File(dir, "$id.jpeg")
                prepare(it, out); if (out.exists()) analyze(out, id, "Recovered")
            }
            files.filter { it.nameWithoutExtension !in known && readable(it) }.sortedBy { it.lastModified() }
                .forEach { analyze(it, it.nameWithoutExtension, "Recovered") }
        }
    }

    fun confirm(d: Draft, reg: String) = viewModelScope.launch {
        val clean = reg.trim().uppercase().replace(Regex("\\s+"), " ")
        val old = dao.byId(d.id)   // non-null when this draft came from "Reprocess"
        dao.upsert(if (old != null) old.copy(
            registration = clean, rawRegistration = d.best?.value, registrationConfidence = d.best?.score ?: 0f, driveName = null, error = null,
            status = if (old.status == CardStatus.UPLOADED && old.registration != clean) CardStatus.PENDING_UPLOAD else old.status,
            jobCardNo = d.fields.jobCardNo ?: old.jobCardNo, customer = d.fields.customer ?: old.customer, phone = d.fields.phone ?: old.phone,
            imei = d.fields.imei ?: old.imei, jobDate = d.fields.date ?: old.jobDate)
        else JobCardEntity(d.id, clean, d.best?.value, d.best?.score ?: 0f, d.path, d.source,
            jobCardNo = d.fields.jobCardNo, customer = d.fields.customer, phone = d.fields.phone, imei = d.fields.imei, jobDate = d.fields.date, hash = d.hash))
        drafts.update { it - d }
        UploadWorker.enqueue(app)
    }
    /** Re-run reading on a saved card; the saved record is only changed when the user confirms the new result. */
    fun reprocess(c: JobCardEntity) = viewModelScope.launch { analyze(File(c.filePath), c.id, "Reprocess") }
    /** Fix a registration after saving. If the card is already in Drive the file is renamed there too. */
    fun editRegistration(c: JobCardEntity, reg: String) = viewModelScope.launch {
        val clean = reg.trim().uppercase().replace(Regex("\\s+"), " "); if (clean.isBlank()) return@launch
        dao.editReg(c.id, clean); UploadWorker.enqueue(app)
    }
    fun storageBytes(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun clearTemp() { File(app.cacheDir, "share").deleteRecursively() }   // never touches saved cards
    fun uploadNow() = UploadWorker.enqueue(app)
    /** action: "replace" | "new" | "cancel" */
    fun resolve(c: JobCardEntity, action: String) = viewModelScope.launch {
        when (action) {
            "replace" -> dao.resolve(c.id, c.duplicateOfId, c.driveName)
            "new" -> dao.resolve(c.id, null, RegistrationDetector.sanitizeFilename(c.registration).removeSuffix(".jpeg") + " (2).jpeg")
            else -> dao.setStatus(c.id, CardStatus.UPLOAD_FAILED, "Held: a file with this registration already exists in Drive.")
        }
        if (action != "cancel") UploadWorker.enqueue(app)
    }
    fun discard(d: Draft) { viewModelScope.launch { if (dao.byId(d.id) == null) { File(d.path).delete(); d.original?.let { File(it).delete() } }; drafts.update { it - d } } }
    /** The clean-up made it worse (e.g. a wrong crop): file the photo as received instead (camera rotation applied, otherwise untouched). */
    fun useOriginal(d: Draft) = viewModelScope.launch {
        val o = d.original?.let { File(it) } ?: return@launch
        val tmp = File(dir, "${d.id}.tmp")
        if (DocumentCleaner.exportOriginal(o, tmp) && tmp.length() > 0) {
            tmp.copyTo(File(d.path), true); tmp.delete(); o.delete()
            drafts.update { l -> l.map { if (it.id == d.id) it.copy(original = null) else it } }
        } else tmp.delete()
    }
}
