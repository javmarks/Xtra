package com.github.andreyasadchy.xtra.util

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream

object CrashDiagnostics {
    private const val FILE_NAME = "last_crash_report.txt"
    private const val MAX_REPORT_BYTES = 96 * 1024

    fun install(context: Context) {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val runtime = Runtime.getRuntime()
                val usedMemory = runtime.totalMemory() - runtime.freeMemory()
                val report = buildString {
                    appendLine("Xtra crash report")
                    appendLine("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US).format(java.util.Date())}")
                    appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Thread: ${thread.name}")
                    appendLine("Heap used: ${usedMemory / (1024 * 1024)} MB")
                    appendLine("Heap max: ${runtime.maxMemory() / (1024 * 1024)} MB")
                    appendLine()
                    append(error.stackTraceToString())
                }.take(MAX_REPORT_BYTES)
                FileOutputStream(File(context.filesDir, FILE_NAME), false).use {
                    it.write(report.toByteArray(Charsets.UTF_8))
                    it.fd.sync()
                }
            } catch (_: Throwable) {
                // Crash reporting must never prevent Android's normal crash handling.
            } finally {
                previousHandler?.uncaughtException(thread, error)
            }
        }
    }

    fun read(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        return try {
            if (file.isFile) file.readText(Charsets.UTF_8) else null
        } catch (_: Exception) {
            null
        }
    }
}
