package com.arena.redradar

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.view.WindowManager
import com.arena.redradar.game.GameView
import com.arena.redradar.game.Sound
import com.arena.redradar.menu.MenuView

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
        prefs = getSharedPreferences("redradar", MODE_PRIVATE)
        sound = Sound(prefs.getBoolean("sound", true))
        showMenu()
    }

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
