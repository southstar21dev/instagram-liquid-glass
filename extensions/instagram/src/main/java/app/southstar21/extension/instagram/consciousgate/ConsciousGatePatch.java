package app.southstar21.extension.instagram.consciousgate;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.WeakHashMap;

import app.southstar21.extension.instagram.InstagramHooks;
import app.southstar21.extension.instagram.settings.ActionSetting;
import app.southstar21.extension.instagram.settings.BooleanSetting;
import app.southstar21.extension.instagram.settings.Icons;
import app.southstar21.extension.instagram.settings.IntSetting;
import app.southstar21.extension.instagram.settings.PatchSettings;
import app.southstar21.extension.instagram.settings.SettingsGroup;
import app.southstar21.extension.instagram.settings.StringSetting;

/**
 * Conscious gate patch: a daily time budget for the home feed, Reels and Explore.
 * <p>
 * Time counts while one of the gated screens is on screen and Instagram has focus. During the
 * last minutes of the budget the screens slowly frost over and can only be scrolled, and once
 * it's used up a gate closes them until the next day. Quiet hours and a morning start can keep
 * them closed at set times. Searching for people, messages and profiles always work.
 * <p>
 * Driven from {@link InstagramHooks}, which calls {@link #onMainActivityFocusChanged} each time
 * the main activity gains or loses focus. While it has focus, a ticker counts time and
 * updates the frost; it stops when focus is lost.
 */
@SuppressWarnings("unused")
public final class ConsciousGatePatch {
    static final String TAG = "ConsciousGate";

    private static final long TICK_MS = 500;
    /** A longer gap between ticks means the app was paused, so it isn't counted. */
    private static final long MAX_TICK_GAP_MS = 2000;
    private static final long SAVE_EVERY_MS = 5000;
    private static final long MINUTE_MS = 60_000;

    // region Settings

    static final BooleanSetting GATE_HOME = new BooleanSetting("conscious_gate_home",
            "Home feed", "Posts and stories on the home feed.", false, true);
    static final BooleanSetting GATE_REELS = new BooleanSetting("conscious_gate_reels",
            "Reels", "The Reels tab, and reels opened from anywhere else.", false, true);
    static final BooleanSetting GATE_EXPLORE = new BooleanSetting("conscious_gate_explore",
            "Explore", "The Explore grid, and posts and reels in search results.", false, true);

    static final IntSetting WEEKDAY_MINUTES = new IntSetting("conscious_gate_minutes",
            "Weekday time", "Monday to Friday, shared by the gated screens.", false,
            5, 120, "min", () -> 20);
    static final IntSetting WEEKEND_MINUTES = new IntSetting("conscious_gate_weekend_minutes",
            "Weekend time", "Saturday and Sunday.", false,
            5, 180, "min", () -> 30);
    static final IntSetting FROST_MINUTES = new IntSetting("conscious_gate_frost_minutes",
            "Frost builds over", "The last minutes of the budget, as the screens slowly frost over. "
                    + "While frosted, they can be scrolled but nothing can be tapped.", false,
            1, 15, "min", () -> 5);
    static final IntSetting DAY_START = new IntSetting("conscious_gate_day_start",
            "New day starts at", "When the budget starts again. Scrolling after midnight still "
                    + "counts towards the night before.", false,
            0, 8, ":00", () -> 4);

    static final BooleanSetting QUIET_HOURS = new BooleanSetting("conscious_gate_quiet_hours",
            "Quiet hours", "The gated screens stay closed overnight.", false, false);
    static final IntSetting QUIET_FROM = new IntSetting("conscious_gate_quiet_from",
            "Quiet from", null, false, 0, 23, ":00", () -> 23);
    static final IntSetting QUIET_UNTIL = new IntSetting("conscious_gate_quiet_until",
            "Quiet until", null, false, 0, 23, ":00", () -> 7);
    static final BooleanSetting MORNING_START = new BooleanSetting("conscious_gate_morning_start",
            "Morning start", "The gated screens stay closed each morning until the time below.", false, false);
    static final IntSetting MORNING_HOUR = new IntSetting("conscious_gate_morning_hour",
            "Opens at", null, false, 5, 14, ":00", () -> 9);

    static final IntSetting EXTRA_MINUTES = new IntSetting("conscious_gate_extra_minutes",
            "Extra minutes", "How much time the gate's button adds.", false,
            1, 15, "min", () -> 5);
    static final IntSetting EXTRA_TIMES = new IntSetting("conscious_gate_extra_times",
            "Times per day", "How often the gate can be opened for extra minutes. 0 turns it off.", false,
            0, 3, "×", () -> 1);
    static final IntSetting EXTRA_WAIT = new IntSetting("conscious_gate_extra_wait",
            "Wait before extending", "The button counts down first, so taking more time is a choice "
                    + "rather than a reflex.", false,
            0, 30, "s", () -> 10);

    static final StringSetting MESSAGE = new StringSetting("conscious_gate_message",
            "Gate message", "Your own words on the gate, instead of “That's the feed for today”.",
            "Go for a walk", 60);

    public static final PatchSettings SETTINGS = new PatchSettings("conscious_gate",
            "Conscious gate",
            "A daily time budget for the home feed, Reels and Explore. Near the end they frost over "
                    + "and can only be scrolled, then a gate closes them until the next day.",
            ConsciousGatePatch::isIncluded,
            new SettingsGroup("Gated screens",
                    "Searching for people, messages and profiles always work.",
                    GATE_HOME, GATE_REELS, GATE_EXPLORE),
            new SettingsGroup("Budget",
                    "Counts the time a gated screen is on screen. Closed screens don't use time.",
                    WEEKDAY_MINUTES, WEEKEND_MINUTES, FROST_MINUTES, DAY_START),
            new SettingsGroup("Schedule", QUIET_HOURS, QUIET_FROM, QUIET_UNTIL, MORNING_START, MORNING_HOUR),
            new SettingsGroup("Extra time", EXTRA_MINUTES, EXTRA_TIMES, EXTRA_WAIT),
            new SettingsGroup("Gate", MESSAGE),
            // Temporary, for trying the gate out without waiting for the budget.
            new SettingsGroup("Testing", "Manual triggers for trying the gate out. They change today's time only.",
                    new ActionSetting("Start the frost now",
                            "Skips to the start of the frost, which then builds over the minutes set above.",
                            ConsciousGatePatch::triggerFrost),
                    new ActionSetting("Freeze now",
                            "Fully frosts the screens and closes the gate right away, without the gradual blur.",
                            ConsciousGatePatch::triggerGate),
                    new ActionSetting("Reset today", "Clears today's time and extra minutes.",
                            ConsciousGatePatch::resetToday)))
            .icon(Icons.Glyph.HOURGLASS, 0xFF30B0C7);

    /** The patch makes this return true, so the menu only shows patches that were applied. */
    public static boolean isIncluded() {
        return false;
    }

    // endregion

    // region State

    private static final String STATE_PREFERENCES = "southstar21_conscious_gate";
    private static final String KEY_DAY = "day";
    private static final String KEY_USED = "used_ms";
    private static final String KEY_EXTRA_COUNT = "extra_count";
    private static final String KEY_EXTRA_MS = "extra_ms";
    private static final String KEY_LAST_GRANT = "last_grant_ms";
    private static final String KEY_GRANTED_AT = "granted_at_ms";

    private static SharedPreferences state;
    private static String day;
    /** Time used on the gated screens today. */
    private static long usedMs;
    /** Extra time taken today: how often, how much in total, and the last amount. */
    private static int extraCount;
    private static long extraMs;
    private static long lastGrantMs;
    /** Time used when extra time was last taken. */
    private static long grantedAtMs;
    private static long lastSave;

    private static void loadState(Context context) {
        if (state != null) return;
        state = context.getApplicationContext().getSharedPreferences(STATE_PREFERENCES, Context.MODE_PRIVATE);
        day = state.getString(KEY_DAY, null);
        usedMs = state.getLong(KEY_USED, 0);
        extraCount = state.getInt(KEY_EXTRA_COUNT, 0);
        extraMs = state.getLong(KEY_EXTRA_MS, 0);
        lastGrantMs = state.getLong(KEY_LAST_GRANT, 0);
        grantedAtMs = state.getLong(KEY_GRANTED_AT, 0);
        rollOverDay();
    }

    /** Now, moved back by the day start, so the small hours count towards the day before. */
    private static Calendar budgetDay() {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.HOUR_OF_DAY, -DAY_START.get());
        return calendar;
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(budgetDay().getTime());
    }

    /** Starts a fresh budget when the day changes. */
    private static void rollOverDay() {
        String today = today();
        if (today.equals(day)) return;
        day = today;
        clearToday();
    }

    private static void clearToday() {
        usedMs = 0;
        extraCount = 0;
        extraMs = 0;
        lastGrantMs = 0;
        grantedAtMs = 0;
        save();
    }

    private static void save() {
        if (state == null) return;
        lastSave = SystemClock.uptimeMillis();
        state.edit()
                .putString(KEY_DAY, day)
                .putLong(KEY_USED, usedMs)
                .putInt(KEY_EXTRA_COUNT, extraCount)
                .putLong(KEY_EXTRA_MS, extraMs)
                .putLong(KEY_LAST_GRANT, lastGrantMs)
                .putLong(KEY_GRANTED_AT, grantedAtMs)
                .apply();
    }

    static boolean isWeekend() {
        int weekday = budgetDay().get(Calendar.DAY_OF_WEEK);
        return weekday == Calendar.SATURDAY || weekday == Calendar.SUNDAY;
    }

    static long budgetMs() {
        int minutes = isWeekend() ? WEEKEND_MINUTES.get() : WEEKDAY_MINUTES.get();
        return minutes * MINUTE_MS + extraMs;
    }

    static int budgetMinutes() {
        return (int) (budgetMs() / MINUTE_MS);
    }

    static long remainingMs() {
        return Math.max(0, budgetMs() - usedMs);
    }

    private static long frostStartMs() {
        long frostMs = FROST_MINUTES.get() * MINUTE_MS;
        long start = budgetMs() - frostMs;
        if (extraCount > 0) {
            // After extra time is taken, its first part is always clear, since the
            // screens can't be tapped while they're frosted.
            long extraFrost = Math.min(frostMs, lastGrantMs * 2 / 5);
            start = Math.max(start, grantedAtMs + lastGrantMs - extraFrost);
        }
        return Math.max(0, start);
    }

    // endregion

    // region Gate state

    /** Why the gated screens are closed, or OPEN. */
    enum Reason {
        OPEN, BUDGET, QUIET_HOURS, MORNING
    }

    static Reason reason() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (QUIET_HOURS.get() && inHours(hour, QUIET_FROM.get(), QUIET_UNTIL.get())) return Reason.QUIET_HOURS;
        if (MORNING_START.get() && inHours(hour, DAY_START.get(), MORNING_HOUR.get())) return Reason.MORNING;
        return usedMs >= budgetMs() ? Reason.BUDGET : Reason.OPEN;
    }

    /** Whether {@code hour} is in [from, until), which may wrap past midnight. */
    private static boolean inHours(int hour, int from, int until) {
        if (from == until) return false;
        return from < until ? hour >= from && hour < until : hour >= from || hour < until;
    }

    /** The hour the gated screens open again, for the gate's text. */
    static int reopensAt(Reason reason) {
        switch (reason) {
            case QUIET_HOURS:
                return QUIET_UNTIL.get();
            case MORNING:
                return MORNING_HOUR.get();
            default:
                return MORNING_START.get() ? Math.max(DAY_START.get(), MORNING_HOUR.get()) : DAY_START.get();
        }
    }

    /**
     * How frosted the screens are, from 0 (clear) to 1 (fully frosted). The frost builds over
     * the last minutes of the budget, and is full whenever the gate is closed.
     */
    static float frostLevel() {
        if (reason() != Reason.OPEN) return 1f;
        long start = frostStartMs();
        long budget = budgetMs();
        if (usedMs <= start) return 0f;
        if (budget <= start) return 1f;
        return Math.min(1f, (usedMs - start) / (float) (budget - start));
    }

    static boolean canTakeExtra() {
        return reason() == Reason.BUDGET && extraCount < EXTRA_TIMES.get();
    }

    /** The gate's title: the user's own message, or one for the reason. */
    static String title(Reason reason) {
        String message = MESSAGE.get();
        if (!TextUtils.isEmpty(message)) return message;
        switch (reason) {
            case QUIET_HOURS:
                return "Quiet hours";
            case MORNING:
                return "Not yet";
            default:
                return "That's the feed for today";
        }
    }

    static String body(Reason reason) {
        String opens = reopensAt(reason) + ":00";
        String first;
        switch (reason) {
            case QUIET_HOURS:
                first = "The feed, Reels and Explore open again at " + opens + ".";
                break;
            case MORNING:
                first = "The feed, Reels and Explore open at " + opens + ".";
                break;
            default:
                first = "You've had your " + budgetMinutes() + " minutes. They open again at " + opens + ".";
                break;
        }
        return first + "\nSearching for people, messages and profiles still work.";
    }

    /** The text of the badge on the gated screens. */
    static String badge(Reason reason) {
        String active = "Conscious gate is active";
        switch (reason) {
            case OPEN: {
                long left = remainingMs();
                if (left < MINUTE_MS) return active + " · under a minute left";
                long minutes = (left + MINUTE_MS - 1) / MINUTE_MS;
                return active + " · " + minutes + " min left";
            }
            case QUIET_HOURS:
                return active + " · quiet hours";
            case MORNING:
                return active + " · opens at " + MORNING_HOUR.get() + ":00";
            default:
                return active;
        }
    }

    /** Called from the gate's extra time button. */
    static void takeExtra() {
        if (!canTakeExtra()) return;
        lastGrantMs = EXTRA_MINUTES.get() * MINUTE_MS;
        grantedAtMs = usedMs;
        extraMs += lastGrantMs;
        extraCount++;
        save();
        GatedScreens screens = currentScreens();
        if (screens != null) screens.thaw();
    }

    // endregion

    // region Manual triggers

    private static boolean ensureState() {
        if (state != null) return true;
        Activity activity = activityRef.get();
        if (activity == null) return false;
        loadState(activity);
        return true;
    }

    private static String triggerFrost() {
        if (!ensureState()) return "Open Instagram's home screen once first";
        usedMs = frostStartMs();
        save();
        return "The frost starts on the gated screens";
    }

    private static String triggerGate() {
        if (!ensureState()) return "Open Instagram's home screen once first";
        usedMs = budgetMs();
        save();
        return "The gated screens are frozen";
    }

    private static String resetToday() {
        if (!ensureState()) return "Open Instagram's home screen once first";
        clearToday();
        GatedScreens screens = currentScreens();
        if (screens != null) screens.thaw();
        return "Today's time is reset";
    }

    // endregion

    // region Ticker

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable tick = ConsciousGatePatch::tick;
    private static final WeakHashMap<Activity, GatedScreens> screensByActivity = new WeakHashMap<>();
    private static WeakReference<Activity> activityRef = new WeakReference<>(null);
    private static long lastTick;

    private static GatedScreens currentScreens() {
        Activity activity = activityRef.get();
        return activity == null ? null : screensByActivity.get(activity);
    }

    /** Called by {@link InstagramHooks} when this patch is switched on. */
    public static void onMainActivityFocusChanged(Activity activity) {
        try {
            loadState(activity);
            activityRef = new WeakReference<>(activity);
            if (!screensByActivity.containsKey(activity)) {
                screensByActivity.put(activity, new GatedScreens(activity));
            }
            handler.removeCallbacks(tick);
            lastTick = SystemClock.uptimeMillis();
            tick();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start", t);
        }
    }

    private static void tick() {
        try {
            Activity activity = activityRef.get();
            if (activity == null || activity.isDestroyed()) return;
            GatedScreens screens = screensByActivity.get(activity);
            if (screens == null) return;

            long now = SystemClock.uptimeMillis();
            long elapsed = now - lastTick;
            lastTick = now;
            rollOverDay();

            boolean focused = activity.hasWindowFocus();
            boolean onScreen = screens.update();
            if (focused && onScreen && reason() == Reason.OPEN && elapsed <= MAX_TICK_GAP_MS) {
                usedMs += elapsed;
                // Show the gate as soon as the last second runs out.
                if (reason() != Reason.OPEN) screens.update();
            }

            if (!focused || now - lastSave >= SAVE_EVERY_MS) save();
            // Without focus nothing is counted, so wait for the next focus change.
            if (focused) handler.postDelayed(tick, TICK_MS);
        } catch (Throwable t) {
            Log.e(TAG, "Tick failed", t);
        }
    }

    // endregion
}
