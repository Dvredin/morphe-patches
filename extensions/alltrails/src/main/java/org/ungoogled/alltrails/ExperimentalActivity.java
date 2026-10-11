package org.ungoogled.alltrails;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;

/**
 * "Experimental" screen, opened from your profile's overflow menu. Built in code
 * from AllTrails' own Denali resources, looked up by name so the screen follows
 * the app's light and dark palettes (and AMOLED black's overlay): the Denali
 * toolbar's back arrow and title style (Denali.Toolbar: 56 dp tall, no inset
 * before the title), the primary background, and the Text200 secondary style for
 * body text. Each lookup falls back to the value this AllTrails version ships,
 * measured on the device.
 */
public final class ExperimentalActivity extends Activity {
    static final String TITLE = "Experimental";

    private boolean night;

    private int res(String name, String type) {
        return getResources().getIdentifier(name, type, getPackageName());
    }

    /**
     * ?attr/[attr] from this screen's theme, which carries the Theme setting's
     * overlay; else color/[name]; else the value this AllTrails version ships for
     * the current day/night mode.
     */
    private int color(String attr, String name, int day, int night) {
        int attrId = res(attr, "attr");
        TypedValue value = new TypedValue();
        if (attrId != 0 && getTheme().resolveAttribute(attrId, value, true)) {
            if (value.resourceId != 0) return getColor(value.resourceId);
            if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) return value.data;
        }
        int id = res(name, "color");
        return id != 0 ? getColor(id) : this.night ? night : day;
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    /** Applies an AllTrails text style if it is there; otherwise [sp] in the platform font. */
    private void style(TextView view, String style, float sp, int color) {
        int id = res(style, "style");
        if (id != 0) view.setTextAppearance(id);
        else view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
    }

    private interface Toggle {
        void set(boolean on);
    }

    /** A settings row: title and summary in AllTrails' Text200 / Text100 styles, the whole row tappable. */
    private LinearLayout row(String title, String summary, int text, int secondary) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setMinimumHeight(dp(64));
        TypedArray ripple = obtainStyledAttributes(new int[] {android.R.attr.selectableItemBackground});
        row.setBackground(ripple.getDrawable(0));
        ripple.recycle();

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(this);
        titleView.setText(title);
        style(titleView, "DenaliText.Text200", 16, text);
        labels.addView(titleView);
        TextView summaryView = new TextView(this);
        summaryView.setText(summary);
        style(summaryView, "DenaliText.Text100.Secondary", 13, secondary);
        summaryView.setPadding(0, dp(2), 0, 0);
        labels.addView(summaryView);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        labelParams.setMarginEnd(dp(16));
        row.addView(labels, labelParams);
        return row;
    }

    /** A feature's row, with the app's own SwitchMaterial at the end. The whole row toggles it. */
    private View toggleRow(String title, String summary, boolean checked, Toggle toggle, int text, int secondary) {
        LinearLayout row = row(title, summary, text, secondary);
        SwitchMaterial toggleView = new SwitchMaterial(this);
        toggleView.setChecked(checked);
        toggleView.setOnCheckedChangeListener((button, on) -> toggle.set(on));
        row.addView(toggleView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.setOnClickListener(v -> toggleView.toggle());
        return row;
    }

    /**
     * The Theme row: the current choice as its summary, and a tap offers all four
     * in AllTrails' own Material dialog. Picking one relaunches the screens, this
     * one included, in the new theme.
     */
    private View themeRow(int text, int secondary) {
        LinearLayout row = row("Theme", Theme.NAMES[Theme.get()], text, secondary);
        row.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle("Theme")
                .setSingleChoiceItems(Theme.NAMES, Theme.get(), (dialog, which) -> {
                    dialog.dismiss();
                    Theme.set(this, which);
                })
                .setNegativeButton("Cancel", null)
                .show());
        return row;
    }

    /** Before Android 12 the Theme setting reaches this non-AppCompat screen only this way. */
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Configuration night = Theme.nightOverride(base);
        if (night != null) applyOverrideConfiguration(night);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int background = color("denaliColorBackgroundPrimary", "color_background_primary", 0xFFFFFFFF, 0xFF0E110E);
        int text = color("denaliColorTextPrimary", "color_text_primary", 0xFF161F13, 0xFFF4F5F4);
        int secondary = color("denaliColorTextSecondary", "color_text_secondary", 0xFF6B746A, 0xA8F4F5F3);

        setTitle(TITLE);
        getWindow().getDecorView().setBackgroundColor(background);
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(night ? 0 : light, light);
        } else if (!night) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);
        // AllTrails targets API 36, so on Android 15+ the window is always edge to edge.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });

        // Toolbar, as Denali.Toolbar lays it out: 56 dp navigation button, title right after it.
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout back = new FrameLayout(this);
        TypedArray ripple = obtainStyledAttributes(new int[] {android.R.attr.selectableItemBackgroundBorderless});
        back.setBackground(ripple.getDrawable(0));
        ripple.recycle();
        back.setContentDescription("Navigate up");
        back.setOnClickListener(v -> finish());
        ImageView arrow = new ImageView(this);
        int arrowRes = res("ic_denali_arrow_sq_left", "drawable");
        arrow.setImageResource(arrowRes != 0 ? arrowRes : android.R.drawable.ic_menu_revert);
        arrow.setColorFilter(text);
        back.addView(arrow, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView title = new TextView(this);
        title.setText(TITLE);
        title.setSingleLine(true);
        style(title, "Denali.ToolbarTitleTextAppearance", 16, text);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.setMarginEnd(dp(16));
        toolbar.addView(title, titleParams);
        root.addView(toolbar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));

        // One row per experimental feature whose patch was included.
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(8), 0, dp(24));
        if (BetterFilters.patched()) {
            body.addView(toggleRow("Better filters", "adds finer adjustments to sliders",
                    BetterFilters.enabled(this), on -> BetterFilters.setEnabled(this, on), text, secondary));
        }
        if (RememberFilters.patched()) {
            body.addView(toggleRow("Remember filter states", "keeps your trail filters instead of resetting them",
                    RememberFilters.enabled(this), on -> RememberFilters.setEnabled(this, on), text, secondary));
        }
        if (BetterTracking.patched()) {
            body.addView(toggleRow("Better tracking", "Changes alltrails shitty tracking to a better universal system used by other apps",
                    BetterTracking.enabled(this), on -> BetterTracking.setEnabled(this, on), text, secondary));
        }
        if (Compass.patched()) {
            body.addView(toggleRow("2D / 3D compass", "switch between 2d & 3d view in navigation mode",
                    Compass.enabled(this), on -> Compass.setEnabled(this, on), text, secondary));
        }
        if (Theme.patched()) {
            body.addView(themeRow(text, secondary));
        }
        if (body.getChildCount() == 0) {
            TextView empty = new TextView(this);
            empty.setText("No experimental features yet.");
            style(empty, "DenaliText.Text200.Secondary", 16, secondary);
            empty.setPadding(dp(16), dp(16), dp(16), 0);
            body.addView(empty);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }
}
