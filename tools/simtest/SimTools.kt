import com.arena.redradar.engine.*
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/**
 * Симулятор движка RTS для настольного компьютера (JVM, без Android).
 * Два режима:
 *   stats N  — баланс: N партий бот-игрока против ИИ на каждой сложности,
 *              печатаются победы/поражения и средняя длительность;
 *   shot seed diff seconds out.ppm — PNG/PPM-снимок мира на секунде партии:
 *              карта, руда, здания, юниты (цвета фракций из мода).
 *
 * Используются только встроенные данные Mod.ra2Defaults() (мод «по умолчанию»).
 */

private fun tryPlace(sim: Sim, key: String, near: Building): Boolean {
    val cx = near.tx
    val cy = near.ty
    for (r in 2..12) for (dy in -r..r) for (dx in -r..r) {
        if (maxOf(abs(dx), abs(dy)) != r) continue
        if (sim.placeBuilding(0, key, cx + dx, cy + dy) != null) return true
    }
    return false
}

private data class Outcome(val over: String?, val t: Float)

/** Простой бот игрока: зеркалит логику ИИ движка (экономика, стройка, оборона, волны). */
private fun play(sim: Sim, rnd: Random, maxT: Float, doAttack: Boolean, stopOnOver: Boolean = true): Outcome {
    val dt = 1f / 30f
    val cfg = sim.mod.diff(sim.difficulty)
    var t = 0f
    var thinkT = 1f
    var waveSize = 8
    var lastWaveT = -60f
    while (t < maxT && !(stopOnOver && sim.over != null)) {
        thinkT += dt
        if (thinkT >= 0.6f) {
            thinkT = 0f
            val base = sim.playerBase(0)
            val mine = sim.buildings.filter { !it.dead && it.owner == 0 }
            val miners = sim.units.filter { !it.dead && it.owner == 0 && it.def.isMiner }
            val army = sim.units.filter { !it.dead && it.owner == 0 && !it.def.isMiner }

            // энергоблок при дефиците
            val powerPending = mine.any { it.def.key == "power" && !it.constructed }
            if (!powerPending && sim.powerUsed(0) >= sim.powerProvided(0) && base != null) {
                tryPlace(sim, "power", base)
            }

            // шахтёры до нормы cfg.miners + бездельных — к руде
            if (base != null && base.constructed) {
                val queued = base.queue.count { it.key == "miner" }
                if (miners.size + queued < cfg.miners) sim.train(base, "miner")
            }
            for (m in miners) {
                if (m.order == "harvest" || m.order == "build") continue
                val ore = sim.map.nearestOre(m.x, m.y, 20) ?: continue
                sim.issueHarvest(m, ore.first, ore.second)
            }

            // казармы, завод, турели
            val barracksN = mine.count { it.def.key == "barracks" }
            val factoryN = mine.count { it.def.key == "factory" }
            val turretN = mine.count { it.def.key == "turret" }
            if (barracksN == 0 && sim.credits[0] >= 550 && base != null) tryPlace(sim, "barracks", base)
            else if (barracksN == 1 && factoryN == 0 && army.size >= 8 && sim.credits[0] > 1500 && base != null) {
                tryPlace(sim, "factory", base)
            }
            if (turretN < 2 && sim.credits[0] > 1100 && base != null) tryPlace(sim, "turret", base)

            // армия: каждая производственная стройка без очереди
            for (b in mine) {
                if (!b.constructed || b.def.trains.isEmpty() || b.queue.isNotEmpty()) continue
                val keys = b.def.trains.filter { sim.mod.unit(it)?.isMiner != true }
                if (keys.isNotEmpty()) sim.train(b, keys[rnd.nextInt(keys.size)])
            }

            // волны атак (как у ИИ: размер растёт)
            if (doAttack && army.size >= waveSize && t - lastWaveT > 30f) {
                val eb = sim.playerBase(1)
                if (eb != null) {
                    sim.issueMove(army, eb.x, eb.y, attackMove = true)
                    lastWaveT = t
                    waveSize = minOf(waveSize + 4, 20)
                }
            }
        }
        sim.update(dt)
        t += dt
    }
    return Outcome(sim.over, t)
}

private fun stats(mod: Mod, n: Int) {
    println("Баланс: $n партий на сложность (бот игрока против ИИ движка)")
    for (diff in listOf("easy", "normal", "hard")) {
        var win = 0
        var lose = 0
        var ongoing = 0
        var tsum = 0f
        var finished = 0
        for (s in 1..n) {
            val sim = Sim(mod, s.toLong() * 977L, diff)
            val (over, t) = play(sim, Random(s * 31 + 7), 900f, true)
            when (over) {
                "win" -> win++
                "lose" -> lose++
                else -> ongoing++
            }
            if (over != null) {
                tsum += t
                finished++
            }
        }
        val avg = if (finished > 0) (tsum / finished).toInt() else 0
        println("  $diff: побед $win, поражений $lose, не доиграно $ongoing, средняя длительность ${avg}с")
    }
}

/** Снимок мира в PPM (P6); конвертацию в PNG делает run.sh (PIL). */
private fun shot(mod: Mod, seed: Long, diff: String, seconds: Float, out: String) {
    val sim = Sim(mod, seed, diff)
    // бот игрока строит базу и обороняется (без атаки); симуляция не прерывается после конца партии
    val (over, t) = play(sim, Random(seed), seconds, doAttack = false, stopOnOver = false)
    println("снимок на ${t.toInt()}с: юнитов ${sim.units.size}, зданий ${sim.buildings.size}, over=${over}")

    // 1 пиксель изображения = 4 пикселя мира (тайл 32 -> 8 px картинки)
    val k = 4
    val px = 8
    val W = mod.worldW / k
    val H = mod.worldH / k
    val rgb = IntArray(W * H)

    fun fill(x0: Int, y0: Int, w: Int, h: Int, c: Int) {
        for (y in y0 until y0 + h) for (x in x0 until x0 + w) {
            if (x in 0 until W && y in 0 until H) rgb[y * W + x] = c
        }
    }

    // террейн
    for (ty in 0 until mod.mapH) for (tx in 0 until mod.mapW) {
        val i = sim.map.idx(tx, ty)
        val c = when (sim.map.terrain[i]) {
            Terrain.WATER -> 0x205A8A
            Terrain.DIRT -> 0x8A6D46
            Terrain.ORE -> 0x6B5237
            else -> 0x4A703A
        }
        fill(tx * px, ty * px, px, px, c)
        if (sim.map.terrain[i] == Terrain.ORE && sim.map.ore[i] > 0) {
            // золотое ядро жилы
            fill(tx * px + 2, ty * px + 2, px - 4, px - 4, 0xC5A528)
        }
    }
    // здания
    for (b in sim.buildings) {
        if (b.dead) continue
        val col = if (!b.constructed) 0x777777 else mod.factionColor(b.owner, true)
        fill(b.tx * px + 1, b.ty * px + 1, b.size * px - 2, b.size * px - 2, col)
    }
    // юниты
    for (u in sim.units) {
        if (u.dead) continue
        val cx = (u.x / k).toInt()
        val cy = (u.y / k).toInt()
        val r = (u.def.radius / k).toInt() + 1
        val col = mod.factionColor(u.owner, false)
        for (y in cy - r..cy + r) for (x in cx - r..cx + r) {
            val dx = x - cx
            val dy = y - cy
            if (dx * dx + dy * dy <= r * r) fill(x, y, 1, 1, col)
        }
    }

    val header = "P6\n$W $H\n255\n".toByteArray()
    val body = ByteArray(W * H * 3)
    for (i in 0 until W * H) {
        body[i * 3] = ((rgb[i] shr 16) and 0xFF).toByte()
        body[i * 3 + 1] = ((rgb[i] shr 8) and 0xFF).toByte()
        body[i * 3 + 2] = (rgb[i] and 0xFF).toByte()
    }
    File(out).writeBytes(header + body)
    println("сохранено: $out ($W x $H)")
}

fun main(args: Array<String>) {
    // мод — из репозитория (app/src/main/assets/mod), как увидит телефон;
    // отсутствующие/битые файлы -> встроенный ra2Defaults
    val dir = File("../../app/src/main/assets/mod")
    fun read(name: String) = File(dir, name).takeIf { it.exists() }?.readText()
    val mod = try {
        ModParse.parse(Mod.ra2Defaults(), read("mod.json"), read("units.json"), read("buildings.json"), read("difficulty.json"))
    } catch (t: Throwable) {
        System.err.println("мод не разобран (${t.message}), используется встроенный")
        Mod.ra2Defaults()
    }
    when (args.getOrNull(0)) {
        "stats" -> stats(mod, args.getOrNull(1)?.toInt() ?: 20)
        "shot" -> shot(
            mod,
            args.getOrNull(1)?.toLong() ?: 7L,
            args.getOrNull(2) ?: "normal",
            args.getOrNull(3)?.toFloat() ?: 240f,
            args.getOrNull(4) ?: "preview.ppm"
        )
        else -> println("использование: SimTools stats [N] | shot [seed] [diff] [seconds] [out.ppm]")
    }
}
