package org.ungoogled.patches.alltrails.misc

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_ALLTRAILS
import org.w3c.dom.Document
import org.w3c.dom.Element

private const val STOCK_PACKAGE = "com.alltrails.alltrails"

/** The one authority AllTrails' code spells out instead of building it from getPackageName(). */
private const val STOCK_FILE_PROVIDER = "$STOCK_PACKAGE.provider"

private fun Document.elements(tag: String): List<Element> =
    getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

/**
 * Installs AllTrails under a package name of your choosing, alongside stock AllTrails.
 *
 * What names this app itself moves with the package, since two installed apps
 * cannot share it:
 *
 * - the package;
 * - all seven provider authorities ("<package>.provider" and friends);
 * - its one custom permission (DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION, which
 *   AndroidX builds from getPackageName() to keep the app's own receivers
 *   private) and the uses-permission that holds it;
 * - the two meta-data values that are the package name (satellite data
 *   optimisation and hostAppId), the Firebase Messaging receiver's category,
 *   and the Facebook login redirect host (fbconnect://cct.<package>, which the
 *   Facebook SDK builds from getPackageName());
 * - the four static launcher shortcuts' target package;
 * - and in code, every "com.alltrails.alltrails.provider" literal: AllTrails
 *   hands FileProvider that authority as a constant when it shares a photo or
 *   a recording, so left alone, sharing would look for stock AllTrails'
 *   provider and throw.
 *
 * Deliberately left alone: component class names (real classes, which do not
 * move), the shortcuts' target class (StartActivity, a real class), the
 * alltrails:// deep-link schemes, and the two other "com.alltrails.alltrails"
 * literals in code -- the Prod environment's application id and the app id in
 * the login request -- which identify the app to AllTrails' servers.
 */
@Suppress("unused")
val changePackageNamePatch = bytecodePatch(
    name = "Change package name",
    description = "Installs alongside stock AllTrails under its own package name.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_ALLTRAILS)

    val packageName = stringOption(
        key = "packageName",
        default = "org.ungoogled.alltrails",
        title = "Package name",
        description = "The package name to install under. Must be a valid Android package name.",
        required = true,
    ) {
        it != null && it != STOCK_PACKAGE && it.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$"))
    }

    dependsOn(
        resourcePatch(description = "Moves the manifest and launcher shortcuts to the new package name.") {
            execute {
                val old = STOCK_PACKAGE
                val new = packageName.value!!
                val counts = linkedMapOf<String, Int>()
                fun count(what: String) { counts[what] = (counts[what] ?: 0) + 1 }
                fun under(value: String) = value.startsWith("$old.")

                document("AndroidManifest.xml").use { manifest ->
                    val root = manifest.documentElement
                    if (root.getAttribute("package") == old) {
                        root.setAttribute("package", new)
                        count("package")
                    }

                    val ownPermissions = manifest.elements("permission").map { it.getAttribute("android:name") }.filter(::under).toSet()
                    for (element in manifest.elements("permission") + manifest.elements("uses-permission")) {
                        val name = element.getAttribute("android:name")
                        if (name in ownPermissions) {
                            element.setAttribute("android:name", new + name.substring(old.length))
                            count(element.tagName)
                        }
                    }

                    // Only the app's own providers, not <queries> entries naming other apps' ones.
                    val application = manifest.elements("application").single()
                    for (provider in manifest.elements("provider").filter { it.parentNode == application }) {
                        val authorities = provider.getAttribute("android:authorities").split(';')
                        val renamed = authorities.map { if (under(it)) new + it.substring(old.length) else it }
                        if (renamed != authorities) {
                            provider.setAttribute("android:authorities", renamed.joinToString(";"))
                            repeat(renamed.zip(authorities).count { (a, b) -> a != b }) { count("authority") }
                        }
                    }

                    for (meta in manifest.elements("meta-data")) {
                        if (meta.getAttribute("android:value") == old) {
                            meta.setAttribute("android:value", new)
                            count("meta-data")
                        }
                    }

                    // Firebase Messaging's receiver filters on the app's own package as a category.
                    for (category in manifest.elements("category")) {
                        if (category.getAttribute("android:name") == old) {
                            category.setAttribute("android:name", new)
                            count("category")
                        }
                    }

                    // Facebook login returns to fbconnect://cct.<getPackageName()>, so the filter has to follow.
                    for (data in manifest.elements("data")) {
                        if (data.getAttribute("android:host") == "cct.$old") {
                            data.setAttribute("android:host", "cct.$new")
                            count("facebook redirect")
                        }
                    }
                }

                document("res/xml/shortcuts.xml").use { shortcuts ->
                    for (intent in shortcuts.elements("intent")) {
                        if (intent.getAttribute("android:targetPackage") == old) {
                            intent.setAttribute("android:targetPackage", new)
                            count("shortcut")
                        }
                    }
                }

                // Measured against this AllTrails version; anything less means the manifest changed shape.
                val expected = mapOf(
                    "package" to 1, "permission" to 1, "uses-permission" to 1,
                    "authority" to 7, "meta-data" to 2, "category" to 1, "facebook redirect" to 1, "shortcut" to 4,
                )
                val short = expected.filter { (k, v) -> (counts[k] ?: 0) < v }
                if (short.isNotEmpty()) {
                    throw PatchException("package rename found less than expected: " + short.keys.joinToString { "$it ${counts[it] ?: 0}/${expected[it]}" })
                }
            }
        },
    )

    execute {
        val provider = packageName.value!! + STOCK_FILE_PROVIDER.substring(STOCK_PACKAGE.length)
        val sites = mutableListOf<Pair<String, com.android.tools.smali.dexlib2.iface.Method>>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (method in classDef.methods) {
                val literal = method.implementation?.instructions?.any { insn ->
                    (insn.opcode == Opcode.CONST_STRING || insn.opcode == Opcode.CONST_STRING_JUMBO) &&
                        ((insn as ReferenceInstruction).reference as StringReference).string == STOCK_FILE_PROVIDER
                } ?: false
                if (literal) sites += classDef.type to method
            }
        }

        var rewritten = 0
        for ((type, method) in sites) {
            val mutableMethod = mutableClassDefBy(type).methods.first {
                it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
            }
            val instructions = mutableMethod.implementation!!.instructions
            for (index in instructions.indices) {
                val insn = instructions[index]
                if (insn.opcode != Opcode.CONST_STRING && insn.opcode != Opcode.CONST_STRING_JUMBO) continue
                if (((insn as ReferenceInstruction).reference as StringReference).string != STOCK_FILE_PROVIDER) continue
                mutableMethod.replaceInstruction(index, "const-string v${(insn as OneRegisterInstruction).registerA}, \"$provider\"")
                rewritten++
            }
        }
        // Measured against this AllTrails version: ten FileProvider.getUriForFile calls.
        if (rewritten < 10) throw PatchException("expected 10 FileProvider authority literals, found $rewritten")
    }
}
