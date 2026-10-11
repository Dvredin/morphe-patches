package org.ungoogled.alltrails;

import android.app.Activity;
import android.app.Application;
import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatDelegate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * "Theme": Follow phone, Light, Dark or AMOLED black. AllTrails itself only
 * follows the phone.
 *
 * - Light and Dark set the app's own night mode: UiModeManager's per-app night
 *   mode on Android 12+, which every screen follows (Compose and views, and this
 *   extension's own screen); AppCompat's default night mode before that.
 * - AMOLED black is Dark with every dark background one neutral step darker, the
 *   base pure black. Compose screens paint from Denali's dark palette map
 *   (DenaliColors.darkPaletteMap, which nothing else reads colours around), so its
 *   background entries are pointed at the palette's own darker neutrals. View
 *   screens paint from the ?denaliColorBackground* theme attributes, which the
 *   MorpheAmoled overlay style (added by the patch) sets the same way on every
 *   activity before it inflates anything (Android 10+).
 *
 * Every change applies at once: a night mode change relaunches the app's
 * activities by itself, and a change between Dark and AMOLED black, which
 * changes no configuration, relaunches them here.
 */
public final class Theme {
    static final int FOLLOW_PHONE = 0, LIGHT = 1, DARK = 2, AMOLED = 3;
    static final String[] NAMES = {"Follow phone", "Light", "Dark", "AMOLED black"};

    private static final String PREFS = "ungoogled_experimental";
    private static final String KEY = "theme";
    private static final String OVERLAY = "MorpheAmoled";

    /**
     * Dark palette entry, then the entry AMOLED black paints it with. The patch's
     * MorpheAmoled style maps the ?denaliColorBackground* attributes the same way.
     */
    private static final String[][] AMOLED_PALETTE = {
        {"colorBackgroundPrimary", "colorNeutralBlack"},       // #0E110E -> #000000
        {"colorBackgroundPrimaryPress", "colorNeutral1000"},   // #151915 -> #0E110E
        {"colorBackgroundSecondary", "colorNeutral1000"},      // #151915 -> #0E110E
        {"colorBackgroundSecondaryPress", "colorNeutral950"},  // #222621 -> #151915
        {"colorBackgroundElevated", "colorNeutral1000"},       // #1D211D -> #0E110E
        {"colorBackgroundTertiary", "colorNeutral950"},        // #222621 -> #151915
        {"colorBackgroundTertiaryPress", "colorNeutral900"},   // #343933 -> #222621
    };

    private static volatile int theme = FOLLOW_PHONE;
    private static int overlay;
    private static Map<String, Object> darkPalette;
    private static final Map<String, Object> stockDarkPalette = new HashMap<>();
    private static final Set<Activity> activities = Collections.newSetFromMap(new WeakHashMap<>());

    private Theme() {}

    /** Rewritten to return true by the Theme patch; the Experimental screen offers the choice only then. */
    public static boolean patched() {
        return false;
    }

    /** From Experimental.init, as the app starts. */
    static void init(Context context) {
        if (!patched()) return;
        int saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, FOLLOW_PHONE);
        theme = saved >= FOLLOW_PHONE && saved <= AMOLED ? saved : FOLLOW_PHONE;
        overlay = context.getResources().getIdentifier(OVERLAY, "style", context.getPackageName());
        if (context instanceof Application) {
            ((Application) context).registerActivityLifecycleCallbacks(new Callbacks());
        }
        applyNightMode(context);
        applyPalette();
    }

    static int get() {
        return theme;
    }

    /** From the Experimental screen. */
    static void set(Activity from, int value) {
        if (!patched() || value == theme || value < FOLLOW_PHONE || value > AMOLED) return;
        boolean wasNight = night(theme);
        from.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, value).apply();
        theme = value;
        applyNightMode(from.getApplicationContext());
        applyPalette();
        if (night(value) == wasNight) {
            // No configuration changes (Dark <-> AMOLED black, or a forced mode the phone is
            // already in), so nothing relaunches by itself.
            for (Activity activity : new ArrayList<>(activities)) {
                if (!activity.isFinishing()) activity.recreate();
            }
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // Before Android 12 AppCompat relaunches only its own activities, and the
            // Experimental screen isn't one.
            from.recreate();
        }
    }

    /** Whether [value] shows the app dark. */
    private static boolean night(int value) {
        if (value == LIGHT) return false;
        if (value == DARK || value == AMOLED) return true;
        return (Resources.getSystem().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private static void applyNightMode(Context context) {
        int value = theme;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                UiModeManager uiMode = context.getSystemService(UiModeManager.class);
                // MODE_NIGHT_AUTO clears the app's own mode: the app follows the phone again.
                if (uiMode != null) {
                    uiMode.setApplicationNightMode(value == LIGHT ? UiModeManager.MODE_NIGHT_NO
                            : value == FOLLOW_PHONE ? UiModeManager.MODE_NIGHT_AUTO : UiModeManager.MODE_NIGHT_YES);
                }
            } else {
                AppCompatDelegate.setDefaultNightMode(value == LIGHT ? AppCompatDelegate.MODE_NIGHT_NO
                        : value == FOLLOW_PHONE ? AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM : AppCompatDelegate.MODE_NIGHT_YES);
            }
        } catch (RuntimeException ignored) {
            // Runs at app start: a ROM that refuses leaves the app following the phone.
        }
    }

    /**
     * For the Experimental screen, which isn't an AppCompat activity: before Android
     * 12 the configuration to force Light or Dark on it, else null.
     */
    static Configuration nightOverride(Context base) {
        int value = theme;
        if (!patched() || value == FOLLOW_PHONE || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return null;
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (value == LIGHT ? Configuration.UI_MODE_NIGHT_NO : Configuration.UI_MODE_NIGHT_YES);
        return config;
    }

    // --- Compose: Denali's palette maps --------------------------------------------------------------

    /**
     * End of DenaliColors' static initializer, once per palette map (unspecified,
     * light, dark). The dark one is the map whose primary background is the
     * darkest neutral: in the light map it's white, and in the unspecified map
     * every entry is the same Unspecified colour, white included.
     */
    public static void palette(Map<String, Object> palette) {
        // Runs inside DenaliColors' static initializer, where a throw would fail every Compose screen.
        try {
            if (palette == null) return;
            Object primary = palette.get("colorBackgroundPrimary");
            if (primary == null || !primary.equals(palette.get("colorNeutral1000")) || primary.equals(palette.get("colorNeutralWhite"))) return;
            synchronized (Theme.class) {
                darkPalette = palette;
                for (String[] entry : AMOLED_PALETTE) {
                    stockDarkPalette.put(entry[0], palette.get(entry[0]));
                    stockDarkPalette.put(entry[1], palette.get(entry[1]));
                }
            }
            applyPalette();
        } catch (RuntimeException ignored) {
            // AllTrails' own dark palette stays.
        }
    }

    private static synchronized void applyPalette() {
        if (darkPalette == null) return;
        boolean amoled = theme == AMOLED;
        try {
            for (String[] entry : AMOLED_PALETTE) {
                Object colour = stockDarkPalette.get(amoled ? entry[1] : entry[0]);
                if (colour != null) darkPalette.put(entry[0], colour);
            }
        } catch (RuntimeException ignored) {
            // A palette map that stopped being writable: AllTrails' own dark palette stays.
        }
    }

    // --- Views: the theme overlay ----------------------------------------------------------------------

    private static final class Callbacks implements Application.ActivityLifecycleCallbacks {
        /** Android 10+: after the activity has its theme and configuration, before its onCreate inflates anything. */
        @Override public void onActivityPreCreated(Activity activity, Bundle savedInstanceState) {
            activities.add(activity);
            if (theme != AMOLED || overlay == 0) return;
            int uiMode = activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            if (uiMode == Configuration.UI_MODE_NIGHT_YES) activity.getTheme().applyStyle(overlay, true);
        }

        @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            activities.add(activity);
        }

        @Override public void onActivityDestroyed(Activity activity) {
            activities.remove(activity);
        }

        @Override public void onActivityStarted(Activity activity) {}
        @Override public void onActivityResumed(Activity activity) {}
        @Override public void onActivityPaused(Activity activity) {}
        @Override public void onActivityStopped(Activity activity) {}
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
    }
}
