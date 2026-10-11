package org.ungoogled.alltrails;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * "Remember filter states": the trail filters you set stay set. AllTrails keeps
 * them only in memory (ExploreFilterProvider), clears them every time you go
 * back from search results to the home screen, and starts empty whenever the
 * app restarts.
 *
 * Remembered: the choices the filter screen makes (Filter's sort, length,
 * elevation gain, highest point, rating, difficulty, activities, features,
 * suitability, route type, traffic, completed / saved). Not the place (location,
 * search term, list), and not Distance away, which only counts together with a
 * travel area worked out for where you were when you set it.
 *
 * Filter is a Gson model, so its field names and generic types are intact; the
 * choices are read and written by reflection and stored as JSON.
 */
public final class RememberFilters {
    private static final String PREFS = "ungoogled_experimental";
    private static final String KEY = "remember_filters";
    private static final String KEY_STATE = "remember_filters_state";

    private static final String[] FIELDS = {
        "sort", "length", "elevationGain", "highestPoint", "minimumRating", "difficulties",
        "activityUids", "featureUids", "suitabilityUids", "routeTypes", "trailTraffic",
        "trailCompletion", "trailSavedAndCompleted",
    };

    private static Context app;
    private static volatile boolean enabled;
    private static volatile String saved;

    private RememberFilters() {}

    /** Rewritten to return true by the Remember filters patch; the Experimental screen offers the switch only then. */
    public static boolean patched() {
        return false;
    }

    /** From Experimental.init, as the app starts. */
    static void init(Context context) {
        app = context;
        enabled = patched() && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true);
        saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STATE, null);
    }

    public static boolean enabled(Context context) {
        return patched() && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true);
    }

    public static void setEnabled(Context context, boolean on) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply();
        enabled = patched() && on;
    }

    // --- ExploreFilterProvider --------------------------------------------------------------------

    /** updateFilter(filter, fromUser, forceRequery): what you set is kept, map moves and app updates are not. */
    public static void save(Object filter, boolean fromUser) {
        if (!enabled || !fromUser || filter == null) return;
        try {
            String state = encode(filter);
            if (state.equals(saved)) return;
            saved = state;
            if (app != null) app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_STATE, state).apply();
        } catch (Throwable ignored) {
            // A filter this version can't read: nothing is kept.
        }
    }

    /**
     * The provider's first filter update, AllTrails' empty default (a shared
     * constant, so not changed): a new update with a new filter holding the
     * saved choices.
     */
    public static Object initialUpdate(Object update) {
        String state = saved;
        if (!enabled || state == null || update == null) return update;
        try {
            for (Constructor<?> constructor : update.getClass().getConstructors()) {
                Class<?>[] parameters = constructor.getParameterTypes();
                if (parameters.length != 3 || parameters[1] != boolean.class || parameters[2] != boolean.class) continue;
                Object filter = parameters[0].getConstructor().newInstance();
                decode(filter, state);
                return constructor.newInstance(filter, false, false);
            }
        } catch (Throwable ignored) {
            // AllTrails' empty default stays.
        }
        return update;
    }

    /** clearFilters, when you go back from results to the home screen: its fresh filter gets the saved choices. */
    public static void restore(Object filter) {
        String state = saved;
        if (!enabled || state == null || filter == null) return;
        try {
            decode(filter, state);
        } catch (Throwable ignored) {
            // The fresh filter stays empty.
        }
    }

    // --- JSON ------------------------------------------------------------------------------------

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static String encode(Object filter) throws Exception {
        JSONObject json = new JSONObject();
        for (String name : FIELDS) json.put(name, toJson(field(filter.getClass(), name).get(filter)));
        return json.toString();
    }

    private static Object toJson(Object value) throws Exception {
        if (value == null) return JSONObject.NULL;
        if (value instanceof Enum) return ((Enum<?>) value).name();
        if (value instanceof Number || value instanceof Boolean || value instanceof String) return value;
        if (value instanceof List) {
            JSONArray array = new JSONArray();
            for (Object item : (List<?>) value) array.put(toJson(item));
            return array;
        }
        // RangeFilter(min, max).
        JSONObject range = new JSONObject();
        range.put("min", toJson(field(value.getClass(), "min").get(value)));
        range.put("max", toJson(field(value.getClass(), "max").get(value)));
        return range;
    }

    private static void decode(Object filter, String state) throws Exception {
        JSONObject json = new JSONObject(state);
        for (String name : FIELDS) {
            if (!json.has(name)) continue;
            try {
                Field field = field(filter.getClass(), name);
                Object value = fromJson(json.get(name), field.getType(), field.getGenericType());
                // Lists are never null in Filter.
                if (value == null && List.class.isAssignableFrom(field.getType())) continue;
                field.set(filter, value);
            } catch (Throwable ignored) {
                // This one stays as it was.
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object fromJson(Object json, Class<?> type, Type generic) throws Exception {
        if (json == null || json == JSONObject.NULL) return null;
        if (type.isEnum()) return Enum.valueOf((Class<Enum>) type, (String) json);
        if (type == Double.class || type == double.class) return ((Number) json).doubleValue();
        if (type == Integer.class || type == int.class) return ((Number) json).intValue();
        if (type == Boolean.class || type == boolean.class || type == String.class) return json;
        if (List.class.isAssignableFrom(type)) {
            Type element = generic instanceof ParameterizedType ? ((ParameterizedType) generic).getActualTypeArguments()[0] : Object.class;
            Class<?> elementType = element instanceof Class ? (Class<?>) element
                    : element instanceof ParameterizedType ? (Class<?>) ((ParameterizedType) element).getRawType() : Object.class;
            JSONArray array = (JSONArray) json;
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) list.add(fromJson(array.get(i), elementType, element));
            return list;
        }
        // RangeFilter(min, max).
        JSONObject range = (JSONObject) json;
        return type.getConstructor(Double.class, Double.class).newInstance(
                fromJson(range.get("min"), Double.class, Double.class), fromJson(range.get("max"), Double.class, Double.class));
    }
}
