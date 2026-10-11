package org.ungoogled.patches.alltrails.profile

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_ALLTRAILS
import org.ungoogled.patches.shared.addInstructionsAtLabel
import org.w3c.dom.Element

private const val ACTIVITY = "org.ungoogled.alltrails.ExperimentalActivity"
private const val EXPERIMENTAL = "Lorg/ungoogled/alltrails/Experimental;"
private const val APPLICATION = "Lcom/alltrails/alltrails/app/AllTrailsApplication;"

/**
 * Rewrites [extension]'s patched() -- `return false` in the extension -- to return
 * true. The Experimental screen offers a feature's row only once its patch did this.
 */
internal fun BytecodePatchContext.markPatched(extension: String) {
    val method = mutableClassDefBy(extension).methods.singleOrNull {
        it.name == "patched" && it.parameterTypes.isEmpty() && it.returnType == "Z"
    } ?: throw PatchException("extension marker $extension->patched() not found")
    val first = method.implementation!!.instructions.first()
    if (first.opcode != Opcode.CONST_4 || (first as NarrowLiteralInstruction).narrowLiteral != 0) {
        throw PatchException("$extension->patched() no longer starts with const/4 v0, 0x0")
    }
    method.replaceInstruction(0, "const/4 v0, 0x1")
}

/** AllTrailsApplication.onCreate, where the features' switches are read (Experimental.init). */
private object ApplicationOnCreateFingerprint : Fingerprint(
    name = "onCreate",
    returnType = "V",
    parameters = emptyList(),
    custom = { _, classDef -> classDef.type == APPLICATION },
)

/** Declares the screen; it is launched by explicit class from inside the app only. */
private val experimentalManifestPatch = resourcePatch(
    description = "Declares the Experimental screen.",
) {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as Element
            val activity = manifest.createElement("activity")
            activity.setAttribute("android:name", ACTIVITY)
            activity.setAttribute("android:exported", "false")
            activity.setAttribute("android:label", "Experimental")
            // The app's own theme: day/night windows, no action bar, like its other screens.
            activity.setAttribute("android:theme", "@style/DenaliAllTrailsMigration")
            activity.setAttribute("android:screenOrientation", "portrait")
            application.appendChild(activity)
        }
    }
}

/**
 * UserDetailsFragment's overflow-menu prepare callback (a static lambda body,
 * `(fragment, Menu) -> Unit`). BannerToolbar inflates the profile menu into a
 * new PopupMenu on every tap and hands it to this callback before showing it;
 * it runs for both the self and the third-party menu and shows or hides the
 * third-party rows, which is what pins it down.
 */
private object ProfileMenuPrepareFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "Lkotlin/Unit;",
    parameters = listOf("L", "Landroid/view/Menu;"),
    filters = listOf(
        fieldAccess(opcode = Opcode.SGET, name = "menu_report_block_user", type = "I"),
        fieldAccess(opcode = Opcode.SGET, name = "menu_unblock_user", type = "I"),
        fieldAccess(opcode = Opcode.SGET, name = "menu_remove_follower", type = "I"),
    ),
)

@Suppress("unused")
val experimentalScreenPatch = bytecodePatch(
    name = "Experimental screen",
    description = "Adds an Experimental row to your profile's menu, under Edit profile and Share profile, " +
        "that opens an Experimental screen.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_ALLTRAILS)
    dependsOn(experimentalManifestPatch)
    extendWith("extensions/alltrails.mpe")

    execute {
        // Read the switches as the app starts, right after super.onCreate.
        ApplicationOnCreateFingerprint.method.apply {
            val superCall = implementation!!.instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_SUPER && ((it as ReferenceInstruction).reference as MethodReference).name == "onCreate"
            }
            if (superCall < 0) throw PatchException("AllTrailsApplication.onCreate no longer calls super.onCreate")
            addInstructionsAtLabel(superCall + 1, "invoke-static { p0 }, $EXPERIMENTAL->init(Landroid/content/Context;)V")
        }

        ProfileMenuPrepareFingerprint.let { fp ->
            val method = fp.method
            // The R class the callback reads its row ids from (R8 keeps R field names, not the class name).
            val ids = ((fp.instructionMatches.first().instruction as ReferenceInstruction).reference as FieldReference).definingClass
            val fragment = method.parameterTypes.first().toString()
            if (fragment == "Ljava/lang/Object;") throw PatchException("menu callback no longer takes the fragment")
            // Static, two object parameters: at index 0 every local is still free.
            val impl = method.implementation!!
            if (impl.registerCount - 2 < 2) throw PatchException("menu callback has no free local registers")
            method.addInstructionsAtLabel(
                0,
                """
                    invoke-virtual { p0 }, Landroidx/fragment/app/Fragment;->getContext()Landroid/content/Context;
                    move-result-object v0
                    sget v1, $ids->menu_edit_profile:I
                    invoke-static { v0, p1, v1 }, $EXPERIMENTAL->addMenuItem(Landroid/content/Context;Landroid/view/Menu;I)V
                """,
            )
        }
    }
}
