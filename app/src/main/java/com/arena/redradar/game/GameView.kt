package com.arena.redradar.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.arena.redradar.App
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Игровой экран: цикл на SurfaceView, рендер мира и HUD, тач-ввод.
 * Тач: тап — выбор/приказ, перетаскивание — камера, щипок — зум,
 * двойной тап — все юниты типа, тап по миникарте — переход.
 */
class GameView(
    context: Context,
    private val snd: Sound,
    difficulty: String,
    private val onExit: () -> kotlin.Unit
) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    private val sim = Sim(System.nanoTime(), difficulty)

    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var paused = false

    // камера
    private var camX = 0f
    private var camY = 0f
    private var zoom = 1f

    // ввод
    private var panMode = false
    private var pinchMode = false
    private var dragMoved = 0f
    private var downX = 0f; private var downY = 0f
    private var downT = 0L
    private var panPX = 0f; private var panPY = 0f
    private var pinchD = 0f; private var pinchMX = 0f; private var pinchMY = 0f
    private var lastTouchX = 0f; private var lastTouchY = 0f
    private var lastTapT = 0L
    private var lastTapX = -99f; private var lastTapY = -99f

    // состояние интерфейса
    private var placementKey: String? = null
    private var attackArmed = false
    private var buildPanelOpen = false
    private var trainPanelOpen = false

    // рендер
    private var terrBitmap: Bitmap? = null
    private var terrVersion = -1L
    private var needCenter = true

    // кнопки HUD (пересобираются каждый кадр)
    private class Btn(val x: Float, val y: Float, val w: Float, val h: Float,
                      val label: String, val action: String, val dim: Boolean = false)
    private val buttons = ArrayList<Btn>()
    private var mmRect = RectF()

    // краски
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val hudBgPaint = Paint().apply { color = Color.argb(215, 16, 18, 22) }

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        paint.isFilterBitmap = true
        Sprites.load(context)

        sim.onEvent = { type, _, _ ->
            when (type) {
                "alarm" -> snd.alarm()
                "boom" -> snd.boom()
                "built" -> snd.built()
                "trained" -> snd.trained()
                "needcredits" -> snd.order()
            }
        }

        val base = sim.playerBase(0)
        if (base != null) centerOn(base.x, base.y)
    }

    // ---------- SurfaceHolder.Callback ----------
    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!running) {
            running = true
            thread = Thread(this).also { it.start() }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (needCenter && width > 0) {
            val base = sim.playerBase(0)
            if (base != null) centerOn(base.x, base.y)
            needCenter = false
        }
        clampCamera()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        try { thread?.join() } catch (e: Exception) { }
        thread = null
    }

    // из Activity при уходе со экрана
    fun onActivityPause() { paused = true }
    fun onActivityResume() { paused = false }

    // ---------- цикл ----------
    override fun run() {
        try {
            val dt = 1f / 60f
            var prev = System.nanoTime()
            while (running) {
                val now = System.nanoTime()
                var elapsed = (now - prev) / 1_000_000_000f
                prev = now
                if (elapsed > 0.25f) elapsed = 0.25f
                if (!paused) {
                    var left = elapsed
                    while (left > 0f && running) {
                        val step = min(dt, left)
                        sim.update(step)
                        left -= step
                    }
                }
                val canvas = try { holder.lockCanvas() } catch (e: Exception) { null }
                if (canvas != null) {
                    try { drawFrame(canvas) } finally {
                        try { holder.unlockCanvasAndPost(canvas) } catch (e: Exception) { }
                    }
                }
            }
        } catch (t: Throwable) {
            // сбой игрового цикла: пишем стек и возвращаемся в меню вместо падения
            running = false
            try { App.instance.writeThrowable(t, "GameView.run") } catch (_: Throwable) { }
            try { onExit() } catch (_: Throwable) { }
        }
    }

    // ---------- рендер ----------
    private fun drawFrame(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawColor(Color.rgb(7, 9, 12))
        ensureTerrain()

        canvas.save()
        canvas.translate(-camX * zoom, -camY * zoom)
        canvas.scale(zoom, zoom)

        val tb = terrBitmap
        if (tb != null) canvas.drawBitmap(tb, null, Rect(0, 0, WORLD_W, WORLD_H), paint)

        drawBuildings(canvas)
        drawUnits(canvas)
        drawProjectiles(canvas)
        drawWorldUi(canvas)

        canvas.restore()

        drawHud(canvas, w, h)

        if (paused && sim.over == null) {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(140, 5, 7, 10)
            canvas.drawRect(0f, 0f, w, h, paint)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = w * 0.07f
            textPaint.color = Color.rgb(240, 210, 120)
            canvas.drawText("ПАУЗА", w / 2f, h / 2f, textPaint)
        }
    }

    private fun ensureTerrain() {
        val tb = terrBitmap
        if (tb != null && terrVersion == sim.map.version) return
        if (tb == null) {
            val b = Bitmap.createBitmap(WORLD_W, WORLD_H, Bitmap.Config.ARGB_8888)
            b.eraseColor(Color.rgb(7, 9, 12))
            terrBitmap = b
        }
        val bb = terrBitmap!!
        val c = Canvas(bb)
        for (y in 0 until MAP_H) for (x in 0 until MAP_W) drawTile(c, x, y)
        terrVersion = sim.map.version
    }

    private fun drawTile(c: Canvas, tx: Int, ty: Int) {
        val i = sim.map.idx(tx, ty)
        val t = sim.map.terrain[i]
        val x = tx * TILE.toFloat(); val y = ty * TILE.toFloat()
        val hv = (tx * 73856093 xor ty * 19349663) and 0x7FFFFFFF
        val bmp = Sprites.tile(t, (hv % 3).toInt())
        if (bmp != null) {
            c.drawBitmap(bmp, null, RectF(x, y, x + TILE, y + TILE), paint)
            return
        }
        // запасной вариант: цветные квадраты (если ассеты не загрузились)
        val r1 = (hv % 1000) / 1000f
        val r2 = ((hv / 7) % 1000) / 1000f

        paint.style = Paint.Style.FILL
        paint.color = when (t) {
            Terrain.WATER -> Color.rgb(34 + (r1 * 10).toInt(), 74 + (r2 * 12).toInt(), 116 + (r1 * 14).toInt())
            Terrain.DIRT -> Color.rgb(104 + (r1 * 18).toInt(), 84 + (r2 * 14).toInt(), 56 + (r1 * 12).toInt())
            else -> Color.rgb(52 + (r1 * 12).toInt(), 86 + (r1 * 18).toInt(), 40 + (r2 * 10).toInt())
        }
        c.drawRect(x, y, x + TILE, y + TILE, paint)
    }

    private fun playerColor(pid: Int, dark: Boolean): Int =
        if (pid == 0) {
            if (dark) Color.rgb(140, 32, 32) else Color.rgb(206, 62, 62)
        } else {
            if (dark) Color.rgb(34, 62, 130) else Color.rgb(74, 120, 216)
        }

    private fun drawUnits(canvas: Canvas) {
        for (u in sim.units) {
            val r = u.def.radius
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(70, 0, 0, 0)
            canvas.drawCircle(u.x, u.y + r * 0.5f, r * 0.9f, paint)
            if (u.selected) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                paint.color = if (u.owner == 0) Color.rgb(140, 245, 155) else Color.rgb(255, 130, 130)
                canvas.drawCircle(u.x, u.y, r + 4f, paint)
                paint.style = Paint.Style.FILL
            }

            val sz = r * 2.7f
            if (u.def.key == "tank") {
                val hull = Sprites.rotated("tank_" + if (u.owner == 0) "red" else "blue", u.face)
                val tur = Sprites.rotated("tank_turret_" + if (u.owner == 0) "red" else "blue", u.turretA)
                if (hull != null && tur != null) {
                    val rf = RectF(u.x - sz, u.y - sz, u.x + sz, u.y + sz)
                    canvas.drawBitmap(hull, null, rf, paint)
                    canvas.drawBitmap(tur, null, rf, paint)
                } else {
                    drawUnitFallback(canvas, u, r)
                }
            } else {
                val bmp = Sprites.rotated(u.def.key + "_" + if (u.owner == 0) "red" else "blue", u.face)
                if (bmp != null) {
                    canvas.drawBitmap(bmp, null, RectF(u.x - sz, u.y - sz, u.x + sz, u.y + sz), paint)
                } else {
                    drawUnitFallback(canvas, u, r)
                }
            }

            if (u.carry > 0.5f) {
                paint.color = Color.rgb(242, 197, 61)
                canvas.drawCircle(u.x, u.y - r - 5f, 3.5f, paint)
            }
            if (u.flashT > 0f) {
                paint.color = Color.argb((min(0.75f, u.flashT * 4f) * 255).toInt(), 255, 255, 255)
                canvas.drawCircle(u.x, u.y, r, paint)
            }
            if (u.hp < u.maxHp) {
                drawBar(canvas, u.x - 11f, u.y - r - 10f, 22f, 3.5f, u.hp / u.maxHp)
            }
        }
    }

    /** Запасная отрисовка юнита кругами (без спрайтов). */
    private fun drawUnitFallback(canvas: Canvas, u: GameUnit, r: Float) {
        if (u.def.key == "tank") {
            paint.color = Color.argb(170, 40, 40, 44)
            canvas.drawRect(u.x - r, u.y - r * 0.9f, u.x + r, u.y + r * 0.9f, paint)
        }
        paint.color = playerColor(u.owner, false)
        canvas.drawCircle(u.x, u.y, r, paint)
        val fx = u.x + cos(u.face) * r * 0.6f
        val fy = u.y + sin(u.face) * r * 0.6f
        paint.color = playerColor(u.owner, true)
        canvas.drawCircle(fx, fy, r * 0.45f, paint)
    }

    private fun drawBuildings(canvas: Canvas) {
        for (b in sim.buildings) {
            val x0 = b.tx * TILE.toFloat(); val y0 = b.ty * TILE.toFloat()
            val s = b.size * TILE.toFloat()
            paint.style = Paint.Style.FILL

            if (!b.constructed) {
                paint.color = Color.argb(150, 28, 32, 38)
                canvas.drawRect(x0, y0, x0 + s, y0 + s, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                paint.color = Color.rgb(196, 164, 92)
                canvas.drawRect(x0 + 2, y0 + 2, x0 + s - 2, y0 + s - 2, paint)
                paint.style = Paint.Style.FILL
                paint.color = playerColor(b.owner, true)
                val hf = (s - 10) * b.progress
                canvas.drawRect(x0 + 5, y0 + s - 5 - hf, x0 + s - 5, y0 + s - 5, paint)
                drawBar(canvas, x0 + 4, y0 - 9, s - 8, 6f, b.progress, Color.rgb(232, 193, 79))
                continue
            }

            val drawn = drawBuildingSprite(canvas, b, x0, y0, s)
            if (!drawn) {
                paint.color = playerColor(b.owner, true)
                canvas.drawRect(x0 + 3, y0 + s * 0.3f, x0 + s - 3, y0 + s - 3, paint)
                paint.color = playerColor(b.owner, false)
                canvas.drawRect(x0 + 6, y0 + 6, x0 + s - 6, y0 + s * 0.34f, paint)

                textPaint.color = Color.argb(230, 255, 255, 255)
                textPaint.textSize = s * 0.22f
                textPaint.textAlign = Paint.Align.CENTER
                val label = when (b.def.key) {
                    "base" -> "ШТ"
                    "power" -> "ЭН"
                    "barracks" -> "КАЗ"
                    "factory" -> "ЗАВ"
                    "turret" -> "ТУР"
                    else -> "?"
                }
                canvas.drawText(label, b.x, y0 + s * 0.28f, textPaint)

                if (b.def.key == "turret") {
                    paint.style = Paint.Style.FILL
                    paint.color = Color.rgb(120, 126, 134)
                    canvas.drawCircle(b.x, b.y, s * 0.3f, paint)
                    paint.color = Color.rgb(61, 66, 73)
                    canvas.save()
                    canvas.rotate(b.barrelA * 180f / Math.PI.toFloat(), b.x, b.y)
                    canvas.drawRect(b.x, b.y - 3.5f, b.x + s * 0.5f, b.y + 3.5f, paint)
                    canvas.restore()
                }
            }

            if (b.flashT > 0f) {
                paint.style = Paint.Style.FILL
                paint.color = Color.argb((min(0.6f, b.flashT * 3.5f) * 255).toInt(), 255, 255, 255)
                canvas.drawRect(x0, y0, x0 + s, y0 + s, paint)
            }

            if (b.queue.isNotEmpty()) {
                val q = b.queue[0]
                drawBar(canvas, x0 + 4, y0 - 9, s - 8, 6f, 1f - q.t / q.tMax, Color.rgb(111, 180, 247))
            }
            if (b.selected) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                paint.color = if (b.owner == 0) Color.rgb(140, 245, 155) else Color.rgb(255, 130, 130)
                canvas.drawRect(x0, y0, x0 + s, y0 + s, paint)
            }
        }
        paint.style = Paint.Style.FILL
    }

    /** Спрайтовая отрисовка достроенного здания; false — нет спрайта, рисовать фигурами. */
    private fun drawBuildingSprite(canvas: Canvas, b: Building, x0: Float, y0: Float, s: Float): Boolean {
        val facn = if (b.owner == 0) "red" else "blue"
        if (b.def.key == "turret") {
            val base = Sprites.turretBase(b.owner)
            val top = Sprites.rotated("turret_top_$facn", b.barrelA)
            if (base != null && top != null) {
                val rf = RectF(x0, y0, x0 + s, y0 + s)
                canvas.drawBitmap(base, null, rf, paint)
                canvas.drawBitmap(top, null, rf, paint)
                return true
            }
            return false
        }
        val bmp = Sprites.building(b.def.key, b.owner) ?: return false
        canvas.drawBitmap(bmp, null, RectF(x0, y0, x0 + s, y0 + s), paint)
        return true
    }

    private fun drawProjectiles(canvas: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.rgb(232, 223, 200)
        for (p in sim.projectiles) {
            canvas.drawLine(p.x, p.y, p.x - cos(p.angle) * 10f, p.y - sin(p.angle) * 10f, paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawWorldUi(canvas: Canvas) {
        val pk = placementKey
        if (pk != null) {
            val def = Defs.buildings[pk] ?: return
            val wx = camX + lastTouchX / zoom
            val wy = camY + lastTouchY / zoom
            val tx = (wx / TILE - def.size / 2f).roundToInt()
            val ty = (wy / TILE - def.size / 2f).roundToInt()
            val ok = sim.canPlaceAt(def, tx, ty) && sim.canAfford(0, def.cost)
            val px = tx * TILE.toFloat(); val py = ty * TILE.toFloat()
            val ps = def.size * TILE.toFloat()
            paint.style = Paint.Style.FILL
            paint.color = if (ok) Color.argb(110, 110, 220, 130) else Color.argb(110, 230, 90, 90)
            canvas.drawRect(px, py, px + ps, py + ps, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = if (ok) Color.rgb(150, 255, 160) else Color.rgb(255, 120, 120)
            canvas.drawRect(px, py, px + ps, py + ps, paint)
            if (def.range > 0f) {
                paint.color = Color.argb(120, 150, 200, 255)
                canvas.drawCircle(px + ps / 2f, py + ps / 2f, def.range, paint)
            }
            paint.style = Paint.Style.FILL
        }
        if (attackArmed) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = Color.argb(200, 255, 110, 110)
            val cx = camX + lastTouchX / zoom
            val cy = camY + lastTouchY / zoom
            canvas.drawCircle(cx, cy, 26f, paint)
            paint.style = Paint.Style.FILL
        }
    }

    private fun drawBar(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, pct: Float, color: Int = -1) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(200, 12, 14, 18)
        canvas.drawRect(x - 1, y - 1, x + w + 1, y + h + 1, paint)
        paint.color = if (color != -1) color
        else if (pct > 0.55f) Color.rgb(103, 217, 85)
        else if (pct > 0.28f) Color.rgb(232, 193, 79) else Color.rgb(224, 85, 85)
        canvas.drawRect(x, y, x + w * pct.coerceIn(0f, 1f), y + h, paint)
    }

    // ---------- HUD ----------
    private fun drawHud(canvas: Canvas, w: Float, h: Float) {
        buttons.clear()

        val topH = (34f * (w / 900f)).coerceIn(30f, 44f)
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, w, topH, hudBgPaint)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = topH * 0.5f
        textPaint.color = Color.rgb(242, 197, 61)
        canvas.drawText("₽ ${sim.credits[0].roundToInt()}", 12f, topH * 0.68f, textPaint)

        val prov = sim.powerProvided(0)
        val used = sim.powerUsed(0)
        textPaint.color = if (used >= prov) Color.rgb(255, 110, 110) else Color.rgb(160, 210, 255)
        canvas.drawText("⚡ $used/$prov", 118f, topH * 0.68f, textPaint)

        textPaint.color = Color.rgb(200, 205, 212)
        canvas.drawText("Юниты ${sim.unitCount(0)}/${Defs.UNIT_CAP}", 220f, topH * 0.68f, textPaint)

        val bs = topH * 0.72f
        val btnPause = Btn(w - bs - 10f, 4f, bs, bs, "⏸", "pause")
        val btnMenu = Btn(w - bs * 2 - 16f, 4f, bs, bs, "☰", "menu")
        buttons.add(btnPause); buttons.add(btnMenu)
        drawButton(canvas, btnPause, topH * 0.44f)
        drawButton(canvas, btnMenu, topH * 0.44f)

        // миникарта
        val mmS = (w * 0.19f).coerceIn(110f, 190f)
        val mmX = w - mmS - 8f
        val mmY = h - mmS - 8f
        drawMinimap(canvas, mmX, mmY, mmS)

        // нижняя панель команд
        val barY = h - 56f
        val barH = 52f
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, barY, w, barY + barH, hudBgPaint)

        val cmds = listOf(
            Triple("СТРОИТЬ", "build", buildPanelOpen),
            Triple("ВОЙСКА", "train", trainPanelOpen),
            Triple("СТОП", "stop", false),
            Triple("АТАКА", "attack", attackArmed),
            Triple("АРМИЯ", "army", false),
            Triple("ШТАБ", "base", false)
        )
        var bx = 8f
        val bw = 80f
        for ((label, action, hl) in cmds) {
            if (mmY < barY + barH && bx + bw > mmX) break
            val b = Btn(bx, barY + 4, bw, barH - 8, label, action)
            buttons.add(b)
            drawButton(canvas, b, 13f, highlight = hl)
            bx += bw + 6f
        }

        if (buildPanelOpen) drawBuildPanel(canvas, w, barY)
        if (trainPanelOpen) drawTrainPanel(canvas, w, barY)

        drawSelectionCard(canvas)

        val over = sim.over
        if (over != null) {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(180, 5, 7, 10)
            canvas.drawRect(0f, 0f, w, h, paint)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = w * 0.09f
            textPaint.color = if (over == "win") Color.rgb(142, 245, 155) else Color.rgb(255, 128, 128)
            canvas.drawText(if (over == "win") "ПОБЕДА" else "ПОРАЖЕНИЕ", w / 2f, h / 2f, textPaint)
            textPaint.textSize = w * 0.032f
            textPaint.color = Color.rgb(200, 205, 212)
            canvas.drawText("Коснитесь экрана, чтобы выйти в меню", w / 2f, h / 2f + w * 0.06f, textPaint)
        }
    }

    private fun drawButton(canvas: Canvas, b: Btn, textSize: Float, highlight: Boolean = false) {
        paint.style = Paint.Style.FILL
        paint.color = if (highlight) Color.rgb(190, 62, 62) else Color.rgb(96, 26, 26)
        canvas.drawRoundRect(RectF(b.x, b.y, b.x + b.w, b.y + b.h), 8f, 8f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = Color.argb(190, 210, 190, 150)
        canvas.drawRoundRect(RectF(b.x, b.y, b.x + b.w, b.y + b.h), 8f, 8f, paint)
        paint.style = Paint.Style.FILL
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = textSize
        textPaint.color = if (b.dim) Color.argb(150, 240, 210, 120) else Color.rgb(240, 210, 120)
        canvas.drawText(b.label, b.x + b.w / 2f, b.y + b.h / 2f + textSize * 0.35f, textPaint)
    }

    private fun drawBuildPanel(canvas: Canvas, w: Float, barY: Float) {
        val pad = 8f
        val bw = 96f; val bh = 48f
        var x = pad; var y = barY - bh - 6f
        for (key in Defs.buildMenu) {
            val def = Defs.buildings[key] ?: continue
            val dim = !sim.canAfford(0, def.cost)
            val b = Btn(x, y, bw, bh, def.name, "place:$key", dim)
            buttons.add(b)
            drawButton(canvas, b, 11f)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 10f
            textPaint.color = if (dim) Color.argb(170, 210, 170, 170) else Color.rgb(220, 226, 232)
            val pw = if (def.powerGive > 0) "ЭН+${def.powerGive}" else "ЭН-${def.powerUse}"
            canvas.drawText("₽${def.cost}  $pw", b.x + bw / 2f, b.y + bh - 4f, textPaint)
            x += bw + 6f
            if (x + bw > w - mmRect.width() - 16f) { x = pad; y -= bh + 6f }
        }
        val cancel = Btn(pad, y - bh - 6f, bw, bh, "ОТМЕНА", "cancelplace")
        buttons.add(cancel)
        drawButton(canvas, cancel, 11f)
    }

    private fun drawTrainPanel(canvas: Canvas, w: Float, barY: Float) {
        val selB = sim.buildings.firstOrNull { it.selected && !it.dead && it.constructed && it.def.trains.isNotEmpty() }
            ?: return
        val pad = 8f
        val bw = 104f; val bh = 48f
        var x = pad
        val y = barY - bh - 6f
        for (key in selB.def.trains) {
            val def = Defs.units[key] ?: continue
            val dim = !sim.canAfford(0, def.cost) || sim.unitCount(0) >= Defs.UNIT_CAP
            val cnt = selB.queue.count { it.key == key }
            val btn = Btn(x, y, bw, bh, def.name + if (cnt > 0) " ($cnt)" else "", "train:$key", dim)
            buttons.add(btn)
            drawButton(canvas, btn, 11f)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 10f
            textPaint.color = if (dim) Color.argb(170, 210, 170, 170) else Color.rgb(220, 226, 232)
            canvas.drawText("₽${def.cost}", btn.x + bw / 2f, btn.y + bh - 4f, textPaint)
            x += bw + 6f
        }
        val cancel = Btn(x, y, bw, bh, "ОТМЕНА", "canceltrain")
        buttons.add(cancel)
        drawButton(canvas, cancel, 11f)
    }

    private fun drawSelectionCard(canvas: Canvas) {
        val sel = ArrayList<Entity>()
        for (u in sim.units) if (u.selected && !u.dead) sel.add(u)
        for (b in sim.buildings) if (b.selected && !b.dead) sel.add(b)
        if (sel.isEmpty()) return
        val x = 8f
        val y = 42f
        val cw = 172f
        val ch = if (sel.size == 1) 56f else 38f
        paint.style = Paint.Style.FILL
        canvas.drawRect(x, y, x + cw, y + ch, hudBgPaint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.argb(120, 255, 255, 255)
        canvas.drawRect(x, y, x + cw, y + ch, paint)
        paint.style = Paint.Style.FILL

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 13f
        textPaint.color = Color.rgb(230, 234, 240)
        val first = sel[0]
        val title: String = if (sel.size == 1) {
            when (first) {
                is GameUnit -> first.def.name
                is Building -> first.def.name
                else -> "?"
            }
        } else "Выбрано: ${sel.size}"
        canvas.drawText(title, x + 8f, y + 16f, textPaint)

        if (sel.size == 1) {
            drawBar(canvas, x + 8f, y + 24f, cw - 16f, 6f, first.hp / first.maxHp)
            textPaint.textSize = 10f
            textPaint.color = Color.rgb(170, 176, 185)
            val sub: String = when (first) {
                is GameUnit -> if (first.def.isMiner && first.carry > 0f)
                    "руда: ${first.carry.roundToInt()}" else "урон ${first.def.dmg.roundToInt()}"
                is Building -> if (!first.constructed) "стройка ${(first.progress * 100).roundToInt()}%"
                else {
                    if (first.def.powerUse > 0) "энергия -${first.def.powerUse}"
                    else if (first.def.powerGive > 0) "энергия +${first.def.powerGive}"
                    else first.def.name
                }
                else -> ""
            }
            canvas.drawText(sub, x + 8f, y + 46f, textPaint)
        }
    }

    private fun drawMinimap(canvas: Canvas, x: Float, y: Float, s: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(10, 12, 15)
        canvas.drawRect(x, y, x + s, y + s, paint)
        val tb = terrBitmap
        if (tb != null) {
            canvas.drawBitmap(tb, null, Rect(x.toInt(), y.toInt(), (x + s).toInt(), (y + s).toInt()), paint)
        }
        val k = s / WORLD_W
        for (b in sim.buildings) {
            paint.color = playerColor(b.owner, false)
            val sz = (b.size * TILE * k).coerceAtLeast(3f)
            canvas.drawRect(b.x * k - sz / 2, b.y * k - sz / 2, b.x * k + sz / 2, b.y * k + sz / 2, paint)
        }
        for (u in sim.units) {
            paint.color = playerColor(u.owner, true)
            canvas.drawRect(u.x * k - 1.5f, u.y * k - 1.5f, u.x * k + 1.5f, u.y * k + 1.5f, paint)
        }
        for (a in sim.alarms) {
            val blink = (sim.time * 3f) % 1f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = Color.argb(((1f - blink) * 255).toInt(), 255, 80, 80)
            canvas.drawCircle(a.first * k, a.second * k, 3f + blink * 10f, paint)
            paint.style = Paint.Style.FILL
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = Color.argb(220, 255, 255, 255)
        canvas.drawRect(
            x + camX * k, y + camY * k,
            x + (camX + width / zoom) * k, y + (camY + height / zoom) * k, paint
        )
        paint.style = Paint.Style.FILL
        mmRect = RectF(x, y, x + s, y + s)
    }

    // ---------- камера ----------
    private fun clampCamera() {
        val vw = width / zoom
        val vh = height / zoom
        camX = camX.coerceIn(0f, max(0f, WORLD_W - vw))
        camY = camY.coerceIn(0f, max(0f, WORLD_H - vh))
    }

    private fun centerOn(wx: Float, wy: Float) {
        camX = wx - width / (2f * zoom)
        camY = wy - height / (2f * zoom)
        clampCamera()
    }

    // ---------- ввод ----------
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                panMode = true; pinchMode = false
                dragMoved = 0f
                downX = e.getX(0); downY = e.getY(0)
                downT = System.currentTimeMillis()
                panPX = downX; panPY = downY
                lastTouchX = downX; lastTouchY = downY
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount >= 2) {
                    pinchMode = true; panMode = false
                    dragMoved = 999f
                    pinchD = pinchDist(e)
                    pinchMX = (e.getX(0) + e.getX(1)) / 2f
                    pinchMY = (e.getY(0) + e.getY(1)) / 2f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pinchMode && e.pointerCount >= 2) {
                    val d = pinchDist(e)
                    val mx = (e.getX(0) + e.getX(1)) / 2f
                    val my = (e.getY(0) + e.getY(1)) / 2f
                    if (pinchD > 0f) zoomAt(mx, my, (d / pinchD).coerceIn(0.6f, 1.6f))
                    camX -= (mx - pinchMX) / zoom
                    camY -= (my - pinchMY) / zoom
                    clampCamera()
                    pinchD = d; pinchMX = mx; pinchMY = my
                    lastTouchX = mx; lastTouchY = my
                } else if (panMode) {
                    val x = e.getX(0); val y = e.getY(0)
                    dragMoved += abs(x - panPX) + abs(y - panPY)
                    camX -= (x - panPX) / zoom
                    camY -= (y - panPY) / zoom
                    clampCamera()
                    panPX = x; panPY = y
                    lastTouchX = x; lastTouchY = y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (pinchMode && e.pointerCount <= 2) {
                    val idx = if (e.actionIndex == 0) 1 else 0
                    panPX = e.getX(idx); panPY = e.getY(idx)
                    pinchMode = false; panMode = true
                    dragMoved = 999f
                }
            }
            MotionEvent.ACTION_UP -> {
                val dt = System.currentTimeMillis() - downT
                val moved = dragMoved
                val wasPinch = pinchMode
                pinchMode = false; panMode = false
                if (!wasPinch && moved <= 16f && dt < 500f) handleTap(downX, downY)
            }
            MotionEvent.ACTION_CANCEL -> {
                pinchMode = false; panMode = false
            }
        }
        return true
    }

    private fun pinchDist(e: MotionEvent): Float =
        hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

    private fun zoomAt(sx: Float, sy: Float, factor: Float) {
        val wx = camX + sx / zoom
        val wy = camY + sy / zoom
        zoom = (zoom * factor).coerceIn(0.45f, 1.8f)
        camX = wx - sx / zoom
        camY = wy - sy / zoom
        clampCamera()
    }

    private fun handleTap(sx: Float, sy: Float) {
        val now = System.currentTimeMillis()
        val dbl = now - lastTapT < 350 && hypot(sx - lastTapX, sy - lastTapY) < 40f
        lastTapT = now
        lastTapX = sx; lastTapY = sy

        // конец игры — выход в меню
        if (sim.over != null) { onExit(); return }

        // кнопки HUD (работают и на паузе)
        for (b in buttons) {
            if (sx >= b.x && sx <= b.x + b.w && sy >= b.y && sy <= b.y + b.h) {
                onButton(b.action, b.dim)
                return
            }
        }
        // на паузе остальной ввод игнорируем
        if (paused) return

        // миникарта
        if (mmRect.width() > 0f && mmRect.contains(sx, sy)) {
            centerOn(
                (sx - mmRect.left) / mmRect.width() * WORLD_W,
                (sy - mmRect.top) / mmRect.height() * WORLD_H
            )
            return
        }
        // нижняя зона — не для мира
        if (sy > height - 62f) return

        val wx = camX + sx / zoom
        val wy = camY + sy / zoom
        val ent = sim.entityAt(wx, wy)

        // размещение здания
        val pk = placementKey
        if (pk != null) {
            val def = Defs.buildings[pk]!!
            val tx = (wx / TILE - def.size / 2f).roundToInt()
            val ty = (wy / TILE - def.size / 2f).roundToInt()
            val site = sim.placeBuilding(0, pk, tx, ty)
            if (site != null) {
                placementKey = null
                snd.built()
            } else {
                snd.order()
            }
            return
        }
        // атака-движение
        if (attackArmed) {
            attackArmed = false
            val army = sim.units.filter { it.selected && !it.dead }
            sim.issueMove(army, wx, wy, attackMove = true)
            snd.order()
            return
        }

        if (ent != null && ent.owner == 0) {
            if (dbl && ent is GameUnit) {
                val same = sim.units.filter {
                    !it.dead && it.owner == 0 && it.def === ent.def &&
                        it.x >= camX && it.x <= camX + width / zoom &&
                        it.y >= camY && it.y <= camY + height / zoom
                }
                select(same.ifEmpty { listOf(ent) })
            } else {
                select(listOf(ent))
            }
            snd.click()
            return
        }

        val sel = sim.units.filter { it.selected && !it.dead }
        if (sel.isNotEmpty()) {
            if (ent != null && ent.owner != 0) {
                sim.issueAttack(sel, ent)
                snd.order()
                return
            }
            val tx = (wx / TILE).toInt()
            val ty = (wy / TILE).toInt()
            if (sim.map.isOre(tx, ty)) {
                val miners = sel.filter { it.def.isMiner }
                val rest = sel.filter { !it.def.isMiner }
                if (miners.isNotEmpty()) sim.issueHarvest(miners[0], tx, ty)
                if (rest.isNotEmpty()) sim.issueMove(rest, wx, wy)
                snd.order()
                return
            }
            val b = sim.buildings.firstOrNull {
                it.selected && !it.dead && it.constructed && it.def.trains.isNotEmpty()
            }
            if (b != null) {
                sim.setRally(b, wx, wy, ent?.id ?: -1)
                snd.order()
                return
            }
            sim.issueMove(sel, wx, wy)
            snd.order()
            return
        }
        select(if (ent != null) listOf(ent) else emptyList())
    }

    private fun select(list: List<Entity>) {
        for (u in sim.units) u.selected = false
        for (b in sim.buildings) b.selected = false
        for (e in list) e.selected = true
        buildPanelOpen = false
        trainPanelOpen = false
    }

    private fun onButton(action: String, dim: Boolean) {
        if (action != "menu" && action != "pause" && dim) return
        when (action) {
            "menu" -> onExit()
            "pause" -> paused = !paused
            "build" -> { buildPanelOpen = !buildPanelOpen; trainPanelOpen = false; placementKey = null }
            "train" -> {
                val sel = sim.buildings.any {
                    it.selected && !it.dead && it.constructed && it.def.trains.isNotEmpty()
                }
                if (sel) { trainPanelOpen = !trainPanelOpen; buildPanelOpen = false }
            }
            "stop" -> sim.stopUnits(sim.units.filter { it.selected })
            "attack" -> { attackArmed = !attackArmed; placementKey = null }
            "army" -> select(sim.units.filter { !it.dead && it.owner == 0 && !it.def.isMiner })
            "base" -> {
                val b = sim.playerBase(0)
                if (b != null) { select(listOf(b)); centerOn(b.x, b.y) }
            }
            "cancelplace" -> { placementKey = null; buildPanelOpen = false }
            "canceltrain" -> {
                val b = sim.buildings.firstOrNull { it.selected && !it.dead && it.constructed }
                if (b != null) sim.cancelTrain(b)
            }
            else -> {
                if (action.startsWith("place:")) {
                    placementKey = action.substringAfter("place:")
                    buildPanelOpen = false
                    attackArmed = false
                } else if (action.startsWith("train:")) {
                    val b = sim.buildings.firstOrNull {
                        it.selected && !it.dead && it.constructed && it.def.trains.isNotEmpty()
                    }
                    if (b != null) sim.train(b, action.substringAfter("train:"))
                }
            }
        }
    }
}
