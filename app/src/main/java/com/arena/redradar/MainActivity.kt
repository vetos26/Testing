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
        try {
            prefs = getSharedPreferences("redradar", MODE_PRIVATE)
            sound = Sound(prefs.getBoolean("sound", true))
            showMenu()
        } catch (t: Throwable) {
            // сбой в главном пути запуска: показываем стек сразу, не выбрасывая
            App.instance.writeThrowable(t, "onCreate")
            showCrashScreen(readCrash() ?: "(нет текста ошибки)")
        }
    }

    // ---------- чтение/очистка файла падений ----------
    private fun crashFile(): File = try {
        val ext = getExternalFilesDir(null)
        val fe = if (ext != null) File(ext, "crash.txt") else null
        when {
            fe != null && fe.exists() && fe.length() > 0 -> fe
            else -> File(filesDir, "crash.txt")
        }
    } catch (_: Throwable) {
        File(filesDir, "crash.txt")
    }

    private fun readCrash(): String? = try {
        val f = crashFile()
        if (f.exists() && f.length() > 0) f.readText() else null
    } catch (_: Throwable) { null }

    private fun clearCrash() {
        try { File(getExternalFilesDir(null), "crash.txt").delete() } catch (_: Throwable) { }
        try { File(filesDir, "crash.txt").delete() } catch (_: Throwable) { }
    }

    /** Экран с текстом последнего падения: копирование и выход в меню. */
    private fun showCrashScreen(text: String) {
        val pad = (resources.displayMetrics.density * 12).toInt()
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val head = TextView(this).apply {
            setText(
                "ОШИБКА ПРОШЛОГО ЗАПУСКА\n(пошлите этот текст разработчику)"
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
            setTextIsSelectable(true)   // текст можно выделять пальцем вручную
        }
        box.addView(head)
        box.addView(body)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun mkButton(label: String, action: () -> Unit) = android.widget.Button(this).apply {
            setText(label)
            setOnClickListener { action() }
        }
        row.addView(mkButton("КОПИРОВАТЬ") {
            try {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("RedRadar", text))
                android.widget.Toast.makeText(this, "Скопировано", android.widget.Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                App.instance.writeThrowable(t, "копирование ошибки")
            }
        })
        row.addView(mkButton("В МЕНЮ") {
            clearCrash()
            showMenu()
        })
        box.addView(row)
        scroll.addView(box)
        setContentView(scroll)
    }

    // ---------- обычная работа ----------
    private fun showMenu() {
        // если игровая петля записала ошибку — сначала показываем её текст
        val c = readCrash()
        if (c != null) {
            showCrashScreen(c)
            return
        }
        gameView?.let { g ->
            g.onActivityPause()
        }
        gameView = null
        val mv = menuView ?: MenuView(
            this,
            App.instance.mod,
            prefs,
            onStartGame = { diff -> startGame(diff) },
            onQuit = { finishAffinity() }
        ).also { menuView = it }
        setContentView(mv)
    }

    private fun startGame(difficulty: String) {
        menuView = null
        try {
            val gv = GameView(this, App.instance.mod, sound ?: Sound(true).also { sound = it }, difficulty) {
                runOnUiThread { showMenu() }
            }
            gameView = gv
            setContentView(gv)
        } catch (t: Throwable) {
            App.instance.writeThrowable(t, "startGame")
            showCrashScreen(readCrash() ?: "(нет текста ошибки)")
        }
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
