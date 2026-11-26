package com.arena.redradar.game

import android.media.AudioManager
import android.media.ToneGenerator

/** Простые сигналы через ToneGenerator (без файлов-ассетов). */
class Sound(startEnabled: Boolean) {
    var enabled = startEnabled
    private var tg: ToneGenerator? = null

    private fun ensure() {
        if (tg == null) {
            tg = try {
                ToneGenerator(AudioManager.STREAM_MUSIC, 55)
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun play(tone: Int, dur: Int = 120) {
        if (!enabled) return
        ensure()
        try { tg?.startTone(tone, dur) } catch (e: Exception) { }
    }

    fun click() = play(ToneGenerator.TONE_PROP_BEEP)
    fun order() = play(ToneGenerator.TONE_PROP_ACK)
    fun trained() = play(ToneGenerator.TONE_PROP_BEEP2)
    fun built() = play(ToneGenerator.TONE_CDMA_CONFIRM, 200)
    fun alarm() = play(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 500)
    fun boom() = play(ToneGenerator.TONE_SUP_ERROR, 250)

    fun release() {
        try { tg?.release() } catch (e: Exception) { }
        tg = null
    }
}
