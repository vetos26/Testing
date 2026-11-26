package com.arena.redradar.game

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class P(val x: Float, val y: Float)

private class Node(val i: Int, val x: Int, val y: Int, val f: Float)

private class MinHeap {
    private val a = ArrayList<Node>()
    val size: Int get() = a.size

    fun push(n: Node) {
        a.add(n)
        var i = a.size - 1
        while (i > 0) {
            val p = (i - 1) / 2
            if (a[p].f <= a[i].f) break
            val t = a[p]; a[p] = a[i]; a[i] = t
            i = p
        }
    }

    fun pop(): Node {
        val top = a[0]
        val last = a.removeAt(a.size - 1)
        if (a.isNotEmpty()) {
            a[0] = last
            var i = 0
            while (true) {
                val l = 2 * i + 1
                val r = l + 1
                var m = i
                if (l < a.size && a[l].f < a[m].f) m = l
                if (r < a.size && a[r].f < a[m].f) m = r
                if (m == i) break
                val t = a[m]; a[m] = a[i]; a[i] = t
                i = m
            }
        }
        return top
    }
}

private class Grid {
    val g = FloatArray(MAP_W * MAP_H) { Float.MAX_VALUE }
    val parent = IntArray(MAP_W * MAP_H) { -1 }
    val closed = BooleanArray(MAP_W * MAP_H)
    fun reset() {
        g.fill(Float.MAX_VALUE)
        parent.fill(-1)
        closed.fill(false)
    }
}

private val DIRS = arrayOf(
    intArrayOf(1, 0, 1), intArrayOf(-1, 0, 1), intArrayOf(0, 1, 1), intArrayOf(0, -1, 1),
    intArrayOf(1, 1, 142), intArrayOf(1, -1, 142), intArrayOf(-1, 1, 142), intArrayOf(-1, -1, 142)
)

/** Ближайший проходимый тайл к (tx, ty) — спираль по кольцам. */
fun nearestPassableTile(map: GameMap, tx: Int, ty: Int, maxR: Int = 8): Pair<Int, Int>? {
    if (map.isPassable(tx, ty)) return Pair(tx, ty)
    for (r in 1..maxR) {
        var best: Pair<Int, Int>? = null
        var bestD = Int.MAX_VALUE
        for (dy in -r..r) for (dx in -r..r) {
            if (max(abs(dx), abs(dy)) != r) continue
            val x = tx + dx; val y = ty + dy
            if (!map.isPassable(x, y)) continue
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = Pair(x, y) }
        }
        if (best != null) return best
    }
    return null
}

private fun lineWalkable(map: GameMap, x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
    val d = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
    val steps = max(1, ceil(d / (TILE * 0.4f)).toInt())
    for (i in 0..steps) {
        val t = i.toFloat() / steps
        val x = x0 + (x1 - x0) * t
        val y = y0 + (y1 - y0) * t
        if (!map.isPassable((x / TILE).toInt(), (y / TILE).toInt())) return false
    }
    return true
}

/** A* по тайловой сетке. Возвращает путевые точки в мировых координатах (без старта). */
fun findPath(map: GameMap, sx0: Int, sy0: Int, gx0: Int, gy0: Int, maxNodes: Int = 5000): List<P>? {
    var sx = sx0; var sy = sy0; var gx = gx0; var gy = gy0
    if (!map.inBounds(sx, sy) || !map.inBounds(gx, gy)) return null

    if (!map.isPassable(gx, gy)) {
        val alt = nearestPassableTile(map, gx, gy, 6) ?: return null
        gx = alt.first; gy = alt.second
    }
    if (!map.isPassable(sx, sy)) {
        val alt = nearestPassableTile(map, sx, sy, 6) ?: return null
        sx = alt.first; sy = alt.second
    }
    if (sx == gx && sy == gy) return emptyList()

    val grid = Grid()
    grid.reset()
    val startI = sy * MAP_W + sx
    val goalI = gy * MAP_W + gx
    grid.g[startI] = 0f
    val heap = MinHeap()

    fun h(x: Int, y: Int): Float {
        val dx = abs(x - gx); val dy = abs(y - gy)
        return (max(dx, dy) + 0.42f * min(dx, dy)) * 1.02f
    }

    heap.push(Node(startI, sx, sy, h(sx, sy)))
    var expanded = 0
    var found = false

    while (heap.size > 0 && expanded < maxNodes) {
        val cur = heap.pop()
        if (grid.closed[cur.i]) continue
        grid.closed[cur.i] = true
        expanded++
        if (cur.i == goalI) { found = true; break }
        for (d in DIRS) {
            val nx = cur.x + d[0]; val ny = cur.y + d[1]
            if (nx < 0 || ny < 0 || nx >= MAP_W || ny >= MAP_H) continue
            if (!map.isPassable(nx, ny)) continue
            if (d[0] != 0 && d[1] != 0) {
                if (!map.isPassable(cur.x + d[0], cur.y) || !map.isPassable(cur.x, cur.y + d[1])) continue
            }
            val ni = ny * MAP_W + nx
            if (grid.closed[ni]) continue
            val ng = grid.g[cur.i] + d[2] / 100f
            if (ng < grid.g[ni]) {
                grid.g[ni] = ng
                grid.parent[ni] = cur.i
                heap.push(Node(ni, nx, ny, ng + h(nx, ny)))
            }
        }
    }

    if (!found) return null

    // восстановление тайлов
    val tiles = ArrayList<Pair<Int, Int>>()
    var i = goalI
    while (i != -1 && i != startI) {
        tiles.add(Pair(i % MAP_W, i / MAP_W))
        i = grid.parent[i]
    }
    tiles.reverse()
    if (tiles.isEmpty()) return emptyList()

    // сглаживание: жадно пропускаем точки с прямой видимостью
    val out = ArrayList<P>()
    var cx = startI % MAP_W * TILE + TILE / 2f
    var cy = startI / MAP_W * TILE + TILE / 2f
    var k = 0
    while (k < tiles.size) {
        var j = min(tiles.size - 1, k + 1)
        var cand = tiles.size - 1
        while (cand > k) {
            val tx = tiles[cand].first * TILE + TILE / 2f
            val ty = tiles[cand].second * TILE + TILE / 2f
            if (lineWalkable(map, cx, cy, tx, ty)) { j = cand; break }
            cand--
        }
        val px = tiles[j].first * TILE + TILE / 2f
        val py = tiles[j].second * TILE + TILE / 2f
        out.add(P(px, py))
        cx = px; cy = py
        k = j + 1
    }
    return out
}
