package org.ungoogled.alltrails;

import android.content.Context;
import android.icu.text.DecimalFormat;
import android.view.View;
import android.widget.TextView;

import com.google.android.material.slider.LabelFormatter;
import com.google.android.material.slider.Slider;

/**
 * "Better filters": the trail filter's sliders move in finer steps over their
 * usual ranges. Rating runs from 0 to 5 stars in 0.1 steps, instead of
 * AllTrails' four stops (Any, 3.5, 4 and 4.5); the range and distance sliders
 * are further down.
 *
 * AllTrails keeps the rating as a four-value enum (RatingFilterValue) and only
 * turns it into a number, Filter.minimumRating, for the search; the search API
 * takes any number ("minRating"). So the precise value travels beside the enum:
 * the slider's change listener stores it on the rating field (a field the patch
 * adds), and the step that turns the field into the Filter prefers it.
 *
 * A slider is in precise mode once {@link #setupRatingSlider} has stretched it
 * to 5 (stock runs 0..3, one step per enum value), so every hook below decides
 * from the slider it is handed, and a filter screen set up before the switch
 * was flipped keeps behaving the way it was set up.
 */
public final class BetterFilters {
    private static final String PREFS = "ungoogled_experimental";
    private static final String KEY = "better_filters";
    private static final float MAX = 5f;
    private static final float STEP = 0.1f;
    /** The enum's query values in ordinal order in this AllTrails version: Any, OverThreePointFive, OverFour, OverFourPointFive. */
    private static final float[] STOPS = {0f, 3.5f, 4f, 4.5f};
    /** Below this a precise rating means Any. */
    private static final double ANY = 0.05;

    /**
     * The switch, cached for the range filters: their step tables are asked for
     * from code that has no Context (the filter view model builds them before
     * any view exists), so AllTrailsApplication.onCreate loads it here first.
     */
    private static volatile Boolean enabled;

    private BetterFilters() {}

    /** Rewritten to return true by the Better filters patch; the Experimental screen offers the switch only then. */
    public static boolean patched() {
        return false;
    }

    private static boolean read(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true);
    }

    /** From Experimental.init, as the app starts. */
    static void init(Context context) {
        enabled = read(context);
    }

    public static boolean enabled(Context context) {
        Boolean on = enabled;
        if (on == null) enabled = on = read(context);
        return patched() && on;
    }

    public static void setEnabled(Context context, boolean on) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply();
        enabled = on;
    }

    /** Without a Context: off until init() ran, which leaves every filter stock. */
    private static boolean precise() {
        Boolean on = enabled;
        return patched() && on != null && on;
    }

    private static boolean precise(Slider slider) {
        return slider.getValueTo() > STOPS.length - 0.5f;
    }

    /** One decimal, as the slider steps; float noise (4.2f is 4.19999...) must not reach the Filter. */
    private static double round(double value) {
        return Math.max(0, Math.min(MAX, Math.round(value * 10) / 10.0));
    }

    private static String format(double value) {
        return new DecimalFormat("#.#").format(value);
    }

    private static String string(Context context, String name, String fallback) {
        int id = context.getResources().getIdentifier(name, "string", context.getPackageName());
        return id != 0 ? context.getString(id) : fallback;
    }

    private static View find(View root, String name) {
        Context context = root.getContext();
        int id = context.getResources().getIdentifier(name, "id", context.getPackageName());
        return id != 0 ? root.findViewById(id) : null;
    }

    /** The enum stop a rating falls in: the highest query value at or below it. */
    public static int ratingStop(double value) {
        int stop = 0;
        for (int i = 1; i < STOPS.length; i++) {
            if (value >= STOPS[i] - 0.001) stop = i;
        }
        return stop;
    }

    /**
     * End of FilterView's rating section builder, once the slider, its labels and
     * its change listener are in place. Stretches the slider to 0..5 in 0.1 steps
     * and moves it to the filter's current rating, which the enum cannot hold.
     * Only the two end labels stay: 0+ and 5 (the old 4.5 label), each a tap
     * target for its end; the 3.5 and 4 labels would sit nowhere near their
     * values on the longer scale.
     */
    public static void setupRatingSlider(View filterView, Double current) {
        if (!enabled(filterView.getContext())) return;
        View view = find(filterView, "slider");
        if (!(view instanceof Slider)) return;
        Slider slider = (Slider) view;

        slider.setValueTo(MAX);
        slider.setStepSize(STEP);
        slider.setTickVisible(false);
        slider.setLabelBehavior(LabelFormatter.LABEL_FLOATING);
        slider.setLabelFormatter(value -> format(round(value)));
        slider.setValue((float) round(current != null ? current : 0));

        for (String hidden : new String[] {"threePointFivePlusLabel", "FourPlusLabel"}) {
            View label = find(filterView, hidden);
            if (label != null) label.setVisibility(View.INVISIBLE);
        }
        View low = find(filterView, "zeroPlusLabel");
        if (low != null) low.setOnClickListener(v -> slider.setValue(0f));
        View high = find(filterView, "fourPointFivePlusLabel");
        if (high instanceof TextView) {
            ((TextView) high).setText(format(MAX));
            high.setOnClickListener(v -> slider.setValue(MAX));
        }
        View subtitle = find(filterView, "sectionSubtitle");
        if (subtitle instanceof TextView) ratingSubtitle((TextView) subtitle, slider);
    }

    /** Change listener, in place of (int) slider.getValue(): which enum value to store. */
    public static int ratingIndex(Slider slider) {
        return precise(slider) ? ratingStop(slider.getValue()) : (int) slider.getValue();
    }

    /** Change listener, stored on the rating field: the precise rating, or null on a stock slider. */
    public static Double preciseRating(Slider slider) {
        return precise(slider) ? round(slider.getValue()) : null;
    }

    /** Change listener, after AllTrails wrote the enum's "Over 4" / "Any" into the section subtitle. */
    public static void ratingSubtitle(TextView subtitle, Slider slider) {
        if (subtitle == null || !precise(slider)) return;
        Context context = subtitle.getContext();
        double value = round(slider.getValue());
        subtitle.setText(value < ANY
                ? string(context, "any", "Any")
                : String.format(string(context, "over", "Over %s"), format(value)));
    }

    /**
     * Rating field to Filter: the precise rating when the field has one (Any
     * below 0.05, as the enum's Any is 0), otherwise what AllTrails computed.
     */
    public static Double minRating(Double precise, Double original) {
        if (precise == null) return original;
        return precise >= ANY ? precise : null;
    }

    /**
     * The rating slider's data binding, which sets the enum's ordinal. On a
     * precise slider that would throw the rating away, so it only moves the
     * slider when the enum disagrees with it (the filter was cleared or reset
     * from outside); the enum is always the precise value's stop otherwise.
     */
    public static void bindRatingValue(Slider slider, float ordinal) {
        if (!precise(slider)) {
            slider.setValue(ordinal);
        } else if (ratingStop(slider.getValue()) != (int) ordinal) {
            slider.setValue(STOPS[Math.max(0, Math.min(STOPS.length - 1, (int) ordinal))]);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Length, Elevation gain and Highest point: AllTrails' range sliders.
    //
    // They work in slider positions. Each filter has a step table (position <-> whole display units,
    // e.g. 0..30 mi one mile a step, then 35, 40, 45, 50) and a top position that means "N+", and the
    // shared code turns positions into meters through them. While Better filters is on, the step table
    // is a linear one and the top position moves out so it still lands on the same top value:
    //
    //   Length          0.1 mi / 0.1 km  up to 50 mi / 80 km
    //   Elevation gain  10 ft  / 5 m     up to 5,000 ft / 1,500 m
    //   Highest point   100 ft / 50 m    up to 15,000 ft / 4,500 m
    //
    // A step table only holds whole display units, so Length counts in tenths while precise: its
    // meters <-> miles conversions and its label format scale by ten.
    // ---------------------------------------------------------------------------------------------

    /** Grafted onto the range sliders' base class (RangeSliderAvailableFilter), whose isMetric getter R8 named O(). */
    public interface MetricFilter {
        boolean O();
    }

    /**
     * A linear step table. The patch grafts AllTrails' step table interface onto it, whose two
     * methods R8 named a (display value to position) and b (position to display value).
     */
    public static final class Steps {
        private final int step;

        Steps(int step) {
            this.step = step;
        }

        public int a(int value) {
            return (int) Math.round((double) value / step);
        }

        public int b(int position) {
            return position * step;
        }
    }

    private static final Steps ONE = new Steps(1);
    private static final Steps FIVE = new Steps(5);
    private static final Steps TEN = new Steps(10);
    private static final Steps FIFTY = new Steps(50);
    private static final Steps HUNDRED = new Steps(100);

    private static boolean metric(Object filter) {
        return ((MetricFilter) filter).O();
    }

    /** LengthAvailableFilter's step table: one position per tenth. */
    public static Object lengthSteps(Object filter, Object stock) {
        return precise() ? ONE : stock;
    }

    /** LengthAvailableFilter's top position (stock 34 / 45: 50 mi / 80 km). */
    public static int lengthMaxPosition(Object filter, int stock) {
        return precise() ? (metric(filter) ? 800 : 500) : stock;
    }

    /** LengthAvailableFilter, meters to display units: tenths while precise. */
    public static double lengthToUnits(double value) {
        return precise() ? value * 10 : value;
    }

    /** LengthAvailableFilter, display units to meters. */
    public static double lengthFromUnits(double value) {
        return precise() ? value / 10 : value;
    }

    /** The length labels' number format ("2.3 mi", "50+ mi"). */
    public static String formatLength(int value) {
        return precise()
                ? new java.text.DecimalFormat("#,##0.#").format(value / 10.0)
                : java.text.NumberFormat.getIntegerInstance().format(value);
    }

    /** The length summary's "min-max" text, whose min AllTrails appends as a bare number. */
    public static StringBuilder appendLength(StringBuilder text, int value) {
        return precise() ? text.append(formatLength(value)) : text.append(value);
    }

    /** ElevationAvailableFilter's step table. */
    public static Object elevationSteps(Object filter, Object stock) {
        return precise() ? (metric(filter) ? FIVE : TEN) : stock;
    }

    /** ElevationAvailableFilter's top position (stock 14: 5,000 ft / 1,500 m). */
    public static int elevationMaxPosition(Object filter, int stock) {
        return precise() ? (metric(filter) ? 300 : 500) : stock;
    }

    /** HighestPointAvailableFilter's step table. */
    public static Object highestPointSteps(Object filter, Object stock) {
        return precise() ? (metric(filter) ? FIFTY : HUNDRED) : stock;
    }

    /** HighestPointAvailableFilter's top position (stock 30 / 15: 15,000 ft / 4,500 m). */
    public static int highestPointMaxPosition(Object filter, int stock) {
        return precise() ? (metric(filter) ? 90 : 150) : stock;
    }

    // ---------------------------------------------------------------------------------------------
    // Distance away: a one-thumb slider whose field holds a position, from 1 up to a top position that
    // means Any. Two transformers map positions to miles or km (5, 10, 20, 40, 60 mi; 10, 20, 40, 60,
    // 80, 100 km). While precise, a position is simply miles or km, 1 to 60 mi / 100 km, then Any.
    // ---------------------------------------------------------------------------------------------

    private static final int MAX_MILES = 60;
    private static final int MAX_KM = 100;

    /** The imperial transformer, miles to position; -1 lets AllTrails' own run. */
    public static int distancePositionMiles(int miles) {
        return precise() ? Math.max(0, Math.min(MAX_MILES, miles)) : -1;
    }

    /** The metric transformer, km to position; -1 lets AllTrails' own run. */
    public static int distancePositionKm(int km) {
        return precise() ? Math.max(0, Math.min(MAX_KM, km)) : -1;
    }

    /** Either transformer, position to miles or km; -1 lets AllTrails' own run. */
    public static int distanceUnits(int position) {
        return precise() ? position : -1;
    }

    /** DistanceAwayFilterField's top (Any) position, imperial (stock 6). */
    public static int distanceMaxMiles(int stock) {
        return precise() ? MAX_MILES + 1 : stock;
    }

    /** DistanceAwayFilterField's top (Any) position, metric (stock 7). */
    public static int distanceMaxKm(int stock) {
        return precise() ? MAX_KM + 1 : stock;
    }

    /** The distance slider shows a tick per stop; at 61 stops they only blur into a dotted line. */
    public static void styleDistanceSlider(Slider slider) {
        if (precise()) slider.setTickVisible(false);
    }
}
