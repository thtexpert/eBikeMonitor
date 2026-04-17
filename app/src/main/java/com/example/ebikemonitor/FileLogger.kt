package com.example.ebikemonitor

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A simple persistent logger that writes to a file in the app's files directory.
 * This is used to debug issues that survive app reboots, like system-level reboots.
 */
object FileLogger {
    private const val TAG = "FileLogger"
    private const val LOG_FILE_NAME = "ebike_monitor_debug.txt"
    private const val MAX_FILE_SIZE = 1024 * 1024 // 1 MB
    
    private val executor = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    
    private var logFile: File? = null

    fun init(context: Context) {
        logFile = File(context.filesDir, LOG_FILE_NAME)
        log("FileLogger initialized. File path: ${logFile?.absolutePath}")
    }

    fun log(message: String) {
        // Also log to regular Logcat
        Log.d(TAG, message)
        
        executor.execute {
            try {
                val file = logFile ?: return@execute
                
                // Check for size limit
                if (file.exists() && file.length() > MAX_FILE_SIZE) {
                    val backup = File(file.parent, "$LOG_FILE_NAME.bak")
                    if (backup.exists()) backup.delete()
                    file.renameTo(backup)
                }
                
                val timestamp = dateFormat.format(Date())
                val logEntry = "[$timestamp] $message\n"
                
                file.appendText(logEntry)
            } catch (e: Exception) {
                Log.e(TAG, "Error writing to log file: ${e.message}")
            }
        }
    }

    fun getLogFilePath(): String? = logFile?.absolutePath
}
