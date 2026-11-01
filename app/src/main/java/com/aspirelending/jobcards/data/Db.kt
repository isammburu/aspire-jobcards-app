package com.aspirelending.jobcards.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

enum class CardStatus { PENDING_UPLOAD, UPLOADING, UPLOADED, UPLOAD_FAILED, DUPLICATE_WARNING }

@Entity(tableName = "job_cards", indices = [Index("registration"), Index("createdAt"), Index("status")])
data class JobCardEntity(
    @PrimaryKey val id: String, val registration: String, val rawRegistration: String?,
    val registrationConfidence: Float, val filePath: String, val source: String,
    val status: CardStatus = CardStatus.PENDING_UPLOAD, val driveFileId: String? = null,
    val driveName: String? = null, val duplicateOfId: String? = null, val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(), val uploadedAt: Long? = null,
    val jobCardNo: String? = null, val customer: String? = null, val phone: String? = null,
    val imei: String? = null, val jobDate: String? = null, val hash: String? = null
)

@Dao interface JobCardDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(c: JobCardEntity)
    @Query("SELECT * FROM job_cards ORDER BY createdAt DESC LIMIT 50") fun recent(): Flow<List<JobCardEntity>>
    @Query("SELECT * FROM job_cards WHERE (registration LIKE '%' || :q || '%' OR jobCardNo LIKE '%' || :q || '%' OR customer LIKE '%' || :q || '%' OR phone LIKE '%' || :q || '%' OR imei LIKE '%' || :q || '%') AND (:status = '' OR status = :status) ORDER BY createdAt DESC LIMIT 300") fun search(q: String, status: String): Flow<List<JobCardEntity>>
    @Query("SELECT * FROM job_cards WHERE id = :id") suspend fun byId(id: String): JobCardEntity?
    @Query("SELECT * FROM job_cards WHERE hash = :h LIMIT 1") suspend fun byHash(h: String): JobCardEntity?
    @Query("UPDATE job_cards SET registration=:r, driveName=NULL, duplicateOfId=NULL, error=NULL, status=CASE WHEN status IN ('UPLOADED','DUPLICATE_WARNING') THEN 'PENDING_UPLOAD' ELSE status END WHERE id=:id") suspend fun editReg(id: String, r: String)
    @Query("SELECT id FROM job_cards") suspend fun ids(): List<String>
    @Query("DELETE FROM job_cards WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT COUNT(*) FROM job_cards WHERE createdAt >= :since") fun capturedSince(since: Long): Flow<Int>
    @Query("SELECT COUNT(*) FROM job_cards WHERE status = :s") fun count(s: CardStatus): Flow<Int>
    @Query("SELECT * FROM job_cards WHERE status IN ('PENDING_UPLOAD','UPLOADING','UPLOAD_FAILED') ORDER BY createdAt") suspend fun uploadable(): List<JobCardEntity>
    @Query("SELECT * FROM job_cards WHERE status = 'DUPLICATE_WARNING' ORDER BY createdAt") fun duplicates(): Flow<List<JobCardEntity>>
    @Query("UPDATE job_cards SET status=:s, error=:e WHERE id=:id") suspend fun setStatus(id: String, s: CardStatus, e: String? = null)
    @Query("UPDATE job_cards SET status='UPLOADED', driveFileId=:f, error=NULL, uploadedAt=:t WHERE id=:id") suspend fun uploaded(id: String, f: String, t: Long)
    @Query("UPDATE job_cards SET status='DUPLICATE_WARNING', duplicateOfId=:d WHERE id=:id") suspend fun duplicate(id: String, d: String)
    @Query("UPDATE job_cards SET status='PENDING_UPLOAD', driveFileId=:f, driveName=:n, duplicateOfId=NULL, error=NULL WHERE id=:id") suspend fun resolve(id: String, f: String?, n: String?)
    @Query("UPDATE job_cards SET status='UPLOAD_FAILED', error=:e WHERE status IN ('PENDING_UPLOAD','UPLOADING')") suspend fun failAll(e: String)
}

val M2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        listOf("jobCardNo", "customer", "phone", "imei", "jobDate").forEach { db.execSQL("ALTER TABLE job_cards ADD COLUMN $it TEXT") }
    }
}

val M3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) { db.execSQL("ALTER TABLE job_cards ADD COLUMN hash TEXT") }
}

class Converters {
    @TypeConverter fun to(s: CardStatus) = s.name
    @TypeConverter fun from(s: String) = CardStatus.valueOf(s)
}

@Database(entities = [JobCardEntity::class], version = 4, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDb : RoomDatabase() {
    abstract fun cards(): JobCardDao
    companion object {
        @Volatile private var i: AppDb? = null
        fun get(c: Context) = i ?: synchronized(this) {
            i ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "aspire.db")
                .addMigrations(M2_3, M3_4).build().also { i = it }
        }
    }
}

object Prefs {
    const val DEFAULT_FOLDER = "1KxVD8Q7x4wDiTDTmEHt1Qw_-lC72B8uw"
    val connected = MutableStateFlow(false)
    val userName = MutableStateFlow("Sam Mburu")
    val wifiOnly = MutableStateFlow(false)
    val folder = MutableStateFlow(DEFAULT_FOLDER)
    val onboarded = MutableStateFlow(true)
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("aspire", Context.MODE_PRIVATE)
    fun init(c: Context) = sp(c).let {
        connected.value = it.getBoolean("drive", false); userName.value = it.getString("name", "Sam Mburu")!!
        wifiOnly.value = it.getBoolean("wifi", false); onboarded.value = it.getBoolean("onboarded", false); folder.value = it.getString("folder", DEFAULT_FOLDER)!!
    }
    fun setConnected(c: Context, v: Boolean) { sp(c).edit().putBoolean("drive", v).apply(); connected.value = v }
    fun setOnboarded(c: Context, v: Boolean) { sp(c).edit().putBoolean("onboarded", v).apply(); onboarded.value = v }
    fun setName(c: Context, v: String) { sp(c).edit().putString("name", v).apply(); userName.value = v }
    fun setWifiOnly(c: Context, v: Boolean) { sp(c).edit().putBoolean("wifi", v).apply(); wifiOnly.value = v }
    /** Accepts a folder ID or a full Drive folder URL. */
    fun setFolder(c: Context, input: String): Boolean {
        val id = Regex("folders/([A-Za-z0-9_-]+)").find(input)?.groupValues?.get(1) ?: input.trim().takeIf { Regex("[A-Za-z0-9_-]{10,}").matches(it) } ?: return false
        sp(c).edit().putString("folder", id).apply(); folder.value = id; return true
    }
    fun folderId(c: Context) = sp(c).getString("folder", DEFAULT_FOLDER)!!
}
