package com.automaker.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import com.automaker.app.engine.CancelledConversion
import com.automaker.app.engine.PackageConverter
import com.automaker.app.engine.PackageNames
import com.automaker.app.engine.SigningKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class StepView(val id: String, val label: String, val state: String)

data class SessionSnapshot(
    val id: String,
    val originalName: String,
    val downloadName: String,
    val format: String,
    val status: String,
    val step: String,
    val steps: List<StepView>,
    val log: List<String>,
    val error: String?,
    val changes: List<String>,
    val packageName: String,
    val size: Long,
    val progress: Int = -1,
    val phase: String = "",
)

class MakerApp : Application() {
    lateinit var sessions: SessionRepository
        private set

    override fun onCreate() {
        super.onCreate()
        SessionRepository.createNotificationChannel(this)
        sessions = SessionRepository(this)
        sessions.restore()
    }
}

class SessionRepository(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val snapshots = MutableStateFlow<List<SessionSnapshot>>(emptyList())
    val state: StateFlow<List<SessionSnapshot>> = snapshots
    private val converter by lazy {
        val keys = SigningKeys()
        PackageConverter { packageName -> keys.signerFor(packageName) }
    }

    private fun navigationDex(): ByteArray = assetBytes("navigation.dex")

    private fun lifecycleDex(): ByteArray = assetBytes("lifecycle.dex")

    private fun carDex(): ByteArray = assetBytes("car.dex")

    private fun supportDex(): ByteArray = assetBytes("car-support.dex")

    private fun archDex(): ByteArray = assetBytes("arch.dex")

    private fun activityDex(): ByteArray = assetBytes("activity.dex")

    private fun shizukuDex(): ByteArray = assetBytes("shizuku.dex")

    private fun keyboardDex(): ByteArray = assetBytes("keyboard.dex")

    private fun assetBytes(name: String): ByteArray {
        return context.assets.open(name).use { it.readBytes() }
    }

    fun restore() {
        val root = sessionsDir()
        for (dir in root.listFiles().orEmpty()) {
            if (!dir.isDirectory) continue
            val record = File(dir, "session.json")
            if (!record.isFile) {
                dir.deleteRecursively()
                continue
            }
            val job = readJob(record)
            if (job == null) {
                dir.deleteRecursively()
                continue
            }
            if (job.status == "running") {
                job.status = "error"
                job.error = "The conversion stopped because the app closed. Start it again."
                writeJob(job)
            }
            jobs[job.id] = job
        }
        purgeExpired()
        publish()
    }

    fun start(
        input: File,
        originalName: String,
        kind: String = "game",
        renamePackage: Boolean = false,
        notes: List<String> = emptyList(),
        gamepad: Boolean = false,
    ): String {
        purgeExpired()
        val id = UUID.randomUUID().toString()
        val xapk = originalName.lowercase().endsWith(".xapk")
        val directory = File(sessionsDir(), id)
        directory.mkdirs()
        val storedInput = File(directory, if (xapk) "input.xapk" else "input.apk")
        input.copyTo(storedInput, overwrite = true)
        input.delete()
        val selected = if (kind == "navigation") "navigation" else "game"
        val job = Job(
            id = id,
            originalName = File(originalName).name.ifBlank { if (xapk) "app.xapk" else "app.apk" },
            downloadName = downloadName(originalName),
            format = "apk",
            status = "running",
            step = "decode",
            directory = directory,
            output = File(directory, "signed.apk"),
            createdAt = System.currentTimeMillis(),
            kind = selected,
            packageSuffix = if (renamePackage) PackageNames.SUFFIX else "",
            gamepad = gamepad,
            initialLog = notes,
        )
        jobs[id] = job
        writeJob(job)
        publish()
        keepAlive()
        job.task = scope.launch {
            run(job, storedInput, xapk)
        }
        return id
    }

    fun discard(id: String) {
        val job = jobs[id] ?: return
        if (job.status == "running") {
            job.cancelled = true
            val worker = job.worker
            val task = job.task
            jobs.remove(id)
            publish()
            stopWorker(worker)
            task?.cancel()
            scope.launch { job.directory.deleteRecursively() }
            return
        }
        remove(id)
    }

    private fun stopWorker(worker: Thread?) {
        worker?.interrupt()
    }

    fun cancelRunning() {
        jobs.values.filter { it.status == "running" }.forEach { discard(it.id) }
    }

    fun directory(id: String): File? = jobs[id]?.directory

    fun outputFile(id: String): File? {
        val job = jobs[id] ?: return null
        if (job.status != "done" || !job.output.isFile) return null
        return job.output
    }

    fun exportAndDelete(id: String, destination: Uri): String? {
        val job = jobs[id] ?: return "That session is no longer on this phone."
        if (job.status != "done" || !job.output.isFile) return "The signed package is not ready."
        return try {
            context.contentResolver.openOutputStream(destination)?.use { out ->
                job.output.inputStream().use { it.copyTo(out) }
            } ?: return "The file could not be saved."
            remove(id)
            null
        } catch (error: Exception) {
            error.message ?: "The file could not be saved."
        }
    }

    fun purgeExpired() {
        val now = System.currentTimeMillis()
        for (job in jobs.values.toList()) {
            if (job.status == "running") continue
            if (now - job.createdAt >= JOB_TTL_MS) remove(job.id)
        }
    }

    private suspend fun run(job: Job, input: File, xapk: Boolean) {
        try {
            val result = runWorker(job) {
                val logLine = { line: String ->
                    if (!job.cancelled && jobs.containsKey(job.id)) {
                        job.log.add(line)
                        writeJob(job)
                        publish()
                    }
                }
                val onStep = { step: String ->
                    if (!job.cancelled && jobs.containsKey(job.id)) {
                        job.step = step
                        writeJob(job)
                        publish()
                    }
                }
                val onProgress = { percent: Int, label: String ->
                    if (!job.cancelled && jobs.containsKey(job.id)) {
                        job.progress = percent
                        job.phase = label
                        publish()
                    }
                }
                if (xapk) {
                    converter.convertXapk(
                        input,
                        job.output,
                        logLine,
                        onStep,
                        { job.cancelled },
                        job.kind,
                        navigationDex(),
                        lifecycleDex(),
                        carDex(),
                        supportDex(),
                        archDex(),
                        activityDex(),
                        shizukuDex(),
                        keyboardDex(),
                        job.packageSuffix,
                        job.gamepad,
                        onProgress,
                    )
                } else {
                    converter.convertApk(
                        input,
                        job.output,
                        logLine,
                        onStep,
                        { job.cancelled },
                        job.kind,
                        navigationDex(),
                        lifecycleDex(),
                        carDex(),
                        supportDex(),
                        archDex(),
                        activityDex(),
                        shizukuDex(),
                        keyboardDex(),
                        job.packageSuffix,
                        job.gamepad,
                        onProgress,
                    )
                }
            }
            if (job.cancelled || !jobs.containsKey(job.id)) {
                if (jobs.containsKey(job.id)) remove(job.id)
                return
            }
            job.status = "done"
            job.step = "sign"
            job.progress = -1
            job.phase = ""
            job.changes = result.changes
            job.packageName = result.packageName
            job.format = result.format
            job.size = result.size
            input.delete()
            writeJob(job)
        } catch (_: CancelledConversion) {
            if (jobs.containsKey(job.id)) remove(job.id)
        } catch (_: CancellationException) {
            if (jobs.containsKey(job.id)) remove(job.id)
        } catch (error: Throwable) {
            if (job.cancelled || error is ThreadDeath) {
                if (jobs.containsKey(job.id)) remove(job.id)
                return
            }
            job.status = "error"
            job.progress = -1
            job.phase = ""
            job.error = "This package could not be converted."
            job.log.add(job.error ?: "")
            writeJob(job)
        } finally {
            publish()
            if (jobs.values.none { it.status == "running" }) {
                context.stopService(Intent(context, ConvertService::class.java))
            }
        }
    }

    private suspend fun <T> runWorker(job: Job, block: () -> T): T = suspendCancellableCoroutine { continuation ->
        val worker = Thread({
            try {
                if (job.cancelled) {
                    if (continuation.isActive) continuation.resumeWithException(CancelledConversion())
                    return@Thread
                }
                val value = block()
                if (continuation.isActive) continuation.resume(value)
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }, "convert-${job.id}")
        worker.isDaemon = true
        job.worker = worker
        continuation.invokeOnCancellation { stopWorker(worker) }
        if (continuation.isActive) worker.start() else stopWorker(worker)
    }

    private fun remove(id: String) {
        val job = jobs.remove(id) ?: return
        job.directory.deleteRecursively()
        publish()
    }

    private fun keepAlive() {
        val intent = Intent(context, ConvertService::class.java)
        try {
            context.startForegroundService(intent)
        } catch (_: Exception) {
        }
    }

    private fun publish() {
        snapshots.value = jobs.values
            .sortedByDescending { it.createdAt }
            .map { it.snapshot() }
    }

    private fun sessionsDir(): File = File(context.filesDir, "sessions").also { it.mkdirs() }

    private fun writeJob(job: Job) {
        if (job.cancelled || !jobs.containsKey(job.id)) return
        val json = JSONObject()
        json.put("id", job.id)
        json.put("originalName", job.originalName)
        json.put("downloadName", job.downloadName)
        json.put("format", job.format)
        json.put("status", job.status)
        json.put("step", job.step)
        json.put("error", job.error)
        json.put("packageName", job.packageName)
        json.put("size", job.size)
        json.put("kind", job.kind)
        json.put("changes", JSONArray(job.changes))
        json.put("log", JSONArray(job.log.takeLast(200)))
        File(job.directory, "session.json").writeText(json.toString())
    }

    private fun readJob(file: File): Job? {
        return try {
            val json = JSONObject(file.readText())
            val directory = file.parentFile ?: return null
            val format = json.optString("format", "apk")
            val log = ArrayList<String>()
            val logArray = json.optJSONArray("log") ?: JSONArray()
            for (index in 0 until logArray.length()) log.add(logArray.optString(index))
            val changes = ArrayList<String>()
            val changeArray = json.optJSONArray("changes") ?: JSONArray()
            for (index in 0 until changeArray.length()) changes.add(changeArray.optString(index))
            Job(
                id = json.getString("id"),
                originalName = json.optString("originalName", "app.apk"),
                downloadName = json.optString("downloadName", "app-android-auto.apk"),
                format = format,
                status = json.optString("status", "error"),
                step = json.optString("step", "decode"),
                directory = directory,
                output = File(directory, if (format == "xapk") "signed.xapk" else "signed.apk"),
                createdAt = json.optLong("createdAt", file.lastModified()),
                kind = json.optString("kind", "game"),
                error = json.optString("error").ifBlank { null },
                packageName = json.optString("packageName"),
                size = json.optLong("size"),
                changes = changes,
                initialLog = log,
            )
        } catch (_: Exception) {
            null
        }
    }

    private class Job(
        val id: String,
        val originalName: String,
        val downloadName: String,
        var format: String,
        var status: String,
        var step: String,
        val directory: File,
        val output: File,
        val createdAt: Long,
        val kind: String = "game",
        val packageSuffix: String = "",
        val gamepad: Boolean = false,
        var error: String? = null,
        var packageName: String = "",
        var size: Long = 0,
        var changes: List<String> = emptyList(),
        initialLog: List<String> = emptyList(),
    ) {
        val log: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList(initialLog)
        @Volatile var cancelled: Boolean = false
        @Volatile var progress: Int = -1
        @Volatile var phase: String = ""
        @Volatile var worker: Thread? = null
        @Volatile var task: kotlinx.coroutines.Job? = null

        fun snapshot(): SessionSnapshot {
            val lines = log.toList()
            return SessionSnapshot(
                id = id,
                originalName = originalName,
                downloadName = downloadName,
                format = format,
                status = status,
                step = step,
                steps = STEPS.map { (stepId, label) ->
                    StepView(stepId, label, stepState(stepId))
                },
                log = lines.takeLast(80),
                error = error,
                changes = changes,
                packageName = packageName,
                size = size,
                progress = progress,
                phase = phase,
            )
        }

        private fun stepState(id: String): String {
            val index = STEPS.indexOfFirst { it.first == id }
            val current = STEPS.indexOfFirst { it.first == step }
            if (status == "error" && index == current) return "error"
            if (status == "done" || index < current) return "done"
            if (index == current) return "active"
            return "pending"
        }
    }

    companion object {
        const val NOTIFICATION_ID = 41
        private const val CHANNEL_ID = "conversions"
        private const val JOB_TTL_MS = 60 * 60 * 1000L
        val STEPS = listOf(
            "decode" to "Prepare",
            "patch" to "Update",
            "build" to "Build",
            "sign" to "Finish",
        )

        fun downloadName(originalName: String): String {
            val base = File(originalName).name.replace(Regex("[^a-zA-Z0-9._-]+"), "_")
            val lower = base.lowercase()
            val stem = when {
                lower.endsWith(".xapk") -> base.dropLast(5)
                lower.endsWith(".apk") -> base.dropLast(4)
                else -> base
            }.ifBlank { "app" }
            return "$stem-android-auto.apk"
        }

        fun createNotificationChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "Conversions", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Android Auto Maker keeps converting while a session is running."
            manager.createNotificationChannel(channel)
        }

        fun notification(context: Context): Notification {
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_car)
                .setContentTitle("Android Auto Maker")
                .setContentText("Converting packages")
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        }
    }
}

class ConvertService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as MakerApp).sessions.cancelRunning()
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = SessionRepository.notification(this)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                SessionRepository.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(SessionRepository.NOTIFICATION_ID, notification)
        }
        if ((application as MakerApp).sessions.state.value.none { it.status == "running" }) stopSelf()
        return START_NOT_STICKY
    }
}
