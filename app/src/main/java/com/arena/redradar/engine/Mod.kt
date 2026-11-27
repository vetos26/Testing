package com.arena.redradar.engine

/**
 * ============================================================
 *  ДВИЖОК СТРАТЕГИЙ (в стиле классических RTS)
 * ============================================================
 *  Весь игровой контент описывается объектом Mod:
 *  юниты, здания, фракции (цвета/спрайты), сложности,
 *  генерация карты, тексты интерфейса.
 *
 *  Данные загружаются из assets/mod/ — файлы mod.json, units.json,
 *  buildings.json, difficulty.json (см. ModJson);
 *  если ассетов нет — используется встроенный сценарий
 *  «Красный радар» (ra2Defaults). Новая стратегия = новый
 *  набор JSON-файлов, без изменения кода движка.
 *
 *  Сетка мира — константа движка: TILE пикселей на тайл.
 */

/** Размер тайла в пикселях мира (константа движка). */
const val TILE = 32

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
    val projectile: Boolean = false,
    /** База имени спрайта (к нему добавляется _<фракция>). */
    val sprite: String = ""
) {
    val spriteBase: String get() = if (sprite.isEmpty()) key else sprite
}

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
    val projectile: Boolean = false,
    val sprite: String = ""
) {
    val spriteBase: String get() = if (sprite.isEmpty()) key else sprite
}

/** Фракция: цвета и суффикс спрайтов (индекс в списке = pid игрока). */
class FactionDef(
    val name: String,
    val color: Int,          // RGB
    val colorDark: Int,
    val colorLight: Int,
    val sprite: String       // суффикс спрайтов, напр. "red"
)

/** Настройка сложности (для ИИ). */
class DiffConfig(
    val key: String,
    val name: String,
    val income: Float,
    val wave0: Int,
    val waveStep: Int,
    val waveCap: Int,
    val miners: Int,
    val turrets: Int
)

/** Параметры генерации карты. */
class MapGenCfg(
    val starts: List<Pair<Int, Int>>,   // стартовые тайлы баз (по числу игроков)
    val waterFreq: Float = 0.085f,
    val dirtFreq: Float = 0.11f,
    val waterT: Float = 0.64f,
    val dirtT: Float = 0.72f,
    val borderWater: Int = 2,
    val baseClearR: Int = 4,
    val midVeins: Int = 6,
    val oreBase: Int = 250,
    val oreVar: Int = 150
)

/** Тексты интерфейса (мод задаёт свои). */
class LabelSet(
    val titleTop: String,
    val titleMain: String,
    val subtitle: String,
    val inspired: String,
    val version: String,
    val buildingAbbr: Map<String, String>,
    val creditsSymbol: String = "₽",
    val powerSymbol: String = "⚡"
)

/**
 * Мод = полное описание одной стратегии.
 */
class Mod(
    val id: String,
    val mapW: Int,
    val mapH: Int,
    val unitsList: List<UnitDef>,
    val buildingsList: List<BuildingDef>,
    val buildMenu: List<String>,
    val factions: List<FactionDef>,
    val diffs: List<DiffConfig>,
    val startCredits: Int,
    val unitCap: Int,
    val mapGen: MapGenCfg,
    val labels: LabelSet
) {
    val worldW: Int get() = mapW * TILE
    val worldH: Int get() = mapH * TILE

    val units: Map<String, UnitDef> = unitsList.associateBy { it.key }
    val buildings: Map<String, BuildingDef> = buildingsList.associateBy { it.key }

    fun unit(key: String): UnitDef? = units[key]
    fun building(key: String): BuildingDef? = buildings[key]

    fun diff(key: String): DiffConfig =
        diffs.firstOrNull { it.key == key }
            ?: diffs.firstOrNull { it.key == "normal" }
            ?: diffs[0]

    fun diffLabel(key: String): String = diff(key).name

    /** Цвет фракции игрока pid (dark — тёмный оттенок). */
    fun factionColor(pid: Int, dark: Boolean): Int {
        val f = factions[pid % factions.size]
        return if (dark) f.colorDark else f.color
    }

    fun factionSprite(pid: Int): String = factions[pid % factions.size].sprite

    // ------------------------------------------------------------
    //  Встроенный сценарий «Красный радар» (та же балансировка,
    //  что была в коде; служит fallback при отсутствии JSON).
    // ------------------------------------------------------------
    companion object {
        fun ra2Defaults(): Mod {
            val units = listOf(
                UnitDef("miner", "Шахтёр", 700, 10f, 80f, 70f, 4f, 14f, 1f, 5f, 10f, isMiner = true),
                UnitDef("rifle", "Стрелок", 120, 5f, 90f, 78f, 8f, 100f, 0.8f, 6f, 9f),
                UnitDef("rocket", "Ракетчик", 180, 7f, 70f, 76f, 22f, 165f, 1.5f, 6f, 9f, projectile = true),
                UnitDef("tank", "Танк", 650, 15f, 260f, 92f, 30f, 140f, 1.6f, 7f, 13f, projectile = true)
            )
            val buildings = listOf(
                BuildingDef("base", "Штаб", 1500, 25f, 1000f, 3, powerUse = 5, trains = listOf("miner"), dropoff = true),
                BuildingDef("power", "Энергоблок", 300, 8f, 400f, 2, powerGive = 40),
                BuildingDef("barracks", "Казармы", 550, 14f, 600f, 3, powerUse = 10, trains = listOf("rifle", "rocket")),
                BuildingDef("factory", "Военный завод", 1200, 20f, 800f, 3, powerUse = 20, trains = listOf("tank")),
                BuildingDef("turret", "Турель", 650, 12f, 400f, 2, powerUse = 6, dmg = 25f, range = 190f, atkCd = 1.2f, projectile = true)
            )
            val factions = listOf(
                FactionDef("Красные", 0xCE3E3E, 0x8C2020, 0xEC7466, "red"),
                FactionDef("Синие", 0x4A78D8, 0x223E7C, 0x7EAAF0, "blue")
            )
            val diffs = listOf(
                DiffConfig("easy", "ЛЕГКО", 0.8f, 6, 4, 14, 5, 0),
                DiffConfig("normal", "НОРМАЛЬНО", 1f, 5, 3, 18, 7, 1),
                DiffConfig("hard", "СЛОЖНО", 1.25f, 5, 2, 22, 9, 2)
            )
            val mapGen = MapGenCfg(
                starts = listOf(Pair(9, 45), Pair(46, 10))
            )
            val labels = LabelSet(
                titleTop = "RTS",
                titleMain = "ENGINE",
                subtitle = "стратегия в реальном времени",
                inspired = "вдохновлено Red Alert 2",
                version = "RTS Engine — версия 1.0",
                buildingAbbr = mapOf(
                    "base" to "ШТ", "power" to "ЭН", "barracks" to "КАЗ",
                    "factory" to "ЗАВ", "turret" to "ТУР"
                )
            )
            return Mod(
                id = "redradar",
                mapW = 56, mapH = 56,
                unitsList = units,
                buildingsList = buildings,
                buildMenu = listOf("power", "barracks", "factory", "turret", "base"),
                factions = factions,
                diffs = diffs,
                startCredits = 3000,
                unitCap = 40,
                mapGen = mapGen,
                labels = labels
            )
        }
    }
}
