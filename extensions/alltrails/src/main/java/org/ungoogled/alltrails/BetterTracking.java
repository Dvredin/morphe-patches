package org.ungoogled.alltrails;

import android.content.Context;
import android.location.Location;
import android.location.altitude.AltitudeConverter;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * "Better tracking": records the way Gaia GPS 2026.8 does, instead of AllTrails'
 * default of batched Play services fixes, a 100 m accuracy cut-off and a Kalman
 * smoother that trusts its own prediction and leans on the trail being followed.
 *
 * - Source: AllTrails' own GPS-only vendor (LocationManager "gps", every fix, no
 *   batching; Gaia asks for 700 ms), and never the fused provider, not even when
 *   AllTrails judges the GPS unreliable.
 * - Fixes (Gaia's TrackRecordingService and outlier gate): dropped when worse
 *   than 65 m, older than 30 s or out of order; dropped when they imply more than
 *   300 m/s, more than 50 m/s² or a turn sharper than 1 g allows, until 3 in a
 *   row have been dropped.
 * - Points (Gaia's point buffer): a fix is recorded only once it is more than
 *   3 m from the last recorded point, so standing still adds no distance.
 * - Elevation: converted to sea level (Android 14+), recorded as a trimmed mean
 *   of the last 10 fixes, and counted as gain or loss once it moves 2 m.
 * - Stats: distance, moving time (speed above 0.12 m/s) and elevation are
 *   computed from those points and replace what AllTrails' smoother computed.
 *
 * AllTrails' smoother still runs beside it (calories, max speed); its points and
 * its distance, time and elevation are what gets replaced.
 */
public final class BetterTracking {
    private static final String PREFS = "ungoogled_experimental";
    private static final String KEY = "better_tracking";

    private static final float MAX_ACCURACY_M = 65f;
    private static final long MAX_AGE_NS = 30_000_000_000L;
    private static final double MAX_SPEED_MPS = 300;
    private static final double MAX_ACCELERATION_MPS2 = 50;
    private static final double GRAVITY_MPS2 = 9.8;
    private static final double TURN_CHECK_MIN_SPEED_MPS = 1;
    private static final int MAX_REJECTIONS_IN_A_ROW = 3;
    private static final double MIN_POINT_SPACING_M = 3;
    private static final double MOVING_SPEED_MPS = 0.12;
    private static final int ALTITUDE_WINDOW = 10;
    private static final double ELEVATION_STEP_M = 2;

    private static Context app;
    /** The switch, as the location vendor sees it: follows the setting at once. */
    private static volatile boolean enabled;
    /** The switch for the recorder: taken when a segment starts, so one never mixes methods. */
    private static boolean active;

    // The current segment.
    private static Location lastFix;
    private static double lastImpliedSpeed = -1;
    private static float lastTravelBearing = Float.NaN;
    private static int rejectionsInARow;
    private static Location lastPoint;
    private static final ArrayDeque<Double> altitudes = new ArrayDeque<>();
    private static double elevationAnchor = Double.NaN;
    private static double distanceM, movingS, gainM, lossM;
    private static double minAltitude = Double.NaN, maxAltitude = Double.NaN;
    private static Object segmentStats;
    /** An AltitudeConverter once created; typed Object so the class loads on Android versions before 14. */
    private static Object altitudeConverter;

    private BetterTracking() {}

    /** Rewritten to return true by the Better tracking patch; the Experimental screen offers the switch only then. */
    public static boolean patched() {
        return false;
    }

    private static boolean read(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true);
    }

    /** From Experimental.init, as the app starts. */
    static synchronized void init(Context context) {
        app = context;
        enabled = patched() && read(context);
        active = enabled;
    }

    public static boolean enabled(Context context) {
        return patched() && read(context);
    }

    public static void setEnabled(Context context, boolean on) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply();
        enabled = patched() && on;
    }

    // --- The location vendor (LocationVendorFactoryImpl) -------------------------------------------

    /** Its useGpsVendor argument: the GPS-only vendor while on. */
    public static boolean useGpsVendor(boolean stock) {
        return enabled || stock;
    }

    /** Its overrideLocationVendor argument, which flips the choice when a vendor looks unreliable: never while on. */
    public static boolean overrideVendor(boolean stock) {
        return !enabled && stock;
    }

    // --- The recorder (TrackRecorder) --------------------------------------------------------------

    /** TrackRecorder.initializeNewSegment: a segment starts at recording start and at every resume. */
    public static synchronized void newSegment() {
        active = enabled;
        lastFix = null;
        lastImpliedSpeed = -1;
        lastTravelBearing = Float.NaN;
        rejectionsInARow = 0;
        lastPoint = null;
        altitudes.clear();
        elevationAnchor = Double.NaN;
        distanceM = movingS = gainM = lossM = 0;
        minAltitude = maxAltitude = Double.NaN;
        segmentStats = null;
    }

    /**
     * Each new location, after AllTrails' smoother has handled it: the points to
     * record, in place of the smoother's. While off, the smoother's own.
     */
    public static synchronized List<Location> record(List<Location> smoothed, Location fix) {
        if (!active || fix == null) return smoothed;
        List<Location> points = new ArrayList<>(1);
        if (!accept(fix)) return points;

        Location point = new Location(fix);
        if (point.hasAltitude()) {
            double altitude = seaLevelAltitude(point);
            altitudes.addLast(altitude);
            if (altitudes.size() > ALTITUDE_WINDOW) altitudes.removeFirst();
            double smooth = trimmedMean();
            point.setAltitude(smooth);
            countElevation(smooth);
        }
        if (lastPoint != null && lastPoint.distanceTo(point) <= MIN_POINT_SPACING_M) return points;
        if (lastPoint != null) distanceM += lastPoint.distanceTo(point);
        lastPoint = point;
        points.add(point);
        return points;
    }

    /** Gaia's quality checks and outlier gate; an accepted fix also counts toward moving time. */
    private static boolean accept(Location fix) {
        if (!fix.hasAccuracy() || fix.getAccuracy() > MAX_ACCURACY_M) return false;
        if (SystemClock.elapsedRealtimeNanos() - fix.getElapsedRealtimeNanos() > MAX_AGE_NS) return false;
        if (lastFix == null) {
            lastFix = fix;
            return true;
        }
        if (fix.getTime() <= lastFix.getTime()) return false;

        double dt = (fix.getElapsedRealtimeNanos() - lastFix.getElapsedRealtimeNanos()) / 1e9;
        if (dt <= 0) dt = (fix.getTime() - lastFix.getTime()) / 1000.0;
        double distance = lastFix.distanceTo(fix);
        double speed = distance / dt;
        float bearing = lastFix.bearingTo(fix);
        if (dt >= 0.05) {
            boolean outlier = speed > MAX_SPEED_MPS
                    || (lastImpliedSpeed >= 0 && Math.abs(speed - lastImpliedSpeed) / dt > MAX_ACCELERATION_MPS2)
                    || (speed >= TURN_CHECK_MIN_SPEED_MPS && !Float.isNaN(lastTravelBearing)
                        && turn(lastTravelBearing, bearing) / dt > Math.toDegrees(GRAVITY_MPS2 / speed));
            if (outlier && ++rejectionsInARow <= MAX_REJECTIONS_IN_A_ROW) return false;
        }
        rejectionsInARow = 0;

        double moving = fix.hasSpeed() ? fix.getSpeed() : speed;
        if (moving > MOVING_SPEED_MPS) movingS += dt;
        lastImpliedSpeed = speed;
        if (distance >= 1) lastTravelBearing = bearing;
        lastFix = fix;
        return true;
    }

    private static double turn(float from, float to) {
        double d = Math.abs(to - from) % 360;
        return d > 180 ? 360 - d : d;
    }

    /** AltitudeConverter (Android 14+) turns the GPS's height above the WGS84 ellipsoid into height above sea level. */
    private static double seaLevelAltitude(Location point) {
        if (Build.VERSION.SDK_INT >= 34 && app != null && Looper.myLooper() != Looper.getMainLooper()) {
            try {
                if (altitudeConverter == null) altitudeConverter = new AltitudeConverter();
                ((AltitudeConverter) altitudeConverter).addMslAltitudeToLocation(app, point);
                if (point.hasMslAltitude()) return point.getMslAltitudeMeters();
            } catch (Exception | LinkageError ignored) {
                // no geoid data: keep the GPS altitude
            }
        }
        return point.getAltitude();
    }

    /** Mean of the window without its lowest and highest value. */
    private static double trimmedMean() {
        double[] values = new double[altitudes.size()];
        int i = 0;
        for (double v : altitudes) values[i++] = v;
        if (values.length < 3) return values[values.length - 1];
        Arrays.sort(values);
        double sum = 0;
        for (int j = 1; j < values.length - 1; j++) sum += values[j];
        return sum / (values.length - 2);
    }

    private static void countElevation(double altitude) {
        minAltitude = Double.isNaN(minAltitude) ? altitude : Math.min(minAltitude, altitude);
        maxAltitude = Double.isNaN(maxAltitude) ? altitude : Math.max(maxAltitude, altitude);
        if (Double.isNaN(elevationAnchor)) {
            elevationAnchor = altitude;
        } else if (altitude - elevationAnchor > ELEVATION_STEP_M) {
            gainM += altitude - elevationAnchor;
            elevationAnchor = altitude;
        } else if (elevationAnchor - altitude > ELEVATION_STEP_M) {
            lossM += elevationAnchor - altitude;
            elevationAnchor = altitude;
        }
    }

    /**
     * AllTrails' LineTimedGeoStats, whose getters and setters keep their names.
     * The patch grafts this interface onto it.
     */
    public interface Stats {
        double getDistanceTotal();
        void setDistanceTotal(double meters);
        long getTimeMoving();
        void setTimeMoving(long seconds);
        long getElevationGain();
        void setElevationGain(long meters);
        long getElevationGainLegacy();
        void setElevationGainLegacy(long meters);
        long getElevationLoss();
        void setElevationLoss(long meters);
        long getElevationMin();
        void setElevationMin(long meters);
        long getElevationMax();
        void setElevationMax(long meters);
        double getSpeedAverage();
        void setSpeedAverage(double metersPerSecond);
    }

    /**
     * TrackRecorder's stats save, right after each point: the segment's stats
     * become this segment's, and the track's totals move by the difference.
     */
    public static synchronized void applyStats(Object segment, Object track) {
        if (!active || !(segment instanceof Stats)) return;
        if (segmentStats == null) segmentStats = segment;
        if (segmentStats != segment) return; // a segment this run did not see start

        Stats s = (Stats) segment;
        double oldDistance = s.getDistanceTotal();
        long oldMoving = s.getTimeMoving();
        long oldGain = s.getElevationGain();
        long oldGainLegacy = s.getElevationGainLegacy();
        long oldLoss = s.getElevationLoss();

        long moving = Math.round(movingS);
        long gain = Math.round(gainM);
        long loss = Math.round(lossM);
        s.setDistanceTotal(distanceM);
        s.setTimeMoving(moving);
        s.setElevationGain(gain);
        s.setElevationGainLegacy(gain);
        s.setElevationLoss(loss);
        if (!Double.isNaN(minAltitude)) {
            s.setElevationMin(Math.round(minAltitude));
            s.setElevationMax(Math.round(maxAltitude));
        }
        s.setSpeedAverage(moving > 0 ? distanceM / moving : 0);

        if (!(track instanceof Stats) || track == segment) return;
        Stats t = (Stats) track;
        t.setDistanceTotal(Math.max(0, t.getDistanceTotal() - oldDistance + distanceM));
        t.setTimeMoving(Math.max(0, t.getTimeMoving() - oldMoving + moving));
        t.setElevationGain(Math.max(0, t.getElevationGain() - oldGain + gain));
        t.setElevationGainLegacy(Math.max(0, t.getElevationGainLegacy() - oldGainLegacy + gain));
        t.setElevationLoss(Math.max(0, t.getElevationLoss() - oldLoss + loss));
        if (!Double.isNaN(minAltitude)) {
            long min = t.getElevationMin();
            t.setElevationMin(min == 0 ? Math.round(minAltitude) : Math.min(min, Math.round(minAltitude)));
            t.setElevationMax(Math.max(t.getElevationMax(), Math.round(maxAltitude)));
        }
        long trackMoving = t.getTimeMoving();
        t.setSpeedAverage(trackMoving > 0 ? t.getDistanceTotal() / trackMoving : 0);
    }
}
