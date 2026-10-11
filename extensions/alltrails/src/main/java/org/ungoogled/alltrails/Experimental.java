package org.ungoogled.alltrails;

import android.content.Context;
import android.content.Intent;
import android.view.Menu;

/** Adds the "Experimental" row to your own profile's overflow menu. */
public final class Experimental {
    /** Menu item ids only have to be unique within their menu; this is not a resource id. */
    private static final int ITEM_ID = 0x0E7E0001;

    private Experimental() {}

    /**
     * Start of AllTrailsApplication.onCreate. The features' switches are read
     * here once, because some are needed where no Context is at hand (the filter
     * view model and filter store, the location vendor factory, the track
     * recorder, the map camera, the Compose palette), and the chosen theme is
     * applied before any screen opens.
     */
    public static void init(Context context) {
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        BetterFilters.init(app);
        RememberFilters.init(app);
        BetterTracking.init(app);
        Compass.init(app);
        Theme.init(app);
    }

    /**
     * Called at the start of the profile screen's overflow-menu prepare callback,
     * which AllTrails runs on a freshly inflated menu every time the overflow opens,
     * for your own profile and for other people's alike. Only your own profile's
     * menu has Edit profile, so the row is added only where [editProfileId] is.
     *
     * The row handles its own tap: a MenuItem's click listener runs before the
     * menu's, so AllTrails' onOptionsItemSelected never sees it.
     */
    public static void addMenuItem(Context context, Menu menu, int editProfileId) {
        if (context == null || menu == null) return;
        if (menu.findItem(editProfileId) == null || menu.findItem(ITEM_ID) != null) return;
        menu.add(Menu.NONE, ITEM_ID, Menu.NONE, ExperimentalActivity.TITLE).setOnMenuItemClickListener(item -> {
            context.startActivity(new Intent(context, ExperimentalActivity.class));
            return true;
        });
    }
}
