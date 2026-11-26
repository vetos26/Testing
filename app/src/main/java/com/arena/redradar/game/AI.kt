package com.arena.redradar.game

import kotlin.math.hypot

/** ИИ-противник: экономика, стройка, оборона, волны атак. */
class AI(private val sim: Sim, private val pid: Int, diffKey: String) {

    private val cfg = Difficulties.of(diffKey)
    private var thinkT = 0f
    private var tacticsT = 0f
    private var waveSize = cfg.wave0
    private var lastWaveT = 0f
    private var mode = "build"   // build | defend
    private var defendUntil = 0f
    private var gatherX = 0f
    private var gatherY = 0f
    private var hasGather = false

    fun update(dt: Float) {
        thinkT += dt
        if (thinkT >= 0.6f) { thinkT = 0f; think() }
        tacticsT += dt
        if (tacticsT >= 0.9f) { tacticsT = 0f; tactics() }
    }

    private fun myUnits(): List<Unit> = sim.units.filter { !it.dead && it.owner == pid }
    private fun myBuildings(): List<Building> = sim.buildings.filter { !it.dead && it.owner == pid }

    private fun think() {
        val base = sim.playerBase(pid)
        val all = myBuildings()
        if (base == null && all.isEmpty()) return

        val miners = myUnits().filter { it.def.isMiner }
        val army = myUnits().filter { !it.def.isMiner }

        // 1. шахтёры
        if (base != null && base.constructed) {
            val queued = base.queue.count { it.key == "miner" }
            if (miners.size + queued < cfg.miners && sim.credits[pid] >= Defs.miner.cost) {
                sim.train(base, "miner", silent = true)
            }
        }

        // 2. бездельных шахтёров — к руде
        for (m in miners) {
            if (m.order == "harvest" || m.order == "build") continue
            val ore = sim.map.nearestOre(m.x, m.y, 20) ?: continue
            sim.issueHarvest(m, ore.first, ore.second)
        }

        // 3. энергоблок при дефиците
        val powerPending = all.any { it.def.key == "power" && !it.constructed }
        if (!powerPending && sim.powerUsed(pid) >= sim.powerProvided(pid)) {
            place("power", base)
        }

        // 4. казармы и завод
        val barracksN = all.count { it.def.key == "barracks" }
        val factoryN = all.count { it.def.key == "factory" }
        if (barracksN == 0) place("barracks", base)
        else if (factoryN == 0 && army.size >= 6) place("factory", base)
        else if (barracksN == 1 && army.size >= 8 && sim.credits[pid] > 1500) place("barracks", base)

        // 5. турели
        val turretN = all.count { it.def.key == "turret" }
        if (turretN < cfg.turrets && sim.credits[pid] > Defs.turret.cost + 400 && base != null) {
            placeTurret(base)
        }

        // 6. брошенные стройки — назначить шахтёра
        for (site in all) {
            if (site.constructed) continue
            val hasBuilder = miners.any { it.order == "build" && it.buildSiteId == site.id }
            if (!hasBuilder) {
                val free = miners.filter { it.order != "build" }
                    .minByOrNull { Sim.dist2(it.x, it.y, site.x, site.y) }
                if (free != null) sim.issueBuild(free, site)
            }
        }

        // 7. войска
        for (b in all) {
            if (!b.constructed || b.def.trains.isEmpty() || b.def.key == "base") continue
            if (b.queue.size >= 2) continue
            val key = if (b.def.key == "factory") "tank"
            else if (sim.credits[pid] > 900 && kotlin.random.Random.nextFloat() < 0.4f) "rocket"
            else "rifle"
            if (sim.credits[pid] >= Defs.units[key]!!.cost) sim.train(b, key, silent = true)
        }
    }

    private fun place(key: String, near: Building?): Boolean {
        val def = Defs.buildings[key] ?: return false
        if (sim.credits[pid] < def.cost) return false
        val cx = near?.let { (it.x / TILE).toInt() } ?: MAP_W / 2
        val cy = near?.let { (it.y / TILE).toInt() } ?: MAP_H / 2
        for (r in 2..12) {
            for (dy in -r..r) for (dx in -r..r) {
                if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != r) continue
                val site = sim.placeBuilding(pid, key, cx + dx, cy + dy)
                if (site != null) return true
            }
        }
        return false
    }

    private fun placeTurret(base: Building): Boolean {
        val eb = sim.playerBase(if (pid == 0) 1 else 0) ?: return place("turret", base)
        val dx = eb.x - base.x; val dy = eb.y - base.y
        val d = hypot(dx, dy).coerceAtLeast(1f)
        val tx = ((base.x + dx / d * 6f * TILE) / TILE).toInt()
        val ty = ((base.y + dy / d * 6f * TILE) / TILE).toInt()
        val def = Defs.turret
        if (sim.credits[pid] < def.cost) return false
        for (r in 0..4) {
            for (ddy in -r..r) for (ddx in -r..r) {
                if (maxOf(kotlin.math.abs(ddx), kotlin.math.abs(ddy)) != r) continue
                if (sim.map.canPlace(tx + ddx, ty + ddy, def.size)) {
                    return sim.placeBuilding(pid, "turret", tx + ddx, ty + ddy) != null
                }
            }
        }
        return false
    }

    private fun tactics() {
        val base = sim.playerBase(pid)
        val army = myUnits().filter { !it.def.isMiner }

        if (base == null) {
            val target = nearestEnemyBuilding(32f * TILE, 32f * TILE)
            if (target != null && army.isNotEmpty()) sim.issueAttack(army, target)
            return
        }

        // оборона
        val threats = sim.buildings.any { b ->
            !b.dead && b.owner == pid && sim.time - b.lastDmgT < 5f
        } || sim.units.any { u ->
            !u.dead && u.owner == pid && u.def.isMiner && sim.time - u.lastDmgT < 5f &&
                base != null && Sim.dist2(u.x, u.y, base.x, base.y) < (12f * TILE) * (12f * TILE)
        }
        if (threats) {
            mode = "defend"
            defendUntil = sim.time + 9f
        }
        if (mode == "defend") {
            val hurt = myBuildings().filter { sim.time - it.lastDmgT < 5f }.maxByOrNull { it.lastDmgT }
                ?: myUnits().filter { it.def.isMiner && sim.time - it.lastDmgT < 5f }.maxByOrNull { it.lastDmgT }
            if (hurt != null) {
                val idle = army.filter { it.order == "idle" || it.order == "stop" }
                if (idle.isNotEmpty()) sim.issueMove(idle, hurt.x, hurt.y, attackMove = true)
            }
            if (sim.time > defendUntil) mode = "build"
            return
        }

        // точка сбора между базами (ближе к себе)
        val eb = sim.playerBase(if (pid == 0) 1 else 0) ?: return
        if (!hasGather) {
            gatherX = base.x + (eb.x - base.x) * 0.3f
            gatherY = base.y + (eb.y - base.y) * 0.3f
            hasGather = true
        }

        for (u in army) {
            if (u.order == "idle" || u.order == "stop") {
                if (Sim.dist2(u.x, u.y, gatherX, gatherY) > (4f * TILE) * (4f * TILE)) {
                    sim.issueMove(listOf(u), gatherX, gatherY)
                }
            }
        }

        val ready = army.filter {
            (it.order == "idle" || it.order == "stop" || it.order == "move") &&
                Sim.dist2(it.x, it.y, gatherX, gatherY) < (6f * TILE) * (6f * TILE)
        }

        val enemyArmy = sim.units.count { !it.dead && it.owner != pid && !it.def.isMiner }
        val sinceWave = sim.time - lastWaveT
        val bigWave = ready.size >= waveSize
        val pressureWave = ready.size >= 4 && sinceWave > 110f
        val finalPush = ready.size >= 6 && enemyArmy == 0

        if (bigWave || pressureWave || finalPush) {
            val target = nearestEnemyBuilding(base.x, base.y)
            if (target != null) {
                sim.issueAttack(ready, target)
                if (bigWave) waveSize = minOf(waveSize + cfg.waveStep, cfg.waveCap)
                lastWaveT = sim.time
                gatherX = base.x + (eb.x - base.x) * 0.3f
                gatherY = base.y + (eb.y - base.y) * 0.3f
            }
        }
    }

    private fun nearestEnemyBuilding(fx: Float, fy: Float): Building? =
        sim.buildings.filter { !it.dead && it.owner != pid }
            .minByOrNull { Sim.dist2(it.x, it.y, fx, fy) }
}
