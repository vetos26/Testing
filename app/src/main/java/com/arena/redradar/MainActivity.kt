package com.arena.redradar

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.arena.redradar.game.GameView
import com.arena.redradar.game.Sound
import com.arena.redradar.menu.MenuView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private var sound: Sound? = null
    private var menuView: MenuView? = null
    private var gameView: GameView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        installCrashLogger()
        prefs = getSharedPreferences("redradar", MODE_PRIVATE)
        sound = Sound(prefs.getBoolean("sound", true))

        // если прошлый запуск упал — сначала показываем стек ошибки
        val crash = readCrash()
        if (crash != null) {
            showCrashScreen(crash)
            return
        }
        showMenu()
    }

    // ---------- перехват падений ----------
    private fun installCrashLogger() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val text = "=== ПАДЕНИЕ $time (поток: ${t.name}) ===\n$sw\n\n"
                try {
                    openFileOutput("crash.txt", MODE_APPEND).use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    }
                } catch (_: Throwable) { }
                try {
                    val dir = getExternalFilesDir(null)
                    if (dir != null) {
                        val f = File(dir, "crash.txt")
                        f.appendText(text)
                    }
                } catch (_: Throwable) { }
            } catch (_: Throwable) { }
            prev?.uncaughtException(t, e)
        }
    }

    private fun readCrash(): String? {
        return try {
            val f = File(getExternalFilesDir(null), "crash.txt")
            if (f.exists() && f.length() > 0) f.readText() else {
                val f2 = File(filesDir, "crash.txt")
                if (f2.exists() && f2.length() > 0) f2.readText() else null
            }
        } catch (_: Throwable) { null }
    }

    private fun clearCrash() {
        try { File(getExternalFilesDir(null), "crash.txt").delete() } catch (_: Throwable) { }
        try { File(filesDir, "crash.txt").delete() } catch (_: Throwable) { }
    }

    /** Экран с текстом последнего падения: тап — очистить и продолжить. */
    private fun showCrashScreen(text: String) {
        val pad = (resources.displayMetrics.density * 12).toInt()
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val head = TextView(this).apply {
            setText(
                "ОШИБКА ПРОШЛОГО ЗАПУСКА\n(пошлите этот текст разработчику)\n\n— тап по экрану: продолжить —"
            )
            setTextColor(Color.rgb(255, 140, 120))
            typeface = Typeface.DEFAULT_BOLD
            textSize = 14f
        }
        val body = TextView(this).apply {
            setText(text)
            setTextColor(Color.rgb(220, 224, 230))
            typeface = Typeface.MONOSPACE
            textSize = 11f
        }
        box.addView(head)
        box.addView(body)
        scroll.addView(box)
        setContentView(scroll)
        scroll.setOnClickListener { clearCrash(); showMenu() }
    }

    // ---------- обычная работа ----------
    private fun showMenu() {
        gameView?.let { g ->
            g.onActivityPause()
        }
        gameView = null
        val mv = menuView ?: MenuView(
            this,
            prefs,
            onStartGame = { diff -> startGame(diff) },
            onQuit = { finishAffinity() }
        ).also { menuView = it }
        setContentView(mv)
    }

    private fun startGame(difficulty: String) {
        menuView = null
        val gv = GameView(this, sound ?: Sound(true).also { sound = it }, difficulty) {
            runOnUiThread { showMenu() }
        }
        gameView = gv
        setContentView(gv)
    }

    override fun onPause() {
        super.onPause()
        gameView?.onActivityPause()
    }

    override fun onResume() {
        super.onResume()
        gameView?.onActivityResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        sound?.release()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // из игры — в меню; в меню — стандартное поведение
        if (gameView != null) {
            showMenu()
        } else {
            super.onBackPressed()
        }
    }
}
