package com.arena.redradar.engine

/**
 * Платформо-независимый разбор контента мода в Mod.
 * Вход — тексты JSON-файлов (mod.json, units.json, buildings.json, difficulty.json);
 * каждый файл необязателен: отсутствующая или битая секция берётся
 * из встроенного сценария ra2Defaults().
 */
object ModParse {

    fun parse(
        base: Mod,
        modText: String?,
        unitsText: String?,
        buildingsText: String?,
        diffsText: String?
    ): Mod {
        val root = try { modText?.let { Json.parse(it) as Map<String, Any?> } } catch (t: Throwable) { null }

        // ---- фракции ----
        val factions = section(root?.get("factions") as? List<Any?>) { arr ->
            arr.mapIndexed { i, e ->
                val f = e as? Map<String, Any?> ?: return@section null
                FactionDef(
                    name = s(f, "name", "Фракция $i"),
                    color = rgb(f, "color"),
                    colorDark = rgb(f, "colorDark"),
                    colorLight = rgb(f, "colorLight"),
                    sprite = s(f, "sprite", "f$i")
                )
            }.filterNotNull().ifEmpty { null }
        } ?: base.factions

        // ---- юниты ----
        val units = section(unitsText?.let { Json.parse(it) as? List<Any?> }) { arr ->
            arr.mapNotNull { e ->
                val u = e as? Map<String, Any?> ?: return@mapNotNull null
                val key = str(u["key"]) ?: return@mapNotNull null
                UnitDef(
                    key = key,
                    name = s(u, "name", key),
                    cost = i(u, "cost", 100),
                    buildTime = d(u, "buildTime", 5.0).toFloat(),
                    hp = d(u, "hp", 100.0).toFloat(),
                    speed = d(u, "speed", 70.0).toFloat(),
                    dmg = d(u, "dmg", 0.0).toFloat(),
                    range = d(u, "range", 0.0).toFloat(),
                    atkCd = d(u, "atkCd", 1.0).toFloat(),
                    sight = d(u, "sight", 6.0).toFloat(),
                    radius = d(u, "radius", 9.0).toFloat(),
                    isMiner = b(u, "isMiner", false),
                    projectile = b(u, "projectile", false),
                    sprite = s(u, "sprite", "")
                )
            }.ifEmpty { null }
        } ?: base.unitsList

        // ---- здания ----
        val buildings = section(buildingsText?.let { Json.parse(it) as? List<Any?> }) { arr ->
            arr.mapNotNull { e ->
                val bld = e as? Map<String, Any?> ?: return@mapNotNull null
                val key = str(bld["key"]) ?: return@mapNotNull null
                BuildingDef(
                    key = key,
                    name = s(bld, "name", key),
                    cost = i(bld, "cost", 500),
                    buildTime = d(bld, "buildTime", 10.0).toFloat(),
                    hp = d(bld, "hp", 500.0).toFloat(),
                    size = i(bld, "size", 2),
                    powerUse = i(bld, "powerUse", 0),
                    powerGive = i(bld, "powerGive", 0),
                    trains = strList(bld["trains"]),
                    dropoff = b(bld, "dropoff", false),
                    dmg = d(bld, "dmg", 0.0).toFloat(),
                    range = d(bld, "range", 0.0).toFloat(),
                    atkCd = d(bld, "atkCd", 0.0).toFloat(),
                    projectile = b(bld, "projectile", false),
                    sprite = s(bld, "sprite", "")
                )
            }.ifEmpty { null }
        } ?: base.buildingsList

        // ---- сложности ----
        val diffs = section(diffsText?.let { Json.parse(it) as? List<Any?> }) { arr ->
            arr.mapNotNull { e ->
                val dd = e as? Map<String, Any?> ?: return@mapNotNull null
                val key = str(dd["key"]) ?: return@mapNotNull null
                DiffConfig(
                    key = key,
                    name = s(dd, "name", key),
                    income = d(dd, "income", 1.0).toFloat(),
                    wave0 = i(dd, "wave0", 5),
                    waveStep = i(dd, "waveStep", 3),
                    waveCap = i(dd, "waveCap", 18),
                    miners = i(dd, "miners", 7),
                    turrets = i(dd, "turrets", 1)
                )
            }.ifEmpty { null }
        } ?: base.diffs

        // ---- карта ----
        val mg = root?.get("map")?.let { m ->
            m as? Map<String, Any?> ?: return@let null
            val starts = (m["starts"] as? List<Any?>)?.mapNotNull { p ->
                (p as? List<Any?>)?.takeIf { it.size >= 2 }?.let {
                    Pair(n(it[0]).toInt(), n(it[1]).toInt())
                }
            } ?: emptyList()
            MapGenCfg(
                starts = starts.ifEmpty { base.mapGen.starts },
                waterFreq = d(m, "waterFreq", base.mapGen.waterFreq.toDouble()).toFloat(),
                dirtFreq = d(m, "dirtFreq", base.mapGen.dirtFreq.toDouble()).toFloat(),
                waterT = d(m, "waterT", base.mapGen.waterT.toDouble()).toFloat(),
                dirtT = d(m, "dirtT", base.mapGen.dirtT.toDouble()).toFloat(),
                borderWater = i(m, "borderWater", base.mapGen.borderWater),
                baseClearR = i(m, "baseClearR", base.mapGen.baseClearR),
                midVeins = i(m, "midVeins", base.mapGen.midVeins),
                oreBase = i(m, "oreBase", base.mapGen.oreBase),
                oreVar = i(m, "oreVar", base.mapGen.oreVar)
            )
        } ?: base.mapGen

        // ---- тексты ----
        val lbl = root?.get("labels")?.let { l ->
            l as? Map<String, Any?> ?: return@let null
            val abbr = (l["buildingAbbr"] as? Map<*, *>)
                ?.mapKeys { it.key.toString() }
                ?.mapValues { str(it.value) ?: "?" }
                ?.takeIf { it.isNotEmpty() } ?: base.labels.buildingAbbr
            LabelSet(
                titleTop = s(l, "titleTop", base.labels.titleTop),
                titleMain = s(l, "titleMain", base.labels.titleMain),
                subtitle = s(l, "subtitle", base.labels.subtitle),
                inspired = s(l, "inspired", base.labels.inspired),
                version = s(l, "version", base.labels.version),
                buildingAbbr = abbr,
                creditsSymbol = s(l, "creditsSymbol", base.labels.creditsSymbol),
                powerSymbol = s(l, "powerSymbol", base.labels.powerSymbol)
            )
        } ?: base.labels

        return Mod(
            id = s(root ?: emptyMap(), "id", base.id),
            mapW = i(root ?: emptyMap(), "mapW", base.mapW),
            mapH = i(root ?: emptyMap(), "mapH", base.mapH),
            unitsList = units,
            buildingsList = buildings,
            buildMenu = strList(root?.get("buildMenu")).ifEmpty { base.buildMenu },
            factions = factions,
            diffs = diffs,
            startCredits = i(root ?: emptyMap(), "startCredits", base.startCredits),
            unitCap = i(root ?: emptyMap(), "unitCap", base.unitCap),
            mapGen = mg,
            labels = lbl
        )
    }

    // ---- вспомогательные доступы к дереву Json.parse ----

    private inline fun <T : Any> section(tree: Any?, map: (List<Any?>) -> List<T>?): List<T>? =
        try { map(tree as? List<Any?> ?: return null) } catch (t: Throwable) { null }

    private fun str(v: Any?): String? = v as? String

    private fun n(v: Any?): Double = (v as? Number)?.toDouble() ?: 0.0

    private fun s(m: Map<String, Any?>, key: String, def: String): String = str(m[key]) ?: def

    private fun i(m: Map<String, Any?>, key: String, def: Int): Int =
        (m[key] as? Number)?.toInt() ?: def

    private fun d(m: Map<String, Any?>, key: String, def: Double): Double =
        (m[key] as? Number)?.toDouble() ?: def

    private fun b(m: Map<String, Any?>, key: String, def: Boolean): Boolean =
        (m[key] as? Boolean) ?: def

    private fun strList(v: Any?): List<String> =
        (v as? List<Any?>)?.mapNotNull { str(it) } ?: emptyList()

    private fun rgb(m: Map<String, Any?>, key: String): Int {
        val a = m[key] as? List<Any?> ?: return 0x888888
        val r = (a.getOrNull(0) as? Number)?.toInt() ?: 0x88
        val g = (a.getOrNull(1) as? Number)?.toInt() ?: 0x88
        val bl = (a.getOrNull(2) as? Number)?.toInt() ?: 0x88
        return (r shl 16) or (g shl 8) or bl
    }
}
