package org.ungoogled.patches.alltrails.tracking

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import org.ungoogled.patches.alltrails.profile.experimentalScreenPatch
import org.ungoogled.patches.alltrails.profile.markPatched
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_ALLTRAILS
import org.ungoogled.patches.shared.addInstructionsAtLabel

private const val BETTER_TRACKING = "Lorg/ungoogled/alltrails/BetterTracking;"
private const val STATS = "Lorg/ungoogled/alltrails/BetterTracking\$Stats;"
private const val TRACK_RECORDER = "Lcom/alltrails/alltrails/track/recorder/TrackRecorder;"
private const val SMOOTHER = "Lcom/alltrails/alltrails/track/util/MapSmootherWorker;"

/** LocationVendorFactoryImpl.create(context, emitters, filter, overrideLocationVendor, useGpsVendor, …). */
private object VendorFactoryCreateFingerprint : Fingerprint(
    strings = listOf("Create LocationManagerLocationVendor", "Create FusedLocationVendor"),
)

/** TrackRecorder's new-location handler: the smoother's points for each fix. */
private object RecorderAddLocationFingerprint : Fingerprint(
    filters = listOf(
        methodCall(definingClass = SMOOTHER, name = "addLocation"),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
    ),
    custom = { _, classDef -> classDef.type == TRACK_RECORDER },
)

/** TrackRecorder.initializeNewSegment. */
private object RecorderNewSegmentFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("initializeNewSegment - No track present"),
    custom = { _, classDef -> classDef.type == TRACK_RECORDER },
)

/** The getters and setters BetterTracking.Stats declares, which LineTimedGeoStats and its base keep by name. */
private val STATS_METHODS = listOf(
    "getDistanceTotal()D", "setDistanceTotal(D)V",
    "getTimeMoving()J", "setTimeMoving(J)V",
    "getElevationGain()J", "setElevationGain(J)V",
    "getElevationGainLegacy()J", "setElevationGainLegacy(J)V",
    "getElevationLoss()J", "setElevationLoss(J)V",
    "getElevationMin()J", "setElevationMin(J)V",
    "getElevationMax()J", "setElevationMax(J)V",
    "getSpeedAverage()D", "setSpeedAverage(D)V",
)

private val Method.signature get() = "$name(${parameterTypes.joinToString("")})$returnType"

/**
 * Records the way Gaia GPS does (see BetterTracking in the extension):
 *
 * 1. LocationVendorFactoryImpl builds AllTrails' own GPS-only vendor
 *    (LocationManager "gps", every fix, no batching) and never flips back to
 *    the fused one when AllTrails judges the GPS unreliable: its useGpsVendor and
 *    overrideLocationVendor arguments go through the extension, in both create()
 *    and the "is the current vendor still right" check.
 * 2. TrackRecorder, for each fix: the points to record come from the extension
 *    (Gaia's filters, outlier gate, 3 m spacing, sea-level and trimmed-mean
 *    altitude) instead of AllTrails' Kalman or legacy smoother, which keeps
 *    running beside it for calories and max speed.
 * 3. TrackRecorder.initializeNewSegment resets the extension's per-segment state.
 * 4. TrackRecorder's stats save (segment + track LineTimedGeoStats, right after
 *    each point) takes the extension's distance, moving time and elevation.
 *
 * The switch is read per segment, so a recording never mixes the two methods.
 */
@Suppress("unused")
val betterTrackingPatch = bytecodePatch(
    name = "Better tracking",
    description = "Adds a Better tracking switch to the Experimental screen. With it on, recordings work the way Gaia " +
        "GPS records: the phone's GPS directly with every fix delivered live (no Play services batching), fixes worse " +
        "than 65 m or physically impossible dropped, points kept 3 m apart, no trail-biased smoothing, and elevation " +
        "converted to sea level and counted in 2 m steps.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_ALLTRAILS)
    dependsOn(experimentalScreenPatch)

    execute {
        markPatched(BETTER_TRACKING)

        // 1. The location vendor.
        val create = VendorFactoryCreateFingerprint.method
        val factoryClass = mutableClassDefBy(create.definingClass)
        val booleans = create.parameterTypes.withIndex().filter { it.value.toString() == "Z" }.map { it.index }
        if (booleans.size != 2) throw PatchException("vendor factory create() no longer takes two flags: ${create.parameterTypes}")
        // Parameter registers: p0 is `this`; none of these parameters is wide.
        val (overrideParam, useGpsParam) = booleans.map { it + 1 }
        create.addInstructionsAtLabel(
            0,
            """
                invoke-static/range { p$overrideParam .. p$overrideParam }, $BETTER_TRACKING->overrideVendor(Z)Z
                move-result p$overrideParam
                invoke-static/range { p$useGpsParam .. p$useGpsParam }, $BETTER_TRACKING->useGpsVendor(Z)Z
                move-result p$useGpsParam
            """,
        )
        val stillRight = factoryClass.methods.singleOrNull {
            it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString).let { p -> p.size == 3 && p[1] == "Z" && p[2] == "Z" }
        } ?: throw PatchException("vendor factory no longer checks the current vendor")
        stillRight.addInstructionsAtLabel(
            0,
            """
                invoke-static { p2 }, $BETTER_TRACKING->overrideVendor(Z)Z
                move-result p2
                invoke-static { p3 }, $BETTER_TRACKING->useGpsVendor(Z)Z
                move-result p3
            """,
        )

        // 2. The recorder's points: after `list = smoother.addLocation(location)`.
        RecorderAddLocationFingerprint.let { fp ->
            val call = fp.method.implementation!!.instructions[fp.instructionMatches[0].index] as FiveRegisterInstruction
            val result = fp.instructionMatches[1].index
            val list = (fp.method.implementation!!.instructions[result] as OneRegisterInstruction).registerA
            if (list > 15 || call.registerD > 15) throw PatchException("smoother call registers out of range")
            fp.method.addInstructionsAtLabel(
                result + 1,
                """
                    invoke-static { v$list, v${call.registerD} }, $BETTER_TRACKING->record(Ljava/util/List;Landroid/location/Location;)Ljava/util/List;
                    move-result-object v$list
                """,
            )
        }

        // 3. A new segment.
        RecorderNewSegmentFingerprint.method.addInstructionsAtLabel(0, "invoke-static { }, $BETTER_TRACKING->newSegment()V")

        // 4. The stats: LineTimedGeoStats (the type whose setters the smoothers call) and the recorder's save.
        val recorder = mutableClassDefBy(TRACK_RECORDER)
        val statsType = classDefBy(SMOOTHER).methods.single { it.name == "updateTrackData" }.parameterTypes.first().toString()
            .let { track -> classDefBy(track).methods.single { it.name == "getLineTimedGeoStats" && it.parameterTypes.isEmpty() }.returnType }
        val statsClass = classDefBy(statsType)
        val available = (statsClass.methods + (statsClass.superclass?.let { classDefByOrNull(it)?.methods } ?: emptyList()))
            .map { it.signature }.toSet()
        val missing = STATS_METHODS.filterNot { it in available }
        if (missing.isNotEmpty()) throw PatchException("LineTimedGeoStats lost methods BetterTracking.Stats declares: $missing")
        mutableClassDefBy(statsType).interfaces.add(STATS)

        recorder.methods.single {
            it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf(statsType, statsType)
        }.addInstructionsAtLabel(0, "invoke-static { p1, p2 }, $BETTER_TRACKING->applyStats(Ljava/lang/Object;Ljava/lang/Object;)V")
    }
}
