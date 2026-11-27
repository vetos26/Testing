package com.arena.redradar.engine

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.random.Random

/* Размер тайла (TILE) — в Mod.kt движка. */

object Terrain {
    const val GRASS = 0
    const val DIRT = 1
    const val WATER = 2
    const val ORE = 3
}

/** Значение-шум [0..1] на вещественной сетке. */
fun makeNoise(seed: Long): (Float, Float) -> Float {
    val rnd = Random(seed)
    val h = IntArray(512) { rnd.nextInt() }
    fun hash(x: Int, y: Int): Float {
        var v = h[x and 255] xor h[256 + (y and 255)]
        v = v * 0x2C1B3C6D.toInt()
        v = v xor (v ushr 15)
        return abs(v % 10000) / 10000f
    }
    fun smooth(t: Float) = t * t * (3f - 2f * t)
    return { x, y ->
        val xi = floor(x).toInt(); val yi = floor(y).toInt()
        val xf = smooth(x - xi); val yf = smooth(y - yi)
        val a = hash(xi, yi); val b = hash(xi + 1, yi)
        val c = hash(xi, yi + 1); val d = hash(xi + 1, yi + 1)
        (a * (1 - xf) + b * xf) * (1 - yf) + (c * (1 - xf) + d * xf) * yf
    }
}

class GameMap(val mod: Mod, val seed: Long) {
    val w = mod.mapW
    val h = mod.mapH
    val terrain = IntArray(mod.mapW * mod.mapH)
    val ore = IntArray(mod.mapW * mod.mapH)
    val bgrid = IntArray(mod.mapW * mod.mapH)
    val starts = mod.mapGen.starts
    var version = 0L

    fun idx(x: Int, y: Int) = y * w + x

    fun inBounds(x: Int, y: Int) = x >= 0 && y >= 0 && x < w && y < h

    fun terrainPassable(x: Int, y: Int): Boolean {
        if (!inBounds(x, y)) return false
        val t = terrain[idx(x, y)]
        return t == Terrain.GRASS || t == Terrain.DIRT
    }

    fun isPassable(x: Int, y: Int): Boolean {
        if (!inBounds(x, y)) return false
        val i = idx(x, y)
        if (bgrid[i] != 0) return false
        return terrainPassable(x, y)
    }

    fun setBuilding(tx: Int, ty: Int, size: Int, id: Int) {
        for (y in ty until ty + size) for (x in tx until tx + size) {
            if (inBounds(x, y)) bgrid[idx(x, y)] = id
        }
        version++
    }

    fun isOre(x: Int, y: Int): Boolean =
        inBounds(x, y) && terrain[idx(x, y)] == Terrain.ORE && ore[idx(x, y)] > 0

    /** Забрать руду из тайла, возвращает сколько удалось взять. */
    fun takeOre(x: Int, y: Int, amt: Int): Int {
        if (!isOre(x, y)) return 0
        val i = idx(x, y)
        val taken = minOf(amt, ore[i])
        ore[i] -= taken
        if (ore[i] <= 0) {
            terrain[i] = Terrain.GRASS
            ore[i] = 0
            version++
        }
        return taken
    }

    fun nearestOre(px: Float, py: Float, maxR: Int = 18): Pair<Int, Int>? {
        val cx = floor(px / TILE).toInt()
        val cy = floor(py / TILE).toInt()
        for (r in 0..maxR) {
            var best: Pair<Int, Int>? = null
            var bestD = Int.MAX_VALUE
            for (dy in -r..r) for (dx in -r..r) {
                if (maxOf(abs(dx), abs(dy)) != r) continue
                val x = cx + dx; val y = cy + dy
                if (!isOre(x, y)) continue
                val d = dx * dx + dy * dy
                if (d < bestD) { bestD = d; best = Pair(x, y) }
            }
            if (best != null) return best
        }
        return null
    }

    /** Свободна ли площадка size×size с углом (tx, ty). */
    fun canPlace(tx: Int, ty: Int, size: Int): Boolean {
        if (tx < 1 || ty < 1 || tx + size >= w - 1 || ty + size >= h - 1) return false
        for (y in ty until ty + size) for (x in tx until tx + size) {
            val i = idx(x, y)
            if (bgrid[i] != 0) return false
            if (terrain[i] == Terrain.WATER || terrain[i] == Terrain.ORE) return false
        }
        return true
    }

    fun angleToOre(x: Int, y: Int): Float {
        val o = nearestOre(x * TILE.toFloat(), y * TILE.toFloat(), 1)
        return if (o != null) atan2((o.second - y).toFloat(), (o.first - x).toFloat()) else 0f
    }

    fun hyp(x1: Float, y1: Float, x2: Float, y2: Float): Float = hypot(x2 - x1, y2 - y1)

    fun distanceTiles(a: Pair<Int, Int>, b: Pair<Int, Int>): Float =
        hypot((a.first - b.first).toFloat(), (a.second - b.second).toFloat())

    fun nearBase(x: Int, y: Int, r: Int): Boolean {
        for (s in starts) {
            val c = Pair(s.first + 1, s.second + 1)
            if (maxOf(abs(x - c.first), abs(y - c.second)) < r) return true
        }
        return false
    }

    fun atan(x: Float, y: Float): Float = atan2(y, x)

    init {
        generate()
    }

    /** Генерация: вода/грязь шумом, россыпи руды у баз и в центре, коридор между базами. */
    private fun generate() {
        val rnd = Random(seed)
        val nWater = makeNoise(seed xor 0xA1B2)
        val nDirt = makeNoise(seed xor 0x7E11)

        val centers = starts.map { Pair(it.first + 1, it.second + 1) }

        for (y in 0 until h) for (x in 0 until w) {
            val i = idx(x, y)
            val bw = mod.mapGen.borderWater
            val border = x < bw || y < bw || x >= w - bw || y >= h - bw
            when {
                border -> terrain[i] = Terrain.WATER
                nWater(x * mod.mapGen.waterFreq, y * mod.mapGen.waterFreq) > mod.mapGen.waterT && !nearBase(x, y, 13) -> terrain[i] = Terrain.WATER
                nDirt(x * mod.mapGen.dirtFreq, y * mod.mapGen.dirtFreq) > mod.mapGen.dirtT -> terrain[i] = Terrain.DIRT
                else -> terrain[i] = Terrain.GRASS
            }
        }

        // расчистка площадок баз
        for (c in centers) {
            val r = mod.mapGen.baseClearR
            for (y in c.second - r..c.second + r) for (x in c.first - r..c.first + r) {
                if (!inBounds(x, y)) continue
                val i = idx(x, y)
                if (terrain[i] == Terrain.WATER || terrain[i] == Terrain.ORE) terrain[i] = Terrain.GRASS
            }
        }

        // рудные жилы у баз (дугой к центру карты)
        for (c in centers) {
            val ang = atan2((h / 2 - c.second).toFloat(), (w / 2 - c.first).toFloat())
            for (k in 0..4) {
                val a = ang + (k - 2) * 0.42f
                val d = 6.5f + (k % 2) * 2.2f
                val mx = Math.round(c.first + kotlin.math.cos(a) * d)
                val my = Math.round(c.second + kotlin.math.sin(a) * d)
                placeVein(mx, my, rnd)
            }
        }

        // россыпи в середине карты
        var placed = 0
        val veins = ArrayList<Pair<Int, Int>>()
        var tries = 0
        while (placed < mod.mapGen.midVeins && tries < 300) {
            tries++
            val cx = 6 + rnd.nextInt(w - 12)
            val cy = 6 + rnd.nextInt(h - 12)
            if (nearBase(cx, cy, 14)) continue
            if (veins.any { hypot((it.first - cx).toFloat(), (it.second - cy).toFloat()) < 10f }) continue
            veins.add(Pair(cx, cy))
            val n = 2 + rnd.nextInt(3)
            for (k in 0 until n) {
                placeVein(cx + rnd.nextInt(3) - 1, cy + rnd.nextInt(3) - 1, rnd)
            }
            placed++
        }

        // гарантированный коридор между базами (3 тайла шириной)
        val A = centers[0]; val B = centers[1]
        val steps = 2 * maxOf(abs(B.first - A.first), abs(B.second - A.second))
        for (s in 0..steps) {
            val t = s.toFloat() / steps
            val x = Math.round(A.first + (B.first - A.first) * t)
            val y = Math.round(A.second + (B.second - A.second) * t)
            for (dy in -1..1) for (dx in -1..1) {
                val px = x + dx; val py = y + dy
                val bw = mod.mapGen.borderWater
                if (px < bw || py < bw || px >= w - bw || py >= h - bw) continue
                val i = idx(px, py)
                if (terrain[i] == Terrain.WATER) terrain[i] = Terrain.DIRT
                if (terrain[i] == Terrain.ORE) { terrain[i] = Terrain.DIRT; ore[i] = 0 }
            }
        }
        version++
    }

    private fun placeVein(mx: Int, my: Int, rnd: Random) {
        if (mx < 3 || my < 3 || mx >= w - 3 || my >= h - 3) return
        val i = idx(mx, my)
        val t = terrain[i]
        if (t == Terrain.GRASS || t == Terrain.DIRT || t == Terrain.WATER) {
            terrain[i] = Terrain.ORE
            ore[i] = mod.mapGen.oreBase + rnd.nextInt(mod.mapGen.oreVar)
            for (dy in -2..2) for (dx in -2..2) {
                if (abs(dx) + abs(dy) > 2) continue
                val jx = mx + dx; val jy = my + dy
                if (!inBounds(jx, jy)) continue
                val j = idx(jx, jy)
                if (terrain[j] == Terrain.WATER) terrain[j] = Terrain.DIRT
            }
        }
    }
}
