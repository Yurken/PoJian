package com.emerge.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.room.withTransaction
import com.emerge.app.capture.CaptureSettings
import com.emerge.app.monitoring.EncodedThumbnail
import com.emerge.app.monitoring.ThumbnailEncoder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.IOException
import java.util.UUID

/** Private, bounded, locally authorized storage with one representative per reliable work. */
class WorkThumbnailStore(context: Context, private val db: BrowseDatabase, private val settings: CaptureSettings,
    private val maxBytes: Long = 64L*1024*1024, private val maxFiles: Int = 400,
    private val availableSpace: (File)->Long = { it.usableSpace }) : AutoCloseable {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutex=Mutex()
    private val decoding=Semaphore(2)
    internal val directory=File(context.noBackupFilesDir,"work-thumbnails")
    private val dao=db.workThumbnailDao()
    val thumbnails=dao.observe().map { rows -> rows.associateBy { it.workKey } }
        .stateIn(scope,SharingStarted.Eagerly,emptyMap())
    private val statusState=MutableStateFlow("")
    val status: StateFlow<String> = statusState.asStateFlow()
    private val cache=object : LruCache<String,Bitmap>(10*1024*1024) {
        override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount
    }
    init {
        scope.launch { combine(settings.localThumbnails,settings.allowedPackages,dao.observe()) { _,_,_ -> Unit }
            .collect { try { maintain() } catch (e: Exception) {
                if (e is CancellationException) throw e
                statusState.value="缩略图清理失败"
            } } }
    }

    internal fun captureFailed() { statusState.value="当前画面无法保存缩略图" }

    internal suspend fun save(event: BrowseEvent, image: EncodedThumbnail, revision: Long,
        permitted: ()->Boolean): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        val key=event.workKey ?: return@withLock false
        fun allowed()=settings.localThumbnails.value && settings.thumbnailRevision==revision &&
            settings.permissionToken(event.packageName)!=null && permitted()
        if(event.identityMethod !in setOf("platform_id","author_title") || !allowed() ||
            image.bytes.size !in 1..ThumbnailEncoder.MAX_BYTES || image.kind !in setOf("page_screenshot","video_frame","content_crop")) return@withLock false
        val previous=dao.get(key)
        if(previous!=null && (previous.kind!="page_screenshot" || image.kind=="page_screenshot")) return@withLock false
        var file: File?=null
        try {
            if(!directory.exists() && !directory.mkdirs()) throw IOException("directory")
            if(availableSpace(directory) < image.bytes.size+2L*1024*1024) {
                statusState.value="存储空间不足，缩略图未保存"; return@withLock false
            }
            file=File(directory,"${UUID.randomUUID()}.jpg")
            file.outputStream().use { it.write(image.bytes) }
            val row=WorkThumbnail(key,event.packageName,file.name,System.currentTimeMillis(),image.kind,
                image.width,image.height,image.bytes.size.toLong())
            val saved=db.withTransaction {
                val retained=db.browseEventDao().event(event.id)
                if(!allowed() || retained?.workKey!=key || retained.packageName!=event.packageName ||
                    retained.sourceType!="accessibility") return@withTransaction false
                if(previous!=null) dao.delete(previous.workKey,previous.fileName)
                dao.insert(row)!=-1L
            }
            if(!saved || !allowed()) { dao.delete(key,row.fileName); file.delete(); return@withLock false }
            previous?.let { removeFile(it.fileName) }
            pruneLocked()
            statusState.value=""
            true
        } catch(error: Exception) {
            withContext(NonCancellable) { file?.let { target -> dao.all().firstOrNull { it.fileName==target.name }?.let { dao.delete(it.workKey,it.fileName) }; target.delete() } }
            if(error is CancellationException) throw error
            statusState.value="缩略图保存失败"
            false
        }
    } }

    suspend fun maintain() = withContext(Dispatchers.IO) { mutex.withLock { pruneLocked() } }
    private suspend fun pruneLocked() {
        val rows=dao.all().sortedWith(compareBy<WorkThumbnail> { it.capturedAt }.thenBy { it.workKey })
        val retained=rows.filter { settings.localThumbnails.value && it.packageName in settings.allowedPackages.value &&
            file(it.fileName)?.isFile==true }.toMutableList()
        var bytes=retained.sumOf { it.byteCount }
        while(retained.size>maxFiles || bytes>maxBytes) bytes-=retained.removeAt(0).byteCount
        val names=retained.map { it.fileName }.toSet()
        rows.filter { it.fileName !in names }.forEach { dao.delete(it.workKey,it.fileName); removeFile(it.fileName) }
        directory.listFiles()?.filter { it.isFile && it.name !in names }?.forEach { it.delete() }
        cache.snapshot().keys.filter { it !in names }.forEach(cache::remove)
    }
    private fun file(name:String):File? = name.takeIf { it.matches(Regex("[a-f0-9-]{36}\\.jpg")) }?.let { File(directory,it) }
    private fun removeFile(name:String) { cache.remove(name); file(name)?.delete() }

    suspend fun load(row: WorkThumbnail): Bitmap? = withContext(Dispatchers.IO) { decoding.withPermit {
        if(!settings.localThumbnails.value || row.packageName !in settings.allowedPackages.value) return@withPermit null
        cache.get(row.fileName)?.let { return@withPermit it }
        val source=file(row.fileName)?.takeIf { it.isFile && it.length()<=ThumbnailEncoder.MAX_BYTES } ?: return@withPermit null
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeFile(source.path,bounds)
        if(bounds.outWidth !in 1..480 || bounds.outHeight !in 1..480) return@withPermit null
        val decoded = BitmapFactory.decodeFile(source.path) ?: return@withPermit null
        if(!settings.localThumbnails.value || row.packageName !in settings.allowedPackages.value || dao.get(row.workKey)?.fileName != row.fileName) {
            decoded.recycle(); return@withPermit null
        }
        cache.put(row.fileName,decoded)
        decoded
    } }
    override fun close() { scope.cancel(); cache.evictAll() }
}
