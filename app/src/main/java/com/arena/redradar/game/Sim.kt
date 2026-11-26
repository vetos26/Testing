package com.arena.redradar.game

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ------------------------------------------------------------
// Описания юнитов и зданий (весь баланс в одном месте)
// ------------------------------------------------------------
class UnitDef(
    val key: String,
    val name: String,
    val cost: Int,
    val buildTime: Float,
    val hp: Float,
    val speed: Float,
    val dmg: Float,
    val range: Float,
    val atkCd: Float,
    val sight: Float,
    val radius: Float,
    val isMiner: Boolean = false,
    val projectile: Boolean = false
)

class BuildingDef(
    val key: String,
    val name: String,
    val cost: Int,
    val buildTime: Float,
    val hp: Float,
    val size: Int,
    val powerUse: Int = 0,
    val powerGive: Int = 0,
    val trains: List<String> = emptyList(),
    val dropoff: Boolean = false,
    val dmg: Float = 0f,
    val range: Float = 0f,
    val atkCd: Float = 0f,
    val projectile: Boolean = false
)

object Defs {
    val miner = UnitDef("miner", "Шахтёр", 700, 10f, 80f, 70f, 4f, 14f, 1f, 5f, 10f, isMiner = true)
    val rifle = UnitDef("rifle", "Стрелок", 120, 5f, 90f, 78f, 8f, 100f, 0.8f, 6f, 9f)
    val rocket = UnitDef("rocket", "Ракетчик", 180, 7f, 70f, 76f, 22f, 165f, 1.5f, 6f, 9f, projectile = true)
    val tank = UnitDef("tank", "Танк", 650, 15f, 260f, 92f, 30f, 140f, 1.6f, 7f, 13f, projectile = true)

    val units = mapOf(
        "miner" to miner, "rifle" to rifle, "rocket" to rocket, "tank" to tank
    )
    val infantry = listOf("rifle", "rocket")

    val base = BuildingDef("base", "Штаб", 1500, 25f, 1000f, 3, powerUse = 5, trains = listOf("miner"), dropoff = true)
    val power = BuildingDef("power", "Энергоблок", 300, 8f, 400f, 2, powerGive = 40)
    val barracks = BuildingDef("barracks", "Казармы", 550, 14f, 600f, 3, powerUse = 10, trains = infantry)
    val factory = BuildingDef("factory", "Военный завод", 1200, 20f, 800f, 3, powerUse = 20, trains = listOf("tank"))
    val turret = BuildingDef("turret", "Турель", 650, 12f, 400f, 2, powerUse = 6, dmg = 25f, range = 190f, atkCd = 1.2f, projectile = true)

    val buildings = mapOf(
        "base" to base, "power" to power, "barracks" to barracks,
        "factory" to factory, "turret" to turret
    )
    val buildMenu = listOf("power", "barracks", "factory", "turret", "base")

    const val UNIT_CAP = 40
    const val START_CREDITS = 3000f
}

// ------------------------------------------------------------
// Сущности
// ------------------------------------------------------------
open class Entity(val owner: Int, var x: Float, var y: Float) {
    var id: Int = 0
    var hp = 0f
    var maxHp = 0f
    var dead = false
    var selected = false
    var flashT = 0f
    var lastDmgT = -99f
}

class GameUnit(owner: Int, val def: UnitDef, x: Float, y: Float) : Entity(owner, x, y) {
    var order = "idle"          // idle|stop|move|attackmove|attack|harvest|build
    var tx = x; var ty = y
    var target: Entity? = null
    var targetBuilding: Building? = null
    var path: List<P>? = null
    var pathI = 0
    var repathT = 0f
    var resume: String? = null
    var resumeTx = 0f; var resumeTy = 0f
    var resumeSiteId = -1
    var resumeResTx = -1; var resumeResTy = -1
    var atkT = 0f
    var scanT = 0f
    var stuckT = 0f
    var lastX = x; var lastY = y
    var carry = 0f
    var hPhase = 0              // 0 к руде | 1 копает | 2 к базе
    var resTx = -1; var resTy = -1
    var gatherT = 0f
    var buildSiteId = -1
    var face = 0f
    var turretA = 0f            // угол башни (танк) — при стрельбе смотрит на цель
}

class Building(owner: Int, val def: BuildingDef, val tx: Int, val ty: Int, constructed: Boolean) :
    Entity(owner, tx * TILE + def.size * TILE / 2f, ty * TILE + def.size * TILE / 2f) {

    val size = def.size
    var constructed = constructed
    var progress = if (constructed) 1f else 0f
    var builtT = 0f

    init {
        maxHp = def.hp
        hp = if (constructed) def.hp else maxOf(1f, def.hp * 0.2f)
    }

    class QueueItem(val key: String, var t: Float, val tMax: Float)

    val queue = ArrayList<QueueItem>()
    var rallyX = x
    var rallyY = y
    var rallyTargetId = -1
    var atkT = 0f
    var scanT = 0f
    var barrelA = 0f
    var target: Entity? = null

    val edgeRadius: Float get() = size * TILE / 2f
}

class Proj(val owner: Int, var x: Float, var y: Float, val target: Entity, val dmg: Float, val src: Entity?) {
    var tx = target.x
    var ty = target.y
    var speed = 300f
    var dead = false
    var angle = 0f
}

// ------------------------------------------------------------
// Симуляция
// ------------------------------------------------------------
class Sim(val seed: Long, val difficulty: String) {

    val map = GameMap(seed)
    val units = ArrayList<GameUnit>()
    val buildings = ArrayList<Building>()
    val projectiles = ArrayList<Proj>()
    val credits = floatArrayOf(Defs.START_CREDITS, Defs.START_CREDITS)
    var time = 0f
    var over: String? = null
    var onEvent: ((String, Float, Float) -> kotlin.Unit)? = null
    private var nextId = 1
    private var winT = 0f
    private var alarmT = -99f
    val alarms = ArrayList<Pair<Float, Float>>()   // точки тревоги (для миникарты)

    private val ai = AI(this, 1, difficulty)

    init {
        for ((pid, s) in map.starts.withIndex()) {
            val b = Building(pid, Defs.base, s.first, s.second, true)
            b.id = nextId++
            buildings.add(b)
            map.setBuilding(s.first, s.second, b.size, b.id)
            b.rallyX = b.x; b.rallyY = b.y
            var n = 0
            for (r in 1..4) {
                for (dy in -r..r) for (dx in -r..r) {
                    if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != r) continue
                    if (n >= 3) break
                    val ux = s.first + 1 + dx; val uy = s.second + 1 + dy
                    if (!map.isPassable(ux, uy)) continue
                    spawn(Units.unit(pid, Defs.miner, ux * TILE + 16f, uy * TILE + 16f))
                    n++
                }
                if (n >= 3) break
            }
            // стартовые стрелки
            spawn(Units.unit(pid, Defs.rifle, b.x, b.y + TILE * 2.5f))
        }
    }

    // ---------- утилиты ----------
    fun spawn(u: GameUnit) {
        u.id = nextId++
        units.add(u)
    }

    fun unitCount(pid: Int) = units.count { !it.dead && it.owner == pid }

    fun powerProvided(pid: Int) =
        buildings.sumOf { if (!it.dead && it.owner == pid && it.constructed) it.def.powerGive else 0 }

    fun powerUsed(pid: Int) =
        buildings.sumOf { if (!it.dead && it.owner == pid && it.constructed) it.def.powerUse else 0 }

    fun powerOk(pid: Int) = powerProvided(pid) >= powerUsed(pid)

    fun playerBase(pid: Int): Building? =
        buildings.firstOrNull { !it.dead && it.owner == pid && it.def.key == "base" }
            ?: buildings.firstOrNull { !it.dead && it.owner == pid }

    fun entityById(id: Int): Entity? {
        for (u in units) if (u.id == id) return u
        for (b in buildings) if (b.id == id) return b
        return null
    }

    fun entityAt(x: Float, y: Float): Entity? {
        var best: Entity? = null
        var bd = Float.MAX_VALUE
        for (u in units) {
            if (u.dead) continue
            val d = dist2(x, y, u.x, u.y)
            if (d < (u.def.radius + 6f) * (u.def.radius + 6f) && d < bd) { bd = d; best = u }
        }
        if (best != null) return best
        for (b in buildings) {
            if (b.dead) continue
            val r = b.edgeRadius
            if (kotlin.math.abs(x - b.x) <= r && kotlin.math.abs(y - b.y) <= r) return b
        }
        return null
    }

    fun canAfford(pid: Int, cost: Int) = credits[pid] >= cost

    // ---------- приказы ----------
    fun issueMove(list: List<GameUnit>, x: Float, y: Float, attackMove: Boolean = false) {
        val offs = ringOffsets(list.size)
        val sorted = list.sortedBy { dist2(it.x, it.y, x, y) }
        sorted.forEachIndexed { i, u ->
            val ox = (x + offs[i].first).coerceIn(0f, WORLD_W - 1f)
            val oy = (y + offs[i].second).coerceIn(0f, WORLD_H - 1f)
            u.order = if (attackMove) "attackmove" else "move"
            u.tx = ox; u.ty = oy
            u.path = null; u.repathT = 0f
            u.resume = null; u.target = null; u.targetBuilding = null
        }
    }

    fun issueAttack(list: List<GameUnit>, t: Entity) {
        for (u in list) {
            u.order = "attack"
            u.target = t
            u.targetBuilding = if (t is Building) t else null
            u.path = null; u.repathT = 0f
            u.resume = null
        }
    }

    fun issueHarvest(u: GameUnit, ox: Int, oy: Int) {
        if (!u.def.isMiner) return
        u.order = "harvest"
        u.hPhase = 0
        u.resTx = ox; u.resTy = oy
        u.path = null
        u.resume = null
    }

    fun issueBuild(u: GameUnit, site: Building) {
        if (!u.def.isMiner) return
        if (u.order == "harvest") {
            u.resume = "harvest"
            u.resumeResTx = u.resTx; u.resumeResTy = u.resTy
        }
        u.order = "build"
        u.buildSiteId = site.id
        u.path = null
    }

    fun stopUnits(list: List<GameUnit>) {
        for (u in list) {
            u.order = "stop"
            u.path = null; u.resume = null
        }
    }

    fun setRally(b: Building, x: Float, y: Float, targetId: Int) {
        b.rallyX = x; b.rallyY = y; b.rallyTargetId = targetId
    }

    // ---------- строительство ----------
    fun canPlaceAt(def: BuildingDef, tx: Int, ty: Int): Boolean = map.canPlace(tx, ty, def.size)

    fun placeBuilding(pid: Int, key: String, tx: Int, ty: Int): Building? {
        val def = Defs.buildings[key] ?: return null
        if (!map.canPlace(tx, ty, def.size)) return null
        if (credits[pid] < def.cost) return null
        // в площадке не должны стоять юниты
        val px0 = tx * TILE.toFloat(); val py0 = ty * TILE.toFloat()
        val ps = def.size * TILE.toFloat()
        for (u in units) {
            if (u.dead) continue
            if (u.x > px0 - 4 && u.x < px0 + ps + 4 && u.y > py0 - 4 && u.y < py0 + ps + 4) return null
        }
        credits[pid] -= def.cost
        val site = Building(pid, def, tx, ty, false)
        site.id = nextId++
        buildings.add(site)
        map.setBuilding(tx, ty, def.size, site.id)
        // ближайшие свободные шахтёры строят
        val miners = units.filter { !it.dead && it.owner == pid && it.def.isMiner && it.order != "build" }
            .sortedBy { dist2(it.x, it.y, site.x, site.y) }
        if (miners.isNotEmpty()) issueBuild(miners[0], site)
        return site
    }

    fun cancelSite(site: Building) {
        if (site.dead || site.constructed) return
        credits[site.owner] += site.def.cost
        map.setBuilding(site.tx, site.ty, site.size, 0)
        site.dead = true
    }

    private fun finishSite(site: Building) {
        site.progress = 1f
        site.constructed = true
        site.hp = site.maxHp
        if (site.owner == 0) onEvent?.invoke("built", site.x, site.y)
    }

    // ---------- производство ----------
    fun train(b: Building, key: String, silent: Boolean = false): Boolean {
        val def = Defs.units[key] ?: return false
        if (b.dead || !b.constructed || !b.def.trains.contains(key)) return false
        if (b.queue.size >= 5) return false
        if (unitCount(b.owner) + b.queue.size >= Defs.UNIT_CAP) return false
        if (credits[b.owner] < def.cost) {
            if (b.owner == 0 && !silent) onEvent?.invoke("needcredits", b.x, b.y)
            return false
        }
        credits[b.owner] -= def.cost
        b.queue.add(Building.QueueItem(key, def.buildTime, def.buildTime))
        return true
    }

    fun cancelTrain(b: Building) {
        val q = b.queue.lastOrNull() ?: return
        credits[b.owner] += Defs.units[q.key]?.cost ?: 0
        b.queue.removeAt(b.queue.size - 1)
    }

    private fun spawnFrom(b: Building, key: String) {
        val def = Defs.units[key] ?: return
        val spot = freeSpot(b)
        val u = Units.unit(b.owner, def, spot.first, spot.second)
        spawn(u)
        if (b.rallyTargetId != -1) {
            val rt = entityById(b.rallyTargetId)
            if (rt != null && !rt.dead && rt.owner != b.owner) { issueAttack(listOf(u), rt); return }
        }
        val oreT = map.nearestOre(b.rallyX, b.rallyY, 6)
        if (u.def.isMiner && oreT != null) { issueHarvest(u, oreT.first, oreT.second); return }
        issueMove(listOf(u), b.rallyX, b.rallyY)
    }

    private fun freeSpot(b: Building): Pair<Float, Float> {
        for (r in 1..4) {
            val cand = ArrayList<Pair<Int, Int>>()
            for (dy in -r until b.size + r) for (dx in -r until b.size + r) {
                val onEdge = dx == -r || dx == b.size + r - 1 || dy == -r || dy == b.size + r - 1
                if (!onEdge) continue
                val tx = b.tx + dx; val ty = b.ty + dy
                if (map.isPassable(tx, ty)) cand.add(Pair(tx, ty))
            }
            if (cand.isNotEmpty()) {
                cand.sortBy { dist2(it.first * TILE + 16f, it.second * TILE + 16f, b.x, b.y) }
                val c = cand[0]
                return Pair(c.first * TILE + 16f, c.second * TILE + 16f)
            }
        }
        return Pair(b.x, b.y + b.edgeRadius + TILE)
    }

    private fun nearestDropoff(pid: Int, x: Float, y: Float): Building? =
        buildings.filter { !it.dead && it.owner == pid && it.constructed && it.def.dropoff }
            .minByOrNull { dist2(it.x, it.y, x, y) }

    // ---------- урон ----------
    fun damage(ent: Entity, amt: Float, src: Entity?) {
        if (ent.dead) return
        ent.hp -= max(1f, amt)
        ent.flashT = 0.18f
        ent.lastDmgT = time
        if (ent.owner == 0 && time - alarmT > 6f) {
            alarmT = time
            alarms.add(Pair(ent.x, ent.y))
            if (alarms.size > 3) alarms.removeAt(0)
            onEvent?.invoke("alarm", ent.x, ent.y)
        }
        retaliate(ent, src)
        if (ent.hp <= 0) kill(ent)
    }

    private fun retaliate(ent: Entity, src: Entity?) {
        val s = src ?: return
        if (s.dead || s.owner == ent.owner) return
        if (ent !is GameUnit || ent.def.isMiner) return
        val o = ent.order
        val rettable = o == "idle" || o == "stop" || o == "harvest" || o == "attackmove"
        if (!rettable) return
        if (o == "attackmove" || o == "harvest") {
            ent.resume = o
            ent.resumeTx = ent.tx; ent.resumeTy = ent.ty
            ent.resumeResTx = ent.resTx; ent.resumeResTy = ent.resTy
            ent.resumeSiteId = ent.buildSiteId
        } else {
            ent.resume = "idle"
        }
        ent.order = "attack"
        ent.target = s
        ent.targetBuilding = if (s is Building) s else null
        ent.path = null; ent.repathT = 0f
    }

    private fun kill(ent: Entity) {
        if (ent.dead) return
        ent.dead = true
        ent.selected = false
        if (ent is Building) {
            map.setBuilding(ent.tx, ent.ty, ent.size, 0)
            onEvent?.invoke("boom", ent.x, ent.y)
        } else {
            onEvent?.invoke("die", ent.x, ent.y)
        }
    }

    // ---------- главный такт ----------
    fun update(dt: Float) {
        if (over != null) return
        time += dt

        for (u in units) if (!u.dead) updateUnit(u, dt)
        separate()
        for (b in buildings) if (!b.dead) updateBuilding(b, dt)

        for (p in projectiles) {
            if (p.dead) continue
            val t = p.target
            if (t != null && !t.dead) { p.tx = t.x; p.ty = t.y }
            val d = kotlin.math.hypot(p.tx - p.x, p.ty - p.y)
            val step = p.speed * dt
            p.angle = atan2(p.ty - p.y, p.tx - p.x)
            if (d <= step + 6f) {
                p.x = p.tx; p.y = p.ty
                val t2 = p.target
                if (t2 != null && !t2.dead) damage(t2, p.dmg, p.src)
                p.dead = true
            } else {
                p.x += cos(p.angle) * step
                p.y += sin(p.angle) * step
            }
        }

        for (u in units) if (u.flashT > 0f) u.flashT -= dt
        for (b in buildings) if (b.flashT > 0f) b.flashT -= dt

        units.removeAll { it.dead }
        buildings.removeAll { it.dead }
        projectiles.removeAll { it.dead }

        ai.update(dt)

        winT += dt
        if (winT > 0.6f) {
            winT = 0f
            val pB = buildings.any { it.owner == 0 }
            val eB = buildings.any { it.owner == 1 }
            if (!pB) over = "lose"
            else if (!eB) over = "win"
        }
    }

    // ---------- юниты ----------
    private fun updateUnit(u: GameUnit, dt: Float) {
        u.atkT = max(0f, u.atkT - dt)
        when (u.order) {
            "idle" -> uIdle(u, dt)
            "stop" -> {}
            "move" -> uMove(u, dt)
            "attackmove" -> uAttackMove(u, dt)
            "attack" -> uAttack(u, dt)
            "harvest" -> uHarvest(u, dt)
            "build" -> uBuild(u, dt)
            else -> u.order = "idle"
        }
        // застревание
        val pth = u.path
        if (pth != null && u.pathI < pth.size) {
            if (dist2(u.x, u.y, u.lastX, u.lastY) < 0.5f) {
                u.stuckT += dt
                if (u.stuckT > 0.8f) { u.stuckT = 0f; u.path = null }
            } else {
                u.stuckT = 0f
                u.lastX = u.x; u.lastY = u.y
            }
        }
    }

    private fun uIdle(u: GameUnit, dt: Float) {
        if (u.def.isMiner) return
        u.scanT -= dt
        if (u.scanT <= 0f) {
            u.scanT = 0.25f
            val t = acquireTarget(u)
            if (t != null) {
                u.resume = "idle"
                u.order = "attack"
                u.target = t
                u.targetBuilding = if (t is Building) t else null
                u.path = null
            }
        }
    }

    private fun uMove(u: GameUnit, dt: Float) {
        if (u.path == null) {
            val p = findPath(map, (u.x / TILE).toInt(), (u.y / TILE).toInt(), (u.tx / TILE).toInt(), (u.ty / TILE).toInt())
            if (p == null || p.isEmpty()) { u.order = "idle"; u.path = null; return }
            u.path = p; u.pathI = 0
        }
        if (followPath(u, dt)) u.order = "idle"
    }

    private fun uAttackMove(u: GameUnit, dt: Float) {
        u.scanT -= dt
        if (u.scanT <= 0f) {
            u.scanT = 0.25f
            val t = acquireTarget(u)
            if (t != null) {
                u.resume = "attackmove"
                u.resumeTx = u.tx; u.resumeTy = u.ty
                u.order = "attack"
                u.target = t
                u.targetBuilding = if (t is Building) t else null
                u.path = null
                return
            }
        }
        uMove(u, dt)
    }

    private fun uAttack(u: GameUnit, dt: Float) {
        var t = u.target
        if (t == null || t.dead) {
            val bt = u.targetBuilding
            if (bt != null && !bt.dead) { t = bt; u.target = bt }
            else {
                resumeOrder(u)
                return
            }
        }
        // штурмуя здание, переключаемся на юнитов рядом
        if (t is Building) {
            u.scanT -= dt
            if (u.scanT <= 0f) {
                u.scanT = 0.35f
                val near = enemyUnitNear(t, u.owner, 3.5f * TILE)
                if (near != null) u.target = near
            }
            val t2 = u.target
            if (t2 == null || t2.dead) return
            t = t2
        }

        val dEdge = edgeDist(t, u.x, u.y)
        if (dEdge > u.def.range) {
            val sameTile = (u.x / TILE).toInt() == (t.x / TILE).toInt() &&
                (u.y / TILE).toInt() == (t.y / TILE).toInt()
            if (sameTile) { u.path = null; nudge(u, t.x, t.y, dt); return }
            u.repathT -= dt
            if (u.path == null || u.repathT <= 0f || u.pathI >= (u.path?.size ?: 0)) {
                val p = findPath(map, (u.x / TILE).toInt(), (u.y / TILE).toInt(), (t.x / TILE).toInt(), (t.y / TILE).toInt())
                u.repathT = 0.5f
                if (p == null) { resumeOrder(u); return }
                if (p.isEmpty()) { u.path = null; nudge(u, t.x, t.y, dt); return }
                u.path = p; u.pathI = 0
            }
            if (u.path != null) followPath(u, dt)
        } else {
            u.path = null
            u.face = atan2(t.y - u.y, t.x - u.x)
            u.turretA = u.face
            if (u.atkT <= 0f) {
                u.atkT = u.def.atkCd
                if (u.def.projectile) {
                    projectiles.add(Proj(u.owner, u.x + cos(u.face) * 10f, u.y + sin(u.face) * 10f, t, u.def.dmg, u))
                } else {
                    damage(t, u.def.dmg, u)
                }
            }
        }
    }

    private fun uHarvest(u: GameUnit, dt: Float) {
        if (!map.isOre(u.resTx, u.resTy)) {
            val alt = map.nearestOre(u.x, u.y, 14)
            if (alt != null) {
                u.resTx = alt.first; u.resTy = alt.second
                u.path = null
                if (u.hPhase == 1) u.hPhase = 0
            } else if (u.carry > 0f && u.hPhase != 2) {
                u.hPhase = 2; u.path = null
            } else if (u.hPhase != 2) {
                resumeOrder(u); return
            }
        }

        val resX = u.resTx * TILE + 16f
        val resY = u.resTy * TILE + 16f

        when (u.hPhase) {
            0 -> {
                if (u.path == null) {
                    val stand = standTileAround(u.resTx, u.resTy, 1, u)
                    if (stand != null) {
                        val p = findPath(map, (u.x / TILE).toInt(), (u.y / TILE).toInt(), stand.first, stand.second)
                        if (p == null) { u.order = "idle"; return }
                        if (p.isEmpty()) { u.hPhase = 1; u.gatherT = 0f; u.path = null; return }
                        u.path = p; u.pathI = 0
                    } else { u.order = "idle"; return }
                }
                if (followPath(u, dt)) {
                    u.path = null
                    if (kotlin.math.hypot(u.x - resX, u.y - resY) <= TILE * 1.6f) {
                        u.hPhase = 1; u.gatherT = 0f
                    }
                }
            }
            1 -> {
                if (kotlin.math.hypot(u.x - resX, u.y - resY) > TILE * 2f) { u.hPhase = 0; u.path = null; return }
                u.gatherT += dt
                u.face = atan2(resY - u.y, resX - u.x)
                if (u.gatherT >= 1.6f) {
                    u.carry = map.takeOre(u.resTx, u.resTy, 25).toFloat()
                    if (u.carry > 0f) { u.hPhase = 2; u.path = null }
                    else u.gatherT = 0f
                }
            }
            2 -> {
                val drop = nearestDropoff(u.owner, u.x, u.y)
                if (drop == null) { u.order = "idle"; return }
                if (u.path == null) {
                    val p = findPath(map, (u.x / TILE).toInt(), (u.y / TILE).toInt(), (drop.x / TILE).toInt(), (drop.y / TILE).toInt())
                    if (p == null) { u.order = "idle"; return }
                    if (p.isEmpty()) { deposit(u, drop); return }
                    u.path = p; u.pathI = 0
                }
                if (followPath(u, dt) ||
                    kotlin.math.hypot(u.x - drop.x, u.y - drop.y) <= drop.edgeRadius + TILE * 0.6f) {
                    deposit(u, drop)
                }
            }
        }
    }

    private fun deposit(u: GameUnit, drop: Building) {
        if (u.carry > 0f) {
            val mult = if (u.owner == 1) Difficulties.of(difficulty).income else 1f
            credits[u.owner] += u.carry * mult
        }
        u.carry = 0f
        u.hPhase = 0
        u.path = null
    }

    private fun uBuild(u: GameUnit, dt: Float) {
        val site = entityById(u.buildSiteId) as? Building
        if (site == null || site.dead || site.constructed) {
            // вернуться к прежнему занятию
            if (u.resume == "harvest") {
                u.resume = null
                issueHarvest(u, u.resumeResTx, u.resumeResTy)
            } else {
                u.resume = null
                u.order = "idle"
            }
            return
        }
        val dEdge = edgeDist(site, u.x, u.y)
        if (dEdge > TILE * 0.9f) {
            if (u.path == null) {
                val stand = standTileAround(site.tx, site.ty, site.size, u)
                if (stand != null) {
                    val p = findPath(map, (u.x / TILE).toInt(), (u.y / TILE).toInt(), stand.first, stand.second)
                    if (p == null) { u.order = "idle"; return }
                    if (p.isEmpty()) { u.path = null; nudge(u, site.x, site.y, dt); return }
                    u.path = p; u.pathI = 0
                } else { u.order = "idle"; return }
            }
            if (followPath(u, dt)) u.path = null
        } else {
            u.path = null
            u.face = atan2(site.y - u.y, site.x - u.x)
            val rate = if (powerOk(site.owner)) 1f else 0.7f
            site.progress = min(1f, site.progress + dt * rate / site.def.buildTime)
            val wantHp = site.maxHp * (0.2f + 0.8f * site.progress)
            if (site.hp < wantHp) site.hp = wantHp
            if (site.progress >= 1f) finishSite(site)
        }
    }

    private fun resumeOrder(u: GameUnit) {
        val r = u.resume
        u.resume = null
        when (r) {
            "idle" -> { u.order = "idle"; u.path = null }
            "attackmove" -> {
                u.order = "attackmove"
                u.tx = u.resumeTx; u.ty = u.resumeTy
                u.path = null
            }
            "harvest" -> issueHarvest(u, u.resumeResTx, u.resumeResTy)
            else -> { u.order = "idle"; u.path = null }
        }
    }

    private fun acquireTarget(u: GameUnit): Entity? {
        val range = u.def.sight * TILE
        var bestU: Entity? = null; var bdU = Float.MAX_VALUE
        var bestB: Entity? = null; var bdB = Float.MAX_VALUE
        for (e in units) {
            if (e.dead || e.owner == u.owner) continue
            val d = dist2(u.x, u.y, e.x, e.y)
            if (d < range * range && d < bdU) { bdU = d; bestU = e }
        }
        if (bestU != null) return bestU
        for (b in buildings) {
            if (b.dead || b.owner == u.owner) continue
            val d = dist2(u.x, u.y, b.x, b.y)
            if (d < range * range && d < bdB) { bdB = d; bestB = b }
        }
        return bestB
    }

    private fun enemyUnitNear(b: Building, ownerOfAttacker: Int, range: Float): GameUnit? {
        val enemy = if (ownerOfAttacker == 0) 1 else 0
        var best: GameUnit? = null
        var bd = Float.MAX_VALUE
        for (u in units) {
            if (u.dead || u.owner != enemy) continue
            val d = dist2(b.x, b.y, u.x, u.y)
            if (d < range * range && d < bd) { bd = d; best = u }
        }
        return best
    }

    private fun edgeDist(e: Entity, x: Float, y: Float): Float {
        if (e is GameUnit) return max(0f, kotlin.math.hypot(x - e.x, y - e.y) - e.def.radius)
        val b = e as Building
        val dx = max(0f, kotlin.math.abs(x - b.x) - b.edgeRadius)
        val dy = max(0f, kotlin.math.abs(y - b.y) - b.edgeRadius)
        return sqrt(dx * dx + dy * dy)
    }

    private fun nudge(u: GameUnit, tx: Float, ty: Float, dt: Float) {
        val dx = tx - u.x; val dy = ty - u.y
        val d = kotlin.math.hypot(dx, dy).coerceAtLeast(0.001f)
        val step = u.def.speed * dt
        tryMove(u, dx / d * step, dy / d * step)
        u.face = atan2(dy, dx)
    }

    private fun standTileAround(tx: Int, ty: Int, size: Int, u: GameUnit): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var bd = Float.MAX_VALUE
        for (y in ty - 1..ty + size) for (x in tx - 1..tx + size) {
            val onEdge = (x == tx - 1 || x == tx + size) || (y == ty - 1 || y == ty + size)
            if (!onEdge) continue
            if (!map.isPassable(x, y)) continue
            val d = Sim.dist2(u.x, u.y, x * TILE + 16f, y * TILE + 16f)
            if (d < bd) { bd = d; best = Pair(x, y) }
        }
        return best
    }

    private fun followPath(u: GameUnit, dt: Float): Boolean {
        val pth = u.path ?: return true
        if (u.pathI >= pth.size) return true
        val wp = pth[u.pathI]
        val dx = wp.x - u.x; val dy = wp.y - u.y
        val d = kotlin.math.hypot(dx, dy)
        val step = u.def.speed * dt
        if (d <= step + 0.01f) {
            tryMove(u, dx, dy)
            u.pathI++
            u.face = atan2(dy, dx)
            return u.pathI >= pth.size
        }
        tryMove(u, dx / d * step, dy / d * step)
        u.face = atan2(dy, dx)
        return false
    }

    private fun tryMove(u: GameUnit, dx: Float, dy: Float) {
        val nx = u.x + dx; val ny = u.y + dy
        if (map.isPassable((nx / TILE).toInt(), (u.y / TILE).toInt())) u.x = nx
        if (map.isPassable((u.x / TILE).toInt(), (ny / TILE).toInt())) u.y = ny
    }

    private fun separate() {
        val list = units
        for (i in list.indices) {
            val a = list[i]
            if (a.dead) continue
            for (j in i + 1 until list.size) {
                val b = list[j]
                if (b.dead) continue
                val dx = b.x - a.x; val dy = b.y - a.y
                val minD = a.def.radius + b.def.radius
                val d2 = dx * dx + dy * dy
                if (d2 >= minD * minD || d2 < 0.0001f) continue
                val d = sqrt(d2)
                val push = (minD - d) * 0.4f
                val ux = dx / d; val uy = dy / d
                tryMove(a, -ux * push, -uy * push)
                tryMove(b, ux * push, uy * push)
            }
        }
    }

    // ---------- здания ----------
    private fun updateBuilding(b: Building, dt: Float) {
        if (!b.constructed) return
        b.builtT += dt

        if (b.queue.isNotEmpty()) {
            val q = b.queue[0]
            val rate = if (powerOk(b.owner)) 1f else 0.5f
            q.t -= dt * rate
            if (q.t <= 0f) {
                b.queue.removeAt(0)
                spawnFrom(b, q.key)
                if (b.owner == 0) onEvent?.invoke("trained", b.x, b.y)
            }
        }

        if (b.def.dmg > 0f) {
            b.scanT -= dt
            if (b.scanT <= 0f) {
                b.scanT = 0.3f
                val t = b.target
                if (t == null || t.dead || kotlin.math.hypot(t.x - b.x, t.y - b.y) > b.def.range) {
                    var best: Entity? = null
                    var bd = Float.MAX_VALUE
                    for (u in units) {
                        if (u.dead || u.owner == b.owner) continue
                        val d = dist2(b.x, b.y, u.x, u.y)
                        if (d < b.def.range * b.def.range && d < bd) { bd = d; best = u }
                    }
                    b.target = best
                }
            }
            val t = b.target
            if (t != null && !t.dead) {
                b.barrelA = atan2(t.y - b.y, t.x - b.x)
                val rate = if (powerOk(b.owner)) 1f else 0.5f
                b.atkT = max(0f, b.atkT - dt * rate)
                if (b.atkT <= 0f && kotlin.math.hypot(t.x - b.x, t.y - b.y) <= b.def.range) {
                    b.atkT = b.def.atkCd
                    projectiles.add(Proj(b.owner, b.x + cos(b.barrelA) * 14f, b.y + sin(b.barrelA) * 14f, t, b.def.dmg, b))
                }
            }
        }
    }

    companion object {
        fun dist2(ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = bx - ax; val dy = by - ay
            return dx * dx + dy * dy
        }

        private val ringCache = ArrayList<Pair<Float, Float>>()

        /** Смещения для построения группы из n юнитов вокруг точки. */
        fun ringOffsets(n: Int): List<Pair<Float, Float>> {
            if (ringCache.size >= n) return ringCache.subList(0, n)
            ringCache.clear()
            ringCache.add(Pair(0f, 0f))
            var ring = 1
            while (ringCache.size < n) {
                val r = ring * 26f
                val cnt = 6 * ring
                for (i in 0 until cnt) {
                    val a = i.toFloat() / cnt * 2f * Math.PI.toFloat() + ring * 0.5f
                    ringCache.add(Pair(cos(a) * r, sin(a) * r))
                    if (ringCache.size >= n) break
                }
                ring++
            }
            return ringCache.subList(0, n)
        }
    }
}

// адаптер юнита: конструктор с дефолтными полями
object Units {
    fun unit(owner: Int, def: UnitDef, x: Float, y: Float): GameUnit {
        val u = GameUnit(owner, def, x, y)
        u.maxHp = def.hp
        u.hp = def.hp
        u.face = if (owner == 0) Math.PI.toFloat() else 0f
        u.turretA = u.face
        return u
    }
}

// ------------------------------------------------------------
// Сложность
// ------------------------------------------------------------
object Difficulties {
    class Config(
        val key: String, val name: String, val income: Float,
        val wave0: Int, val waveStep: Int, val waveCap: Int,
        val miners: Int, val turrets: Int
    )

    val easy = Config("easy", "Легко", 0.8f, 6, 4, 14, 5, 0)
    val normal = Config("normal", "Нормально", 1f, 5, 3, 18, 7, 1)
    val hard = Config("hard", "Сложно", 1.25f, 5, 2, 22, 9, 2)
    val all = listOf(easy, normal, hard)

    fun of(key: String): Config = all.firstOrNull { it.key == key } ?: normal
}
