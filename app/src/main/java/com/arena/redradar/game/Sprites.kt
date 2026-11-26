package com.arena.redradar.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix

/**
 * Загрузчик рисованных спрайтов из assets/sprites.
 * Все рисунки сгенерированы tools/gen_sprites.py и ориентированы «вправо» (+x);
 * поворот делается заранее и кэшируется с шагом 6° (60 позиций на оборот).
 * Если ассетов нет (старая сборка) — возвращает null, GameView рисует фигуры.
 */
object Sprites {
    private var ctx: Context? = null
    private val cache = HashMap<String, Bitmap?>()
    private val rotated = HashMap<String, Bitmap>()
    private var failed = false

    const val ROT_STEP = 6f

    fun load(context: Context) {
        ctx = context.applicationContext
        // прогрев: декодируем всё заранее (31 файл, ~150 КБ)
        val names = listOf(
            "grass0", "grass1", "grass2", "dirt0", "dirt1", "water0", "water1", "ore0", "ore1",
            "tank_red", "tank_turret_red", "miner_red", "rifle_red", "rocket_red",
            "tank_blue", "tank_turret_blue", "miner_blue", "rifle_blue", "rocket_blue",
            "base_red", "power_red", "barracks_red", "factory_red",
            "turret_base_red", "turret_top_red",
            "base_blue", "power_blue", "barracks_blue", "factory_blue",
            "turret_base_blue", "turret_top_blue"
        )
        for (n in names) raw(n)
    }

    private fun raw(name: String): Bitmap? {
        if (failed) return null
        if (cache.containsKey(name)) return cache[name]
        val b = try {
            val c = ctx!!
            c.assets.open("sprites/$name.png").use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            failed = true
            null
        }
        cache[name] = b
        return b
    }

    private fun fac(owner: Int) = if (owner == 0) "red" else "blue"

    /** Тайл местности (вариант выбирает вызывающий, отсечётся к существующему). */
    fun tile(t: Int, variant: Int): Bitmap? {
        val name = when (t) {
            Terrain.WATER -> "water"
            Terrain.DIRT -> "dirt"
            Terrain.ORE -> "ore"
            else -> "grass"
        }
        val maxV = if (t == Terrain.GRASS) 3 else 2
        return raw(name + (variant % maxV))
    }

    fun unit(key: String, owner: Int): Bitmap? = raw(key + "_" + fac(owner))

    fun tankHull(owner: Int): Bitmap? = raw("tank_" + fac(owner))
    fun tankTurret(owner: Int): Bitmap? = raw("tank_turret_" + fac(owner))

    fun building(key: String, owner: Int): Bitmap? =
        if (key == "base" || key == "power" || key == "barracks" || key == "factory")
            raw(key + "_" + fac(owner))
        else null

    fun turretBase(owner: Int): Bitmap? = raw("turret_base_" + fac(owner))
    fun turretTop(owner: Int): Bitmap? = raw("turret_top_" + fac(owner))

    /** Поворот спрайта на угол (радианы, 0 = вправо), кэш с шагом ROT_STEP. */
    fun rotated(name: String, angleRad: Float): Bitmap? {
        val src = raw(name) ?: return null
        var a = angleRad * 180f / Math.PI.toFloat() % 360f
        if (a < 0f) a += 360f
        val step = (a / ROT_STEP).toInt() * ROT_STEP
        val ck = "$name@$step"
        rotated[ck]?.let { return it }
        val m = Matrix()
        m.postRotate(step, src.width / 2f, src.height / 2f)
        val b = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        rotated[ck] = b
        return b
    }
}
