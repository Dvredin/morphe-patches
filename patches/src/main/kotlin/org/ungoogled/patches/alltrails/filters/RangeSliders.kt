package org.ungoogled.patches.alltrails.filters

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.ungoogled.patches.shared.addInstructionsAtLabel
import org.ungoogled.patches.shared.replaceBlockAtLabel

private const val STEPS = "Lorg/ungoogled/alltrails/BetterFilters\$Steps;"
private const val METRIC_FILTER = "Lorg/ungoogled/alltrails/BetterFilters\$MetricFilter;"

/** FilterView's Length / Elevation gain / Highest point section builder. */
private object RangeSectionFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    strings = listOf("null cannot be cast to non-null type com.alltrails.alltrails.util.filter.RangeSliderAvailableFilter"),
    custom = { _, classDef -> classDef.type == FILTER_VIEW },
)

/** DistanceAwayFilterField's constructor. */
private object DistanceFieldFingerprint : Fingerprint(
    name = "<init>",
    strings = listOf("DistanceAwayFilterField"),
)

/**
 * The range filters by id, the extension's method prefix for each, and their
 * stock top positions in this version (50 mi / 80 km, 5,000 ft / 1,500 m and
 * 15,000 ft / 4,500 m through their step tables). BetterFilters' precise top
 * positions are worked out from those same caps.
 */
private val RANGE_FILTERS = listOf(
    Triple("length", "length", setOf(34, 45)),
    Triple("elevation_gain", "elevation", setOf(14)),
    Triple("highest_point", "highestPoint", setOf(30, 15)),
)

private val Instruction.methodReference get() = (this as? ReferenceInstruction)?.reference as? MethodReference

private val Method.parameters get() = parameterTypes.map(CharSequence::toString)

private val MethodReference.parameters get() = parameterTypes.map(CharSequence::toString)

private fun MutableMethod.code() = implementation!!.instructions

/** The id an available filter passes to its base constructor, e.g. "length". */
private fun ClassDef.filterId(): String? = methods.filter { it.name == "<init>" }
    .flatMap { it.implementation?.instructions ?: emptyList() }
    .firstNotNullOfOrNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }

/** Indices of every [opcode] in the method, last first, so inserting at one leaves the rest valid. */
private fun MutableMethod.indicesOf(opcode: Opcode) = code().indices.filter { code()[it].opcode == opcode }.reversed()

/** The constant each `return vX` returns, in code order. */
private fun MutableMethod.returnedConstants(): List<Int> = code().indices.filter { code()[it].opcode == Opcode.RETURN }.map { ret ->
    val register = (code()[ret] as OneRegisterInstruction).registerA
    val load = code().subList(0, ret).last { (it as? OneRegisterInstruction)?.registerA == register }
    (load as? NarrowLiteralInstruction)?.narrowLiteral ?: throw PatchException("$name() no longer returns constants")
}

/**
 * The range sliders (Length, Elevation gain, Highest point) and Distance away, in
 * finer steps while Better filters is on. See BetterFilters for the scheme; the
 * hooks here only hand AllTrails' own values to it and take its answer back:
 *
 * - the switch is read as the app starts (Experimental.init, from the
 *   Experimental screen patch): the step tables are built by the view model,
 *   before any view or Context is at hand;
 * - each range filter's step table getter and top position go through it, and
 *   Length's meters <-> display unit conversions too (it counts tenths);
 * - the Length labels format those tenths;
 * - Distance away's two transformers and its top position go through it, and
 *   its slider loses the per-stop ticks.
 *
 * The shared position <-> meters code, the "N+" top position and the labels'
 * wording are AllTrails' own, untouched.
 */
internal fun BytecodePatchContext.preciseRangeAndDistanceSliders() {
    // The range base class (RangeSliderAvailableFilter), from the section builder's cast, and its members.
    val base = RangeSectionFingerprint.method.code().first { it.opcode == Opcode.CHECK_CAST }
        .let { ((it as ReferenceInstruction).reference as TypeReference).type }
    val baseClass = classDefBy(base)
    // position -> meters is the one (I)D method: clamp to [L(), H()], then C(M().b(position)).
    val toMeters = baseClass.methods.single { it.parameters == listOf("I") && it.returnType == "D" }
    val toMetersCalls = toMeters.implementation!!.instructions.mapNotNull { it.methodReference }.filter { it.definingClass == base }
    val topName = toMetersCalls.first { it.returnType == "I" && it.parameterTypes.isEmpty() }.name
    val stepsCall = toMetersCalls.single { it.parameterTypes.isEmpty() && it.returnType.startsWith("L") }
    val stepTable = stepsCall.returnType
    val fromUnitsName = toMetersCalls.single { it.parameters == listOf("D") && it.returnType == "D" }.name
    // meters -> position is the one (D)I method, and starts with A(meters) -> display units.
    val toUnitsName = baseClass.methods.single { it.parameters == listOf("D") && it.returnType == "I" }
        .implementation!!.instructions.mapNotNull { it.methodReference }
        .first { it.definingClass == base && it.returnType == "D" }.name
    if (toUnitsName == fromUnitsName) throw PatchException("range filter unit conversions are no longer told apart")
    val stepTableMethods = classDefBy(stepTable).methods.map { "${it.name}(${it.parameters.joinToString("")})${it.returnType}" }.sorted()
    if (stepTableMethods != listOf("a(I)I", "b(I)I")) throw PatchException("the slider step table interface changed: $stepTableMethods")

    // isMetric: the getter of the base class' one final boolean field. BetterFilters.MetricFilter declares O().
    val isMetricField = baseClass.fields.single { AccessFlags.FINAL.isSet(it.accessFlags) && it.type == "Z" }.name
    val isMetricName = baseClass.methods.single { method ->
        method.parameterTypes.isEmpty() && method.returnType == "Z" && method.implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? FieldReference)?.let { f -> f.definingClass == base && f.name == isMetricField } == true
        } == true
    }.name
    if (isMetricName != "O") throw PatchException("the range filters' isMetric getter is $isMetricName(); BetterFilters.MetricFilter declares O()")

    // The extension's linear step table becomes one of AllTrails'; the base class becomes a MetricFilter.
    mutableClassDefBy(STEPS).interfaces.add(stepTable)
    mutableClassDefBy(base).interfaces.add(METRIC_FILTER)

    val subclasses = mutableListOf<ClassDef>()
    classDefForEach { if (it.superclass == base) subclasses += it }
    val byId = subclasses.associateBy { it.filterId() }
    if (byId.keys != RANGE_FILTERS.map { it.first }.toSet()) throw PatchException("range filters changed: ${byId.keys}")

    for ((id, prefix, stockTop) in RANGE_FILTERS) {
        val subclass = mutableClassDefBy(byId.getValue(id))

        // The step table getter (for Length, the bridge to its covariant one).
        subclass.methods.single { it.name == stepsCall.name && it.parameterTypes.isEmpty() && it.returnType == stepTable }.apply {
            for (ret in indicesOf(Opcode.RETURN_OBJECT)) {
                val register = (code()[ret] as OneRegisterInstruction).registerA
                addInstructionsAtLabel(
                    ret,
                    """
                        invoke-static { p0, v$register }, $BETTER_FILTERS->${prefix}Steps(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object v$register
                        check-cast v$register, $stepTable
                    """,
                )
            }
        }

        // The top position.
        subclass.methods.single { it.name == topName && it.parameterTypes.isEmpty() && it.returnType == "I" }.apply {
            val stock = returnedConstants().toSet()
            if (stock != stockTop) throw PatchException("$id top positions changed: $stock")
            for (ret in indicesOf(Opcode.RETURN)) {
                val register = (code()[ret] as OneRegisterInstruction).registerA
                addInstructionsAtLabel(
                    ret,
                    """
                        invoke-static { p0, v$register }, $BETTER_FILTERS->${prefix}MaxPosition(Ljava/lang/Object;I)I
                        move-result v$register
                    """,
                )
            }
        }
    }

    // Length counts in tenths while precise: its conversions scale, and its labels format tenths.
    val length = mutableClassDefBy(byId.getValue("length"))
    length.methods.single { it.name == toUnitsName && it.parameters == listOf("D") }.apply {
        for (ret in indicesOf(Opcode.RETURN_WIDE)) {
            val register = (code()[ret] as OneRegisterInstruction).registerA
            addInstructionsAtLabel(
                ret,
                """
                    invoke-static { v$register, v${register + 1} }, $BETTER_FILTERS->lengthToUnits(D)D
                    move-result-wide v$register
                """,
            )
        }
    }
    length.methods.single { it.name == fromUnitsName && it.parameters == listOf("D") }.addInstructionsAtLabel(
        0,
        """
            invoke-static { p1, p2 }, $BETTER_FILTERS->lengthFromUnits(D)D
            move-result-wide p1
        """,
    )

    val formatter = length.methods.single { it.parameters == listOf("Landroid/content/res/Resources;") }
        .implementation!!.instructions.first { it.opcode == Opcode.NEW_INSTANCE }
        .let { ((it as ReferenceInstruction).reference as TypeReference).type }
    val formatterMethods = mutableClassDefBy(formatter).methods
    // The number format: NumberFormat.getIntegerInstance().format(value).
    formatterMethods.single { method ->
        method.parameters == listOf("I") && method.returnType == "Ljava/lang/String;" &&
            method.implementation!!.instructions.first().methodReference?.name == "getIntegerInstance"
    }.replaceBlockAtLabel(
        0,
        """
            invoke-static { p1 }, $BETTER_FILTERS->formatLength(I)Ljava/lang/String;
            move-result-object p1
            return-object p1
        """,
    )
    // The "min-max" summary appends the min as a bare number.
    formatterMethods.single { it.parameters == listOf(base) && it.returnType == "Ljava/lang/String;" }.apply {
        val append = code().indices.single { i ->
            code()[i].methodReference?.let {
                it.definingClass == "Ljava/lang/StringBuilder;" && it.name == "append" && it.parameters == listOf("I")
            } == true
        }
        val call = code()[append] as FiveRegisterInstruction
        replaceInstruction(
            append,
            "invoke-static { v${call.registerC}, v${call.registerD} }, $BETTER_FILTERS->appendLength(Ljava/lang/StringBuilder;I)Ljava/lang/StringBuilder;",
        )
    }

    // Distance away: the field, and FilterView's section builder for it.
    val field = DistanceFieldFingerprint.method.definingClass
    val section = mutableClassDefBy(FILTER_VIEW).methods.single { it.parameters == listOf(field) && it.returnType == "V" }
    val setValueTo = section.code().indexOfFirst { it.methodReference?.let { r -> r.definingClass == SLIDER && r.name == "setValueTo" } == true }
    if (setValueTo < 0) throw PatchException("distance slider no longer sets its range")
    val topPosition = section.code().subList(0, setValueTo).last { it.methodReference?.definingClass == field }.methodReference!!
    if (topPosition.returnType != "I" || topPosition.parameterTypes.isNotEmpty()) throw PatchException("distance slider no longer ends at the field's top position")

    // The top (Any) position: `isMetric ? 7 : 6`.
    mutableClassDefBy(field).methods.single { it.name == topPosition.name && it.parameterTypes.isEmpty() && it.returnType == "I" }.apply {
        val stock = returnedConstants()
        if (stock != listOf(7, 6)) throw PatchException("distance top positions changed: $stock")
        // indicesOf lists the last return (imperial, 6) first.
        for ((ret, helper) in indicesOf(Opcode.RETURN).zip(listOf("distanceMaxMiles", "distanceMaxKm"))) {
            val register = (code()[ret] as OneRegisterInstruction).registerA
            addInstructionsAtLabel(
                ret,
                """
                    invoke-static { v$register }, $BETTER_FILTERS->$helper(I)I
                    move-result v$register
                """,
            )
        }
    }

    // The two transformers the field converts through; the imperial one's first stop is 5 mi.
    val transformers = classDefBy(field).methods.flatMap { it.implementation?.instructions ?: emptyList() }
        .filter { it.opcode == Opcode.SGET_OBJECT }
        .map { ((it as ReferenceInstruction).reference as FieldReference).type }
        .distinct()
        .filter { type -> classDefByOrNull(type)?.interfaces?.contains(stepTable) == true }
    if (transformers.size != 2) throw PatchException("distance transformers changed: $transformers")
    for (type in transformers) {
        val methods = mutableClassDefBy(type).methods
        val toPosition = methods.single { it.name == "a" && it.parameters == listOf("I") && it.returnType == "I" }
        val toUnits = methods.single { it.name == "b" && it.parameters == listOf("I") && it.returnType == "I" }
        val imperial = toPosition.code().any { (it as? NarrowLiteralInstruction)?.narrowLiteral == 5 }
        for ((method, helper) in listOf(
            toPosition to if (imperial) "distancePositionMiles" else "distancePositionKm",
            toUnits to "distanceUnits",
        )) {
            method.apply {
                if (implementation!!.registerCount - 2 < 1) throw PatchException("$type.$name has no free local")
                // AllTrails' own mapping runs unless the extension answers (>= 0).
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static { p1 }, $BETTER_FILTERS->$helper(I)I
                        move-result v0
                        if-ltz v0, :stock
                        return v0
                    """,
                    ExternalLabel("stock", implementation!!.instructions.first()),
                )
            }
        }
    }

    // No per-stop ticks on the distance slider while precise.
    section.apply {
        val listen = code().indexOfFirst { it.methodReference?.let { r -> r.definingClass == SLIDER && r.name == "addOnChangeListener" } == true }
        if (listen < 0) throw PatchException("distance slider no longer gets a change listener")
        val slider = (code()[listen] as FiveRegisterInstruction).registerC
        addInstructionsAtLabel(listen + 1, "invoke-static { v$slider }, $BETTER_FILTERS->styleDistanceSlider($SLIDER)V")
    }
}
