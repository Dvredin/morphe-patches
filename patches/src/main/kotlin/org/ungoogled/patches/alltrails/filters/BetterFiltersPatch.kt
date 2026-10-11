package org.ungoogled.patches.alltrails.filters

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import org.ungoogled.patches.alltrails.profile.experimentalScreenPatch
import org.ungoogled.patches.alltrails.profile.markPatched
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_ALLTRAILS
import org.ungoogled.patches.shared.addInstructionsAtLabel
import org.ungoogled.patches.shared.replaceBlockAtLabel

internal const val FILTER_VIEW = "Lcom/alltrails/alltrails/util/filter/FilterView;"
internal const val FILTER = "Lcom/alltrails/model/filter/Filter;"
internal const val SLIDER = "Lcom/google/android/material/slider/Slider;"
internal const val BETTER_FILTERS = "Lorg/ungoogled/alltrails/BetterFilters;"

/** Added to the rating field: the precise rating, or null while the slider is a stock one. */
private const val PRECISE = "preciseRating"

/** RatingFilterValue's constants in this version, in ordinal order; BetterFilters.STOPS mirrors their query values. */
private val RATING_STOPS = listOf("Any", "OverThreePointFive", "OverFour", "OverFourPointFive")

/** FilterView's rating section builder: inflates item_filter_rating, sets the slider up and adds it. */
private object RatingSectionFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    strings = listOf("null cannot be cast to non-null type com.alltrails.alltrails.util.filter.availablefilters.RatingFilterField"),
    filters = listOf(methodCall(definingClass = SLIDER, name = "addOnChangeListener")),
    custom = { _, classDef -> classDef.type == FILTER_VIEW },
)

/**
 * The rating slider's change listener body, `(field, binding, FilterView,
 * slider, value, fromUser)`: stores RatingFilterValue.values()[(int) slider
 * value] on the field, writes the section subtitle, then tells the view model.
 */
private object RatingChangedFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "L", FILTER_VIEW, SLIDER, "F", "Z"),
    filters = listOf(
        methodCall(definingClass = SLIDER, name = "getValue"),
        opcode(Opcode.MOVE_RESULT, MatchAfterImmediately()),
        opcode(Opcode.FLOAT_TO_INT, MatchAfterImmediately()),
        opcode(Opcode.AGET_OBJECT, MatchAfterImmediately()),
        opcode(Opcode.INVOKE_VIRTUAL, MatchAfterImmediately()),
    ),
)

/** RatingAvailableFilter's field-to-filter step: the enum's query value (null for Any) into Filter.copy. */
private object RatingToFilterFingerprint : Fingerprint(
    returnType = FILTER,
    parameters = listOf(FILTER, "L"),
    filters = listOf(
        methodCall(name = "getQueryValue", returnType = "D"),
        methodCall(definingClass = "Ljava/lang/Double;", name = "valueOf"),
        methodCall(definingClass = FILTER, name = "copy\$default", opcode = Opcode.INVOKE_STATIC_RANGE),
    ),
)

/** The slider's rating_value binding adapter: slider.setValue(ratingFilterValue.ordinal()). */
private object RatingBindingFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(SLIDER, "L"),
    strings = listOf("ratingFilterValue"),
    filters = listOf(methodCall(definingClass = SLIDER, name = "setValue")),
)

/** Every register [this] reads or writes (the second half of a wide write included). */
private fun Instruction.registers(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> listOfNotNull(
        (this as? OneRegisterInstruction)?.registerA,
        (this as? OneRegisterInstruction)?.registerA?.takeIf { opcode.setsWideRegister() }?.plus(1),
        (this as? TwoRegisterInstruction)?.registerB,
        (this as? ThreeRegisterInstruction)?.registerC,
    )
}

private val Instruction.methodReference get() = (this as? ReferenceInstruction)?.reference as? MethodReference

/**
 * The rating filter is a four-value enum, RatingFilterValue (Any, 3.5, 4, 4.5),
 * and the slider has one step per value (0..3). The search API itself takes any
 * number (minRating), so the precise rating only needs a way through the app:
 *
 * 1. the rating field gets a `preciseRating` Double;
 * 2. the slider's change listener stores it there (and the enum value it falls
 *    in, so everything that reads the enum stays consistent), and writes
 *    "Over 4.2" into the section subtitle;
 * 3. the field-to-filter step prefers it over the enum's query value;
 * 4. FilterView's rating section stretches the slider to 0..5 in 0.1 steps and
 *    moves it to the filter's current rating;
 * 5. the slider's data binding, which re-applies the enum's ordinal a frame
 *    later, leaves a precise slider alone unless the enum disagrees with it;
 * 6. RatingFilterValue.fromQueryValue maps a rating to the stop it falls in
 *    (4.2 -> 4) instead of anything-but-3.5/4/4.5 -> Any.
 *
 * Which of these act is decided at runtime by the slider (see BetterFilters),
 * so with the switch off the screen is stock apart from (6).
 *
 * The other sliders are in RangeSliders.kt.
 */
@Suppress("unused")
val betterFiltersPatch = bytecodePatch(
    name = "Better filters",
    description = "Adds a Better filters switch to the Experimental screen. With it on, the trail filter's sliders move " +
        "in finer steps over the same ranges: Rating in 0.1 stars (instead of Any, 3.5, 4 and 4.5), Length in 0.1 mi, " +
        "Elevation gain in 10 ft, Highest point in 100 ft and Distance away in 1 mi (metric: 0.1 km, 5 m, 50 m, 1 km).",
    default = true,
) {
    compatibleWith(COMPATIBILITY_ALLTRAILS)
    dependsOn(experimentalScreenPatch)

    execute {
        markPatched(BETTER_FILTERS)

        // The rating field and enum types, from the change listener's cast and values() call.
        val changed = RatingChangedFingerprint.method
        val changedCode = changed.implementation!!.instructions
        val field = changedCode.first { it.opcode == Opcode.CHECK_CAST }
            .let { ((it as ReferenceInstruction).reference as TypeReference).type }
        val enum = changedCode.firstNotNullOfOrNull { insn ->
            insn.methodReference?.takeIf { insn.opcode == Opcode.INVOKE_STATIC && it.name == "values" }?.definingClass
        } ?: throw PatchException("rating listener no longer calls RatingFilterValue.values()")
        val enumClass = classDefBy(enum)
        val constants = enumClass.fields.filter { AccessFlags.ENUM.isSet(it.accessFlags) }.map { it.name }
        if (constants.sorted() != RATING_STOPS.sorted()) throw PatchException("RatingFilterValue changed: $constants")

        // 1. The field.
        mutableClassDefBy(field).fields.add(
            ImmutableField(field, PRECISE, "Ljava/lang/Double;", AccessFlags.PUBLIC.value, null, null, null).toMutable(),
        )

        // 2. The change listener. Bottom-up, so earlier indices stay put.
        changed.apply {
            val matches = RatingChangedFingerprint.instructionMatches
            val floatToInt = matches[2].index
            val aget = changedCode[matches[3].index] as ThreeRegisterInstruction
            val setterIndex = matches[4].index
            val setter = changedCode[setterIndex] as FiveRegisterInstruction
            if (setter.methodReference?.definingClass != field) throw PatchException("rating listener no longer sets the field")
            // Static with six one-register parameters: p5 (fromUser) is the last register, and unused.
            val fromUser = implementation!!.registerCount - 1
            if (changedCode.any { fromUser in it.registers() }) throw PatchException("rating listener now reads fromUser")

            val subtitle = (setterIndex until changedCode.size).firstOrNull { i ->
                changedCode[i].opcode == Opcode.INVOKE_STATIC && changedCode[i].methodReference?.let {
                    it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/widget/TextView;", enum) && it.returnType == "V"
                } == true
            } ?: throw PatchException("rating listener no longer writes the section subtitle")
            val subtitleView = (changedCode[subtitle] as FiveRegisterInstruction).registerC
            addInstructionsAtLabel(
                subtitle + 1,
                "invoke-static { v$subtitleView, p5 }, $BETTER_FILTERS->ratingSubtitle(Landroid/widget/TextView;$SLIDER)V",
            )

            // After the setter the enum register is free: the next instruction overwrites it.
            val scratch = aget.registerA
            val next = changedCode[setterIndex + 1]
            if (next.opcode != Opcode.IGET_OBJECT || (next as OneRegisterInstruction).registerA != scratch) {
                throw PatchException("rating listener changed shape after the setter")
            }
            addInstructionsAtLabel(
                setterIndex + 1,
                """
                    invoke-static { p5 }, $BETTER_FILTERS->preciseRating($SLIDER)Ljava/lang/Double;
                    move-result-object v$scratch
                    iput-object v$scratch, v${setter.registerC}, $field->$PRECISE:Ljava/lang/Double;
                """,
            )

            // (int) value -> the enum index BetterFilters picks; the aget indexes with it.
            val value = (changedCode[floatToInt] as TwoRegisterInstruction).registerA
            if (aget.registerC != value) throw PatchException("rating listener no longer indexes values() with the slider value")
            replaceInstruction(floatToInt, "invoke-static { p5 }, $BETTER_FILTERS->ratingIndex($SLIDER)I")
            addInstructions(floatToInt + 1, "move-result v$value")

            // Keep the slider: its own parameter register is reused for the field right away.
            addInstructionsAtLabel(0, "move-object p5, p3")
        }

        // 3. Field to filter: the precise rating goes into Filter.copy's minimumRating argument.
        RatingToFilterFingerprint.method.apply {
            val code = implementation!!.instructions
            if (code.first { it.opcode == Opcode.CHECK_CAST }.let { ((it as ReferenceInstruction).reference as TypeReference).type } != field) {
                throw PatchException("rating filter step no longer casts to the rating field")
            }
            val copyIndex = RatingToFilterFingerprint.instructionMatches.last().index
            val copy = code[copyIndex] as RegisterRangeInstruction
            var argument = copy.startRegister
            for (type in copy.methodReference!!.parameterTypes.map(CharSequence::toString)) {
                if (type == "Ljava/lang/Double;") break
                argument += if (type == "J" || type == "D") 2 else 1
            }
            // v0 and v1 lie below the call's range and are both overwritten straight after it.
            val after = code.subList(copyIndex + 1, copyIndex + 3).map { (it as OneRegisterInstruction).registerA }
            if (copy.startRegister < 2 || after.sorted() != listOf(0, 1) || argument > 15) {
                throw PatchException("rating filter step changed shape around Filter.copy")
            }
            addInstructionsAtLabel(
                copyIndex,
                """
                    move-object/from16 v0, p2
                    check-cast v0, $field
                    iget-object v1, v0, $field->$PRECISE:Ljava/lang/Double;
                    invoke-static { v1, v$argument }, $BETTER_FILTERS->minRating(Ljava/lang/Double;Ljava/lang/Double;)Ljava/lang/Double;
                    move-result-object v$argument
                """,
            )
        }

        // 4. The section builder, last thing before it returns.
        RatingSectionFingerprint.method.apply {
            val code = implementation!!.instructions
            val ret = code.indexOfLast { it.opcode == Opcode.RETURN_VOID }
            val getAvailable = classDefBy(FILTER_VIEW).methods.single { it.name == "getAvailableFilter" && it.parameterTypes.isEmpty() }
            val available = getAvailable.returnType
            val getFilter = classDefBy(available).methods.single { it.parameterTypes.isEmpty() && it.returnType == FILTER }
            addInstructionsAtLabel(
                ret,
                """
                    invoke-virtual { p0 }, $FILTER_VIEW->getAvailableFilter()$available
                    move-result-object v0
                    invoke-virtual { v0 }, $available->${getFilter.name}()$FILTER
                    move-result-object v0
                    invoke-virtual { v0 }, $FILTER->getMinimumRating()Ljava/lang/Double;
                    move-result-object v0
                    invoke-static { p0, v0 }, $BETTER_FILTERS->setupRatingSlider(Landroid/view/View;Ljava/lang/Double;)V
                """,
            )
        }

        // 5. The binding adapter's setValue.
        RatingBindingFingerprint.let { fp ->
            val index = fp.instructionMatches.last().index
            val call = fp.method.implementation!!.instructions[index] as FiveRegisterInstruction
            fp.method.replaceInstruction(
                index,
                "invoke-static { v${call.registerC}, v${call.registerD} }, $BETTER_FILTERS->bindRatingValue(${SLIDER}F)V",
            )
        }

        // 6. RatingFilterValue.Companion's query value -> enum.
        val companion = enumClass.fields.singleOrNull { it.name == "Companion" }?.type
            ?: throw PatchException("RatingFilterValue has no Companion")
        mutableClassDefBy(companion).methods.single {
            it.parameterTypes.map(CharSequence::toString) == listOf("D") && it.returnType == enum
        }.apply {
            // An instance method taking a double: this + two registers; the rest are locals.
            if (implementation!!.registerCount - 3 < 2) throw PatchException("fromQueryValue has no two free locals")
            replaceBlockAtLabel(
                0,
                """
                    invoke-static { p1, p2 }, $BETTER_FILTERS->ratingStop(D)I
                    move-result v0
                    invoke-static { }, $enum->values()[$enum
                    move-result-object v1
                    aget-object v0, v1, v0
                    return-object v0
                """,
            )
        }

        // Length, Elevation gain, Highest point and Distance away.
        preciseRangeAndDistanceSliders()
    }
}
