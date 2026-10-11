package org.ungoogled.alltrails;

import android.content.Context;
import android.os.SystemClock;

/**
 * "2D / 3D compass": in navigate mode (once a navigation or recording has
 * started, paused included) the compass switches the map between 3D and 2D,
 * the way Google Maps' navigation compass does.
 *
 * - 3D: AllTrails' own view, following you heading-up and tilted 45°. It now
 *   stays tilted while a recording is paused; AllTrails flattens it there.
 * - 2D: flat and north-up, following you.
 *
 * Every tap on the navigator's compass switches and brings the map back to you,
 * from any state: following, panned away, paused, or a recording AllTrails
 * reopened paused. (AllTrails' own tap only flipped heading-up/north-up while the
 * map was following you and a recording was running; panned or paused it only
 * turned the map north. And its navigation camera, "tilt and perspective", only
 * comes on at Start and Resume, so a recording reopened paused still had the
 * plain map camera; the tap now turns it on.) The compass shows the mode:
 * AllTrails' needle (the N with an arrow above and below) in 3D, a single north
 * arrow in 2D. The mode is kept for the next navigation, and for re-centring,
 * until the next tap.
 *
 * The map's controls tell the compass it is in 2D by adding {@link #TWO_D_TURNS}
 * to its rotation (360 - bearing, so 0..360): two full turns, which draw the
 * same as none, in a range a heading never gives.
 */
public final class Compass {
    private static final String PREFS = "ungoogled_experimental";
    private static final String KEY = "compass_2d_3d";
    private static final String KEY_NORTH_UP = "compass_north_up";
    private static final String NORTH_ICON = "morphe_compass_north";

    static final int TWO_D_TURNS = 720;
    /** 3D's tilt, AllTrails' own while recording. */
    private static final double TILT = 45.0;
    /** CameraConfiguration's reducer type while navigating (the enum constant's name). */
    private static final String NAVIGATION_CAMERA = "TILT_AND_PERSPECTIVE";
    /** How long after a tap on the compass button the camera takes it as the user's. */
    private static final long TAP_WINDOW_MS = 1000;

    private static Context app;
    private static volatile boolean enabled;
    private static volatile boolean northUp;
    private static volatile long tappedAt;
    private static int northIcon;

    private Compass() {}

    /** Rewritten to return true by the 2D / 3D compass patch; the Experimental screen offers the switch only then. */
    public static boolean patched() {
        return false;
    }

    /** From Experimental.init, as the app starts. */
    static void init(Context context) {
        app = context;
        enabled = patched() && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true);
        northUp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_NORTH_UP, false);
        northIcon = context.getResources().getIdentifier(NORTH_ICON, "drawable", context.getPackageName());
    }

    public static boolean enabled(Context context) {
        return patched() && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true);
    }

    public static void setEnabled(Context context, boolean on) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply();
        enabled = patched() && on;
    }

    private static void remember(boolean value) {
        northUp = value;
        if (app != null) app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_NORTH_UP, value).apply();
    }

    // --- The navigator's compass button (NavigatorTopControls) ---------------------------------------

    /** A tap on the navigator's compass, right before it calls the map's compass handler. */
    public static void navigatorTapped() {
        tappedAt = SystemClock.uptimeMillis();
    }

    private static boolean tappedJustNow() {
        long at = tappedAt;
        return at != 0 && SystemClock.uptimeMillis() - at <= TAP_WINDOW_MS;
    }

    /** The map's compass handler (onCompassButtonClicked): switch this map to the navigation camera first? */
    public static boolean useNavigationCamera() {
        return enabled && tappedJustNow();
    }

    // --- The navigator's camera (CameraStateReducerTiltAndPerspective) ------------------------------

    /**
     * The compass action, with the camera's orientation and the north-up following
     * orientation. 0: AllTrails' own handling (switch off, or not a tap on the
     * navigator's compass -- the camera also runs this action inside its "reset
     * view"); 1: go 2D; -1: go 3D.
     * From 2D following it goes 3D; from anywhere else it switches the mode kept.
     */
    public static int tap(Object orientation, Object northUpFollowing) {
        boolean tapped = tappedJustNow();
        tappedAt = 0;
        if (!enabled || !tapped) return 0;
        boolean toNorthUp = orientation != northUpFollowing && !northUp;
        remember(toNorthUp);
        return toNorthUp ? 1 : -1;
    }

    /**
     * Whenever the navigator's camera is about to follow heading-up for any reason
     * but a tap -- a navigation starting, re-centring after a pan -- it goes
     * north-up instead while 2D is the mode.
     */
    public static boolean keepNorthUp() {
        return enabled && northUp;
    }

    /** Heading-up's tilt, AllTrails' (45° while recording, else flat): always tilted in 3D. */
    public static boolean tilt(boolean recording) {
        return enabled || recording;
    }

    /** A pause flattens heading-up; in 3D it stays tilted. */
    public static Double pausedPitch(Double pitch) {
        return enabled ? Double.valueOf(TILT) : pitch;
    }

    // --- The compass (MapControlsState, CompassButtonComponent) ------------------------------------

    /** The controls' compass rotation (360 - bearing), and the map camera's reducer type. */
    public static float rotation(float rotation, Object cameraType) {
        return enabled && northUp && NAVIGATION_CAMERA.equals(String.valueOf(cameraType)) ? rotation + TWO_D_TURNS : rotation;
    }

    /** CompassButtonComponent's icon, AllTrails' needle, and the rotation it was given. */
    public static int icon(int needle, int rotation) {
        return rotation > TWO_D_TURNS - 180 && northIcon != 0 ? northIcon : needle;
    }
}
