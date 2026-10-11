package org.ungoogled.patches.waze

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.floatOption
import app.morphe.patcher.patch.intOption
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import org.ungoogled.patches.waze.WazeConstants.COMPATIBILITY_WAZE
import java.util.Locale

/** The map's structure: label sizes, line widths, the navigation arrow. */
private val SKIN_STRUCTURES = listOf(
    "assets/res/skins/default/skin_structure.main.lua",
    "assets/res/skins/default/experiment/skin_structure.main.lua",
)

/** The day map's colour tables. */
private val DAY_SKINS = listOf(
    "assets/res/skins/default/skin_values.day.lua",
    "assets/res/skins/default/skin_values.editor.day.lua",
    "assets/res/skins/default/experiment/skin_values.day.lua",
    "assets/res/skins/default/experiment/skin_values.editor.day.lua",
)

/** A colour option as six hex digits, whatever the user typed ("#ebe7dd", "0xEBE7DD", "ebe7dd"). */
private fun hexColour(value: String?, what: String): String {
    val hex = value?.trim()?.removePrefix("#")?.removePrefix("0x")?.removePrefix("0X") ?: ""
    if (!Regex("[0-9a-fA-F]{6}").matches(hex)) throw PatchException("$what must be six hex digits, got \"$value\"")
    return hex.uppercase(Locale.ROOT)
}

/**
 * Why Map skin, and Full black's black night map, are off by default and skipped by newer patchers:
 * they change files under `assets/res/`, and Morphe Manager 1.35 (patcher 1.15) then also writes
 * empty folder entries into the APK for every parent folder of a changed asset. Waze 5.24's native
 * map engine treats the `assets/res/` folder entry as a file and crashes about a second after the
 * map appears, on every launch. Morphe Desktop (patcher 1.14) writes no folder entries, so its
 * builds run.
 */
internal const val MANAGER_ASSET_WARNING =
    "Skipped by Morphe Manager 1.35 and other patcher 1.15+ builds, which would crash Waze when the map " +
        "opens; patch with Morphe Desktop to get it."

/** The version of the patcher running these patches ("1.15.1"), from its own version.properties; null if unreadable. */
private fun runningPatcherVersion(): String? = try {
    // The patcher's classes come from the app running the patches (Morphe Manager or Desktop), not from
    // this bundle, so this is that app's patcher.
    PatchException::class.java.getResourceAsStream("/app/morphe/patcher/version.properties")?.use { stream ->
        java.util.Properties().apply { load(stream) }.getProperty("version")?.trim()
    }
} catch (_: Throwable) {
    null
}

/**
 * Whether Waze's assets can be changed without crashing Waze: only under a patcher from before 1.15,
 * which writes no folder entries. Logs why when not, naming [what] was skipped.
 */
internal fun wazeAssetEditsSafe(what: String): Boolean {
    val version = runningPatcherVersion()
    val parts = version?.split('.', '-')?.mapNotNull { it.toIntOrNull() }.orEmpty()
    val safe = parts.size >= 2 && (parts[0] < 1 || (parts[0] == 1 && parts[1] < 15))
    if (!safe) {
        // The patcher's own logger, which Morphe Desktop prints and Morphe Manager shows in its patch log.
        java.util.logging.Logger.getLogger("app.morphe.patcher.Patcher").warning(
            "$what skipped: patcher ${version ?: "(unknown version)"} adds folder entries to the APK that crash " +
                "Waze when the map opens. Patch with Morphe Desktop to get it.",
        )
    }
    return safe
}

@Suppress("unused")
val wazeMapSkinPatch = resourcePatch(
    name = "Map skin",
    description = "The old Waze mods' map skin, as changes to Waze's own: bigger map labels, a wider " +
        "navigation arrow head and a warm day background. The night background is Full black's. " +
        MANAGER_ASSET_WARNING,
    default = false,
) {
    compatibleWith(COMPATIBILITY_WAZE)

    val fontHuge by intOption(key = "fontHuge", default = 18, title = "Map labels: huge", description = "Waze: 14.", required = true)
    val fontBig by intOption(key = "fontBig", default = 14, title = "Map labels: big", description = "Waze: 12.", required = true)
    val fontMedium by intOption(key = "fontMedium", default = 12, title = "Map labels: medium", description = "Waze: 10.", required = true)
    val fontSmall by intOption(key = "fontSmall", default = 10, title = "Map labels: small", description = "Waze: 8.", required = true)
    val arrowHead by floatOption(
        key = "arrowHead",
        default = 10.0f,
        title = "Navigation arrow head width",
        description = "As a multiple of the arrow's tail width. Waze: 3.0.",
        required = true,
    )
    val dayBackground by stringOption(
        key = "dayBackground",
        default = "ebe7dd",
        title = "Day map background (hex)",
        description = "Waze: fafcfa, near white.",
        required = true,
    )

    execute {
        if (!wazeAssetEditsSafe("Map skin")) return@execute
        val fonts = mapOf("huge" to fontHuge!!, "big" to fontBig!!, "medium" to fontMedium!!, "small" to fontSmall!!)
        val head = String.format(Locale.ROOT, "%.1f", arrowHead!!)
        for (path in SKIN_STRUCTURES) {
            val skin = get(path)
            if (!skin.isFile) throw PatchException("$path not found")
            val before = skin.readText()
            // The label sizes live in one table; the same names in ShieldScaleFactors must stay.
            val table = Regex("FontSizes\\s*=\\s*\\{[^}]*\\}").find(before) ?: throw PatchException("$path has no FontSizes")
            var sizes = table.value
            for ((name, size) in fonts) {
                val sized = sizes.replace(Regex("\\b$name\\s*=\\s*\\d+"), "$name = $size")
                if (sized == sizes) throw PatchException("$path: FontSizes has no $name")
                sizes = sized
            }
            val after = before.replace(table.value, sizes)
                .replace(Regex("(\\bnav_arrow_head_width_factor\\s*=\\s*)[0-9.]+"), "$1$head")
            if (!after.contains("nav_arrow_head_width_factor = $head")) throw PatchException("$path has no nav_arrow_head_width_factor")
            skin.writeText(after)
        }

        val day = hexColour(dayBackground, "the day background")
        for (path in DAY_SKINS) {
            val skin = get(path)
            if (!skin.isFile) throw PatchException("$path not found")
            val before = skin.readText()
            val after = before
                .replace(Regex("(\\bmap_background\\s*=\\s*rgb\\()0x[0-9a-fA-F]{6}"), "$10x$day")
                .replace(Regex("(\\bmap_missing\\s*=\\s*rgb\\()0x[0-9a-fA-F]{6}"), "$10x$day")
            if (after == before) throw PatchException("$path has no map background")
            skin.writeText(after)
        }
    }
}
