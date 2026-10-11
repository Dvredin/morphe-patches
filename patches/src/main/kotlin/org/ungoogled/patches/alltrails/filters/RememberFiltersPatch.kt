package org.ungoogled.patches.alltrails.filters

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.ungoogled.patches.alltrails.profile.experimentalScreenPatch
import org.ungoogled.patches.alltrails.profile.markPatched
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_ALLTRAILS
import org.ungoogled.patches.shared.addInstructionsAtLabel

private const val REMEMBER = "Lorg/ungoogled/alltrails/RememberFilters;"

/** ExploreFilterProvider.updateFilter(filter, fromUser, forceRequery): every new explore filter. */
private object FilterStoreUpdateFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(FILTER, "Z", "Z"),
    strings = listOf("Emitting new filter"),
)

/** ExploreFilterProvider.clearFilters: a fresh filter, when you go back from results to the home screen. */
private object FilterStoreClearFingerprint : Fingerprint(
    returnType = "V",
    parameters = emptyList(),
    strings = listOf("Clearing all filters"),
)

/**
 * The trail filters you set stay set (see RememberFilters in the extension).
 * AllTrails keeps them in ExploreFilterProvider, in memory only:
 *
 * 1. updateFilter, which every filter change goes through: what you set (fromUser) is saved.
 * 2. Its constructor's first update, AllTrails' empty default: replaced with one holding the
 *    saved choices, so they are back after the app restarts.
 * 3. clearFilters, which ExploreViewModel runs when you go back from search results to the
 *    home screen: its fresh filter gets the saved choices.
 */
@Suppress("unused")
val rememberFiltersPatch = bytecodePatch(
    name = "Remember filters",
    description = "Adds a Remember filter states switch to the Experimental screen. With it on, the trail filters " +
        "you set (length, elevation, rating, difficulty, activities, features and the rest, but not Distance away) " +
        "stay set when you go back to the home screen and after the app restarts, instead of resetting.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_ALLTRAILS)
    dependsOn(experimentalScreenPatch)

    execute {
        markPatched(REMEMBER)

        // 1. updateFilter(filter, fromUser, forceRequery). A plain virtual method: p1 and p2 are the parameters.
        val update = FilterStoreUpdateFingerprint.method
        val filterUpdate = update.implementation!!.instructions.firstOrNull { it.opcode == Opcode.NEW_INSTANCE }
            ?.let { ((it as ReferenceInstruction).reference as TypeReference).type }
            ?: throw PatchException("updateFilter no longer wraps the filter in an update")
        update.addInstructionsAtLabel(0, "invoke-static { p1, p2 }, $REMEMBER->save(Ljava/lang/Object;Z)V")

        // 2. The constructor: `processor = createDefault(FilterUpdate.Companion.default())`.
        val store = FilterStoreUpdateFingerprint.classDef
        store.methods.single { it.name == "<init>" }.apply {
            val code = implementation!!.instructions
            val default = code.indexOfFirst { (it as? ReferenceInstruction)?.reference.let { r -> r is MethodReference && r.returnType == filterUpdate } }
            val result = code.getOrNull(default + 1)
            if (default < 0 || result?.opcode != Opcode.MOVE_RESULT_OBJECT) throw PatchException("filter store no longer starts from a default update")
            val register = (result as OneRegisterInstruction).registerA
            if (register > 15) throw PatchException("filter store's default update register out of range")
            addInstructionsAtLabel(
                default + 2,
                """
                    invoke-static { v$register }, $REMEMBER->initialUpdate(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$register
                """,
            )
        }

        // 3. clearFilters: after both branches have made the fresh filter, before it is wrapped.
        FilterStoreClearFingerprint.method.apply {
            if (definingClass != store.type) throw PatchException("clearFilters is no longer the filter store's")
            val code = implementation!!.instructions
            val wrap = code.indexOfFirst { it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == filterUpdate }
            val init = code.drop(wrap).firstOrNull { (it as? ReferenceInstruction)?.reference.let { r -> r is MethodReference && r.definingClass == filterUpdate && r.name == "<init>" } }
            // The filter is the first argument after the new update itself.
            val filter = when (init) {
                is RegisterRangeInstruction -> init.startRegister + 1
                is FiveRegisterInstruction -> init.registerD
                else -> throw PatchException("clearFilters no longer wraps its fresh filter in an update")
            }
            if (wrap < 0 || filter > 15) throw PatchException("clearFilters' fresh filter register out of range")
            addInstructionsAtLabel(wrap, "invoke-static { v$filter }, $REMEMBER->restore(Ljava/lang/Object;)V")
        }
    }
}
