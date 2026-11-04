package com.coralcoder.iwatchbridge.core

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Saves the stack trace of an uncaught crash so the next launch can show it and let the user
 * share it. Chains to the default handler, so Android still reports the crash normally.
 */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "last_crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
                file(app).writeText(
                    "iWatch Bridge $version · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                        "Thread: ${thread.name}\n\n$sw",
                )
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** The last crash report, removed once read. */
    fun take(context: Context): String? {
        val f = file(context)
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull().also { f.delete() }
    }
}
