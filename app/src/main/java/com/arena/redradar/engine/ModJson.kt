package com.arena.redradar.engine

import android.content.res.AssetManager

/**
 * Загрузчик мода из assets/mod (Android): mod.json, units.json,
 * buildings.json, difficulty.json. Разбор — платформо-независимый
 * ModParse; каждый файл необязателен (fallback ra2Defaults).
 */
object ModJson {

    fun load(assets: AssetManager): Mod {
        fun read(path: String): String? = try {
            assets.open(path).use { it.readBytes().decodeToString() }
        } catch (t: Throwable) {
            null
        }
        return ModParse.parse(
            Mod.ra2Defaults(),
            read("mod/mod.json"),
            read("mod/units.json"),
            read("mod/buildings.json"),
            read("mod/difficulty.json")
        )
    }
}
