package com.arena.redradar.engine

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Загрузчик мода из assets/mod — файлы mod.json, units.json,
 * buildings.json, difficulty.json.
 * Каждый файл необязателен: отсутствующие секции берутся
 * из встроенного сценария ra2Defaults().
 */
object ModJson {

    fun load(assets: AssetManager): Mod {
        val base = Mod.ra2Defaults()
        val root = try {
            val s = assets.open("mod/mod.json").use { it.readBytes().decodeToString() }
            JSONObject(s)
        } catch (t: Throwable) {
            return base
        }

        // ---- фракции ----
        val factions = if (root.has("factions")) {
            val arr = root.getJSONArray("factions")
            (0 until arr.length()).map { i ->
                val f = arr.getJSONObject(i)
                FactionDef(
                    name = f.optString("name", "Фракция $i"),
                    color = rgb(f, "color"),
                    colorDark = rgb(f, "colorDark"),
                    colorLight = rgb(f, "colorLight"),
                    sprite = f.optString("sprite", "f$i")
                )
            }.ifEmpty { base.factions }
        } else base.factions

        // ---- юниты ----
        val units = try {
            val arr = JSONArray(loadText(assets, "mod/units.json"))
            (0 until arr.length()).map { i ->
                val u = arr.getJSONObject(i)
                UnitDef(
                    key = u.getString("key"),
                    name = u.optString("name", u.getString("key")),
                    cost = u.optInt("cost", 100),
                    buildTime = u.optDouble("buildTime", 5.0).toFloat(),
                    hp = u.optDouble("hp", 100.0).toFloat(),
                    speed = u.optDouble("speed", 70.0).toFloat(),
                    dmg = u.optDouble("dmg", 0.0).toFloat(),
                    range = u.optDouble("range", 0.0).toFloat(),
                    atkCd = u.optDouble("atkCd", 1.0).toFloat(),
                    sight = u.optDouble("sight", 6.0).toFloat(),
                    radius = u.optDouble("radius", 9.0).toFloat(),
                    isMiner = u.optBoolean("isMiner", false),
                    projectile = u.optBoolean("projectile", false),
                    sprite = u.optString("sprite", "")
                )
            }.ifEmpty { base.unitsList }
        } catch (t: Throwable) { base.unitsList }

        // ---- здания ----
        val buildings = try {
            val arr = JSONArray(loadText(assets, "mod/buildings.json"))
            (0 until arr.length()).map { i ->
                val b = arr.getJSONObject(i)
                val trains = mutableListOf<String>()
                if (b.has("trains")) {
                    val ta = b.getJSONArray("trains")
                    for (k in 0 until ta.length()) trains.add(ta.getString(k))
                }
                BuildingDef(
                    key = b.getString("key"),
                    name = b.optString("name", b.getString("key")),
                    cost = b.optInt("cost", 500),
                    buildTime = b.optDouble("buildTime", 10.0).toFloat(),
                    hp = b.optDouble("hp", 500.0).toFloat(),
                    size = b.optInt("size", 2),
                    powerUse = b.optInt("powerUse", 0),
                    powerGive = b.optInt("powerGive", 0),
                    trains = trains,
                    dropoff = b.optBoolean("dropoff", false),
                    dmg = b.optDouble("dmg", 0.0).toFloat(),
                    range = b.optDouble("range", 0.0).toFloat(),
                    atkCd = b.optDouble("atkCd", 0.0).toFloat(),
                    projectile = b.optBoolean("projectile", false),
                    sprite = b.optString("sprite", "")
                )
            }.ifEmpty { base.buildingsList }
        } catch (t: Throwable) { base.buildingsList }

        // ---- сложности ----
        val diffs = try {
            val arr = JSONArray(loadText(assets, "mod/difficulty.json"))
            (0 until arr.length()).map { i ->
                val d = arr.getJSONObject(i)
                DiffConfig(
                    key = d.getString("key"),
                    name = d.optString("name", d.getString("key")),
                    income = d.optDouble("income", 1.0).toFloat(),
                    wave0 = d.optInt("wave0", 5),
                    waveStep = d.optInt("waveStep", 3),
                    waveCap = d.optInt("waveCap", 18),
                    miners = d.optInt("miners", 7),
                    turrets = d.optInt("turrets", 1)
                )
            }.ifEmpty { base.diffs }
        } catch (t: Throwable) { base.diffs }

        // ---- карта ----
        val mg = if (root.has("map")) {
            val m = root.getJSONObject("map")
            val starts = mutableListOf<Pair<Int, Int>>()
            if (m.has("starts")) {
                val sa = m.getJSONArray("starts")
                for (k in 0 until sa.length()) {
                    val p = sa.getJSONArray(k)
                    starts.add(Pair(p.getInt(0), p.getInt(1)))
                }
            }
            MapGenCfg(
                starts = starts.ifEmpty { base.mapGen.starts },
                waterFreq = m.optDouble("waterFreq", base.mapGen.waterFreq.toDouble()).toFloat(),
                dirtFreq = m.optDouble("dirtFreq", base.mapGen.dirtFreq.toDouble()).toFloat(),
                waterT = m.optDouble("waterT", base.mapGen.waterT.toDouble()).toFloat(),
                dirtT = m.optDouble("dirtT", base.mapGen.dirtT.toDouble()).toFloat(),
                borderWater = m.optInt("borderWater", base.mapGen.borderWater),
                baseClearR = m.optInt("baseClearR", base.mapGen.baseClearR),
                midVeins = m.optInt("midVeins", base.mapGen.midVeins),
                oreBase = m.optInt("oreBase", base.mapGen.oreBase),
                oreVar = m.optInt("oreVar", base.mapGen.oreVar)
            )
        } else base.mapGen

        // ---- тексты ----
        val lbl = if (root.has("labels")) {
            val l = root.getJSONObject("labels")
            val abbr = mutableMapOf<String, String>()
            if (l.has("buildingAbbr")) {
                val a = l.getJSONObject("buildingAbbr")
                for (k in a.keys()) abbr[k] = a.optString(k, "?")
            }
            LabelSet(
                titleTop = l.optString("titleTop", base.labels.titleTop),
                titleMain = l.optString("titleMain", base.labels.titleMain),
                subtitle = l.optString("subtitle", base.labels.subtitle),
                inspired = l.optString("inspired", base.labels.inspired),
                version = l.optString("version", base.labels.version),
                buildingAbbr = abbr.ifEmpty { base.labels.buildingAbbr },
                creditsSymbol = l.optString("creditsSymbol", base.labels.creditsSymbol),
                powerSymbol = l.optString("powerSymbol", base.labels.powerSymbol)
            )
        } else base.labels

        return Mod(
            id = root.optString("id", base.id),
            mapW = root.optInt("mapW", base.mapW),
            mapH = root.optInt("mapH", base.mapH),
            unitsList = units,
            buildingsList = buildings,
            buildMenu = stringList(root.optJSONArray("buildMenu")).ifEmpty { base.buildMenu },
            factions = factions,
            diffs = diffs,
            startCredits = root.optInt("startCredits", base.startCredits),
            unitCap = root.optInt("unitCap", base.unitCap),
            mapGen = mg,
            labels = lbl
        )
    }

    private fun loadText(assets: AssetManager, path: String): String =
        assets.open(path).use { it.readBytes().decodeToString() }

    private fun stringList(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        return (0 until a.length()).map { a.getString(it) }
    }

    private fun rgb(o: JSONObject, key: String): Int {
        val a = o.optJSONArray(key) ?: return 0x888888
        val r = a.optInt(0, 0x88)
        val g = a.optInt(1, 0x88)
        val b = a.optInt(2, 0x88)
        return (r shl 16) or (g shl 8) or b
    }
}
