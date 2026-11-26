package com.arena.redradar.menu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Главное меню в стиле классических стратегий: слева — панель с красным
 * радаром (круговая развёртка, метки), справа — колонка красных кнопок.
 * Состояния: главное, выбор сложности, настройки, авторы.
 */
class MenuView(
    context: Context,
    private val prefs: android.content.SharedPreferences,
    private val onStartGame: (String) -> Unit,
    private val onQuit: () -> Unit
) : View(context) {

    enum class State { MAIN, NEWGAME, SETTINGS, ABOUT }

    private var state = State.MAIN

    private data class Hit(val rect: RectF, val id: String)
    private val hits = ArrayList<Hit>()
    private var pressedId: String? = null

    // краски
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    // анимация радара
    private var lastFrame = SystemClock.uptimeMillis()
    private var sweepA = 0f

    private val blips = ArrayList<Float>()
    private val rnd = Random(System.nanoTime())
    private var blinkT = 0f

    init {
        for (i in 0 until 7) blips.add(rnd.nextFloat())
        postInvalidateOnAnimation()
    }

    // ---------- жизненный цикл анимации ----------
    private fun tick() {
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastFrame) / 1000f
        lastFrame = now
        sweepA += dt * 1.4f
        val tau = 2f * Math.PI.toFloat()
        if (sweepA > tau) sweepA -= tau
        blinkT += dt
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        tick()
        val w = width.toFloat()
        val h = height.toFloat()

        // фон
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, 0f, 0f, h,
            Color.rgb(58, 62, 68), Color.rgb(24, 26, 30), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        // ---------- левая панель (радар) ----------
        val panelLeft = w * 0.06f
        val panelTop = h * 0.05f
        val panelRight = w * 0.63f
        val panelBottom = h * 0.95f
        val rr = RectF(panelLeft, panelTop, panelRight, panelBottom)

        // металлическая рамка
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, panelTop, 0f, panelBottom,
            Color.rgb(150, 154, 160), Color.rgb(88, 92, 98), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rr, 40f, 40f, paint)
        paint.shader = null

        val inner = RectF(rr.left + 14f, rr.top + 14f, rr.right - 14f, rr.bottom - 14f)
        paint.color = Color.rgb(30, 32, 36)
        canvas.drawRoundRect(inner, 30f, 30f, paint)

        // круг радара
        val cx = inner.centerX()
        val cy = inner.centerY()
        val rad = minOf(inner.width(), inner.height()) * 0.46f

        paint.shader = LinearGradient(cx, cy - rad, cx, cy + rad,
            Color.rgb(84, 10, 10), Color.rgb(28, 6, 6), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, rad, paint)
        paint.shader = null

        // сетка радара
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = Color.argb(120, 190, 40, 40)
        for (k in 1..4) {
            canvas.drawCircle(cx, cy, rad * k / 4f, paint)
        }
        canvas.drawLine(cx - rad, cy, cx + rad, cy, paint)
        canvas.drawLine(cx, cy - rad, cx, cy + rad, paint)
        for (k in 0..3) {
            val a = k * Math.PI.toFloat() / 4f + Math.PI.toFloat() / 8f
            canvas.drawLine(cx, cy, cx + cos(a) * rad, cy + sin(a) * rad, paint)
        }
        paint.style = Paint.Style.FILL

        // развёртка (вращающийся градиентный сектор)
        canvas.save()
        canvas.rotate(Math.toDegrees(sweepA.toDouble()).toFloat(), cx, cy)
        paint.shader = SweepGradient(
            cx, cy,
            Color.argb(230, 235, 40, 40),
            Color.argb(0, 235, 40, 40)
        )
        canvas.drawCircle(cx, cy, rad, paint)
        canvas.restore()
        paint.shader = null

        // метки-блипы
        for (i in blips.indices) {
            val t = (blinkT * 0.7f + blips[i] * 6f) % 1f
            val a = blips[i] * 2f * Math.PI.toFloat()
            val d = rad * (0.25f + blips[i] * 0.65f)
            val alpha = ((1f - t) * 220f).toInt().coerceIn(0, 220)
            paint.color = Color.argb(alpha, 255, 70, 70)
            canvas.drawCircle(cx + cos(a) * d, cy + sin(a) * d, 5f - t * 3f, paint)
        }

        // центральный блик
        paint.color = Color.argb(60, 255, 120, 120)
        canvas.drawCircle(cx, cy, rad * 0.08f, paint)

        // ---------- заголовок ----------
        val titleCx = cx
        val titleY = inner.top + inner.height() * 0.18f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.rgb(240, 200, 90)
        textPaint.textSize = inner.width() * 0.115f
        canvas.drawText("КРАСНЫЙ", titleCx, titleY, textPaint)
        textPaint.textSize = inner.width() * 0.155f
        textPaint.color = Color.rgb(216, 48, 48)
        canvas.drawText("РАДАР", titleCx, titleY + inner.width() * 0.15f, textPaint)
        textPaint.textSize = inner.width() * 0.038f
        textPaint.color = Color.rgb(190, 196, 204)
        canvas.drawText("стратегия в реальном времени", titleCx, titleY + inner.width() * 0.21f, textPaint)
        textPaint.textSize = inner.width() * 0.03f
        textPaint.color = Color.rgb(140, 146, 154)
        canvas.drawText("вдохновлено Red Alert 2", titleCx, titleY + inner.width() * 0.25f, textPaint)

        // ---------- верхний правый блок «ВНИМАНИЕ» ----------
        if (state == State.MAIN) {
            val wr = RectF(w * 0.66f, h * 0.05f, w * 0.94f, h * 0.20f)
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(20, 22, 26)
            canvas.drawRoundRect(wr, 10f, 10f, paint)
            paint.shader = LinearGradient(wr.left, wr.top, wr.left, wr.bottom,
                Color.rgb(240, 200, 60), Color.rgb(200, 60, 30), Shader.TileMode.CLAMP)
            val innerW = RectF(wr.left + 6f, wr.top + 6f, wr.right - 6f, wr.bottom - 6f)
            canvas.drawRoundRect(innerW, 6f, 6f, paint)
            paint.shader = null
            // «шкала угрозы»
            paint.color = Color.argb(160, 30, 10, 10)
            val bars = 14
            val bw = innerW.width() / (bars * 1.6f)
            val lit = 4 + ((blinkT * 5f).toInt() % 9)
            for (k in 0 until bars) {
                paint.color = if (k < lit) Color.argb(230, 40, 24, 16) else Color.argb(90, 40, 24, 16)
                canvas.drawRect(
                    innerW.left + 8f + k * bw * 1.6f, innerW.centerY() - innerW.height() * 0.22f,
                    innerW.left + 8f + k * bw * 1.6f + bw, innerW.centerY() + innerW.height() * 0.22f,
                    paint
                )
            }
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = wr.height() * 0.22f
            textPaint.color = Color.argb(220, 60, 16, 12)
            canvas.drawText("ВНИМАНИЕ", wr.centerX(), wr.top + wr.height() * 0.28f, textPaint)
        }

        // ---------- правая колонка кнопок ----------
        hits.clear()
        val bx0 = w * 0.66f
        val bx1 = w * 0.94f

        val labels: List<String> = when (state) {
            State.MAIN -> listOf("НОВАЯ ИГРА", "НАСТРОЙКИ", "АВТОРЫ", "ВЫХОД")
            State.NEWGAME -> listOf("ЛЕГКО", "НОРМАЛЬНО", "СЛОЖНО", "НАЗАД")
            State.SETTINGS -> listOf(
                "СЛОЖНОСТЬ: " + Diff.label(prefs.getString("difficulty", "normal") ?: "normal"),
                "ЗВУК: " + (if (prefs.getBoolean("sound", true)) "ВКЛ" else "ВЫКЛ"),
                "СБРОС", "НАЗАД"
            )
            State.ABOUT -> listOf("НАЗАД")
        }

        val topPad = if (state == State.MAIN) h * 0.26f else h * 0.10f
        val bottomPad = h * 0.08f
        val n = labels.size
        val gap = 14f
        val bh = ((h - topPad - bottomPad) - gap * (n - 1)) / n

        for (i in 0 until n) {
            val by = topPad + i * (bh + gap)
            val rect = RectF(bx0, by, bx1, by + bh)
            val pressed = pressedId == "btn$i"
            drawMenuButton(canvas, rect, labels[i], pressed)
            hits.add(Hit(rect, "btn$i"))
        }

        // ---------- содержимое левой панели по состояниям ----------
        if (state == State.NEWGAME) {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.color = Color.rgb(230, 234, 240)
            textPaint.textSize = inner.width() * 0.045f
            canvas.drawText(
                "Выберите сложность.\nЦель — уничтожить всю базу противника.",
                titleCx, titleY + inner.width() * 0.34f, textPaint
            )
        } else if (state == State.SETTINGS) {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.color = Color.rgb(230, 234, 240)
            textPaint.textSize = inner.width() * 0.042f
            val lines = listOf(
                "Сложность влияет на доход ИИ,",
                "размер волн атак и число шахтёров.",
                "Звук — системные сигналы.",
                "Настройки сохраняются автоматически."
            )
            var ly = titleY + inner.width() * 0.36f
            for (l in lines) {
                canvas.drawText(l, titleCx, ly, textPaint)
                ly += inner.width() * 0.055f
            }
        } else if (state == State.ABOUT) {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.color = Color.rgb(230, 234, 240)
            textPaint.textSize = inner.width() * 0.04f
            val lines = listOf(
                "КРАСНЫЙ РАДАР — мобильная стратегия",
                "в духе классических RTS.",
                "",
                "Игра и весь код — оригинальные,",
                "написаны на Kotlin для Android.",
                "Не содержит материалов EA.",
                "",
                "Дизайн и программирование:",
                "Arena Agent",
                "",
                "Модель боя, экономика и ИИ:",
                "шахтёры, стрелки, ракетчики, танки,",
                "турели, энергосистема, волны атак."
            )
            var ly = titleY + inner.width() * 0.36f
            for (l in lines) {
                canvas.drawText(l, titleCx, ly, textPaint)
                ly += inner.width() * 0.05f
            }
        }

        // версия
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = w * 0.022f
        textPaint.color = Color.rgb(200, 206, 214)
        canvas.drawText("Версия 1.0", (bx0 + bx1) / 2f, h - 10f, textPaint)
    }

    private fun drawMenuButton(canvas: Canvas, r: RectF, label: String, pressed: Boolean) {
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(r.left, r.top, r.left, r.bottom,
            if (pressed) Color.rgb(230, 90, 90) else Color.rgb(120, 30, 30),
            if (pressed) Color.rgb(90, 30, 30) else Color.rgb(60, 14, 14),
            Shader.TileMode.CLAMP)
        canvas.drawRoundRect(r, 10f, 10f, paint)
        paint.shader = null

        // глянцевый блик сверху
        paint.shader = LinearGradient(r.left, r.top, r.left, r.centerY(),
            Color.argb(90, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(r.left + 3f, r.top + 3f, r.right - 3f, r.centerY()), 8f, 8f, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.rgb(150, 154, 160)
        canvas.drawRoundRect(r, 10f, 10f, paint)
        paint.style = Paint.Style.FILL

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = r.height() * 0.34f
        textPaint.color = Color.rgb(240, 210, 120)
        canvas.drawText(label, r.centerX(), r.centerY() + r.height() * 0.12f, textPaint)
    }

    // ---------- ввод ----------
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                pressedId = hit(e.getX(0), e.getY(0))
                invalidate()
            }
            android.view.MotionEvent.ACTION_UP -> {
                val id = hit(e.getX(0), e.getY(0))
                if (id != null && id == pressedId) activate(id)
                pressedId = null
                invalidate()
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                pressedId = null
                invalidate()
            }
        }
        return true
    }

    private fun hit(x: Float, y: Float): String? {
        for (h in hits) if (h.rect.contains(x, y)) return h.id
        return null
    }

    private fun activate(id: String) {
        when (state) {
            State.MAIN -> when (id) {
                "btn0" -> { state = State.NEWGAME; invalidate() }
                "btn1" -> { state = State.SETTINGS; invalidate() }
                "btn2" -> { state = State.ABOUT; invalidate() }
                "btn3" -> onQuit()
            }
            State.NEWGAME -> when (id) {
                "btn0" -> { setPref("difficulty", "easy"); onStartGame("easy") }
                "btn1" -> { setPref("difficulty", "normal"); onStartGame("normal") }
                "btn2" -> { setPref("difficulty", "hard"); onStartGame("hard") }
                "btn3" -> { state = State.MAIN; invalidate() }
            }
            State.SETTINGS -> when (id) {
                "btn0" -> {
                    val cur = prefs.getString("difficulty", "normal") ?: "normal"
                    val next = when (cur) {
                        "easy" -> "normal"; "normal" -> "hard"; else -> "easy"
                    }
                    setPref("difficulty", next)
                    invalidate()
                }
                "btn1" -> {
                    setPref("sound", !prefs.getBoolean("sound", true))
                    invalidate()
                }
                "btn2" -> {
                    setPref("difficulty", "normal")
                    setPref("sound", true)
                    invalidate()
                }
                "btn3" -> { state = State.MAIN; invalidate() }
            }
            State.ABOUT -> { state = State.MAIN; invalidate() }
        }
    }

    private fun setPref(key: String, value: Any) {
        prefs.edit().apply {
            when (value) {
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
            }
            apply()
        }
    }
}

private object Diff {
    fun label(key: String) = when (key) {
        "easy" -> "ЛЕГКО"
        "hard" -> "СЛОЖНО"
        else -> "НОРМАЛЬНО"
    }
}
