package com.parakeet.voicekeyboard

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class ParakeetApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                File(filesDir, CRASH_LOG_FILE).writeText(sw.toString())
            } catch (_: Throwable) {
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val CRASH_LOG_FILE = "last_crash.txt"
    }
}
