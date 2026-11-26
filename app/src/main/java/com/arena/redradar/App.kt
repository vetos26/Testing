package com.arena.redradar

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Класс приложения: ставит перехватчик падений раньше всех остальных классов.
 * Application создаётся системой ДО загрузки MainActivity, поэтому стек
 * записывается даже если не грузится сам класс Activity.
 * Стек пишется в crash.txt (внешняя память приложения и внутренняя);
 * при следующем запуске MainActivity показывает текст ошибки на экране.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            writeThrowable(e, "поток " + t.name)
            prev?.uncaughtException(t, e)
        }
    }

    private fun crashFiles(): List<File> = listOfNotNull(
        try { File(getExternalFilesDir(null), "crash.txt") } catch (_: Throwable) { null },
        try { File(filesDir, "crash.txt") } catch (_: Throwable) { null }
    )

    fun writeThrowable(e: Throwable, note: String) {
        try {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val text = "=== ОШИБКА $time (${note}) ===\n$sw\n\n"
            for (f in crashFiles()) {
                try { f.appendText(text) } catch (_: Throwable) { }
            }
        } catch (_: Throwable) { }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
