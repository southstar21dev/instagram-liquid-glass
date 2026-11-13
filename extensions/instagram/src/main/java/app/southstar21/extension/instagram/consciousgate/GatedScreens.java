package app.southstar21.extension.instagram.consciousgate;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.southstar21.extension.instagram.consciousgate.ConsciousGatePatch.Reason;

/**
 * Finds the gated screens of one activity, frosts them, and keeps the gate over the one on
 * screen.
 * <p>
 * Instagram's screens are fragments, and the root view of each carries its fragment in a view
 * tag. Every fragment names itself through {@code getModuleName()}, which isn't obfuscated:
 * "feed_timeline" for the home feed, "clips_viewer_*" for Reels, "explore_popular" for Explore
 * and "serp_*" for search results ("serp_users" is the Accounts tab, which stays open). Each
 * gated screen's largest list is frosted (a blur on Android 12+ plus a tint) and covered by the
 * {@link GateView}, which is kept over the list's visible area every frame.
 */
@SuppressLint("NewApi")
final class GatedScreens {
    enum Kind {
        HOME, REELS, EXPLORE
    }

    private static final String RECYCLER_VIEW = "androidx.recyclerview.widget.RecyclerView";
    /** While the screens are frosted or closed, a newly shown screen is picked up this fast. */
    private static final long ACTIVE_RESCAN_MS = 150;
    private static final float MAX_BLUR_DP = 30;
    /** Tint at full frost, with and without blur. */
    private static final float MAX_TINT_WITH_BLUR = 0.35f;
    private static final float MAX_TINT_WITHOUT_BLUR = 0.9f;
    private static final long THAW_MS = 700;

    private static final Map<Class<?>, Boolean> recyclerClasses = new HashMap<>();

    private final Activity activity;
    private final float density;
    private final int fragmentTagId;
    private final WeakHashMap<Object, String> modules = new WeakHashMap<>();

    /** Lists of the gated screens on screen right now, and what each one is. */
    private final List<WeakReference<View>> onScreen = new ArrayList<>();
    private final WeakHashMap<View, Kind> kinds = new WeakHashMap<>();
    private final WeakHashMap<View, String> listModules = new WeakHashMap<>();
    /** Frost level and tint of every list frosted so far. */
    private final WeakHashMap<View, Float> applied = new WeakHashMap<>();
    private final WeakHashMap<View, ColorDrawable> tints = new WeakHashMap<>();
    /** When Reels were last left, to wait for that to finish before trying again. */
    private long lastLeave;
    private long lastLeaveToast;

    private final Rect rect = new Rect();
    private final Rect otherRect = new Rect();
    private float level;
    private Reason reason = Reason.OPEN;
    private long lastScan;
    private View tabBar;
    private final Map<Kind, View> headers = new HashMap<>();
    private static final String[][] HEADER_IDS = {
            {"main_feed_action_bar_container", "main_feed_action_bar"},
            {"clips_viewer_action_bar"},
            {"explore_action_bar"},
    };

    private GateView gate;
    private ValueAnimator thawAnimator;

    GatedScreens(Activity activity) {
        this.activity = activity;
        this.density = activity.getResources().getDisplayMetrics().density;
        this.fragmentTagId = findId("fragment_container_view_tag");
        View decor = activity.getWindow().getDecorView();
        decor.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                try {
                    if (level > 0f || reason != Reason.OPEN) {
                        if (SystemClock.uptimeMillis() - lastScan >= ACTIVE_RESCAN_MS) refresh();
                        syncGate();
                    }
                } catch (Throwable t) {
                    decor.getViewTreeObserver().removeOnPreDrawListener(this);
                    Log.e(ConsciousGatePatch.TAG, "Frame update failed", t);
                }
                return true;
            }
        });
    }

    /**
     * Brings the screens up to date with the budget.
     *
     * @return whether a gated screen is on screen.
     */
    boolean update() {
        level = ConsciousGatePatch.frostLevel();
        reason = ConsciousGatePatch.reason();
        refresh();
        return !onScreen.isEmpty();
    }

    /** Clears the frost after the gate opens for extra time, or today is reset. */
    void thaw() {
        level = 0f;
        reason = Reason.OPEN;
        hideGate();
        if (thawAnimator != null) thawAnimator.cancel();
        float from = 0f;
        for (Float value : applied.values()) {
            if (value != null) from = Math.max(from, value);
        }
        if (from == 0f) return;
        thawAnimator = ValueAnimator.ofFloat(from, 0f);
        thawAnimator.setDuration(THAW_MS);
        thawAnimator.setInterpolator(new DecelerateInterpolator());
        thawAnimator.addUpdateListener(a -> applyAll((float) a.getAnimatedValue()));
        thawAnimator.start();
    }

    private void refresh() {
        scan();
        if (thawAnimator != null && thawAnimator.isRunning()) return;
        applyAll(level);
        boolean active = level > 0f || reason != Reason.OPEN;
        if (active) showGate();
        else hideGate();
        if (reason != Reason.OPEN) leaveReels();
    }

    // region Finding the screens

    static Kind kindOf(String module) {
        if (module == null) return null;
        if (module.equals("feed_timeline")) return Kind.HOME;
        if (module.startsWith("clips_viewer")) return Kind.REELS;
        if (module.startsWith("explore")) return Kind.EXPLORE;
        // Search results: everything but the Accounts tab.
        if (module.startsWith("serp_") && !module.startsWith("serp_users")) return Kind.EXPLORE;
        return null;
    }

    private static boolean isEnabled(Kind kind) {
        switch (kind) {
            case HOME:
                return ConsciousGatePatch.GATE_HOME.get();
            case REELS:
                return ConsciousGatePatch.GATE_REELS.get();
            default:
                return ConsciousGatePatch.GATE_EXPLORE.get();
        }
    }

    private void scan() {
        lastScan = SystemClock.uptimeMillis();
        onScreen.clear();
        if (fragmentTagId == 0) return;
        walk(activity.getWindow().getDecorView(), null);
    }

    private void walk(View view, Object parentFragment) {
        if (view.getVisibility() != View.VISIBLE) return;
        Object fragment = view.getTag(fragmentTagId);
        if (fragment != null && fragment != parentFragment) {
            String module = moduleOf(fragment);
            Kind kind = kindOf(module);
            if (kind != null && isEnabled(kind)) {
                View list = largestList(view);
                if (list != null && isOnScreen(list) && !contains(list)) {
                    onScreen.add(new WeakReference<>(list));
                    kinds.put(list, kind);
                    listModules.put(list, module);
                }
            }
        }
        // Instagram's tabs and pagers are RecyclerViews too, so only the gated lists themselves
        // are skipped; their items can't hold another screen.
        if (view instanceof ViewGroup && !contains(view)) {
            ViewGroup group = (ViewGroup) view;
            Object current = fragment != null ? fragment : parentFragment;
            for (int i = 0; i < group.getChildCount(); i++) walk(group.getChildAt(i), current);
        }
    }

    private String moduleOf(Object fragment) {
        String module = modules.get(fragment);
        if (module == null) {
            try {
                module = String.valueOf(fragment.getClass().getMethod("getModuleName").invoke(fragment));
            } catch (Throwable t) {
                module = "";
            }
            modules.put(fragment, module);
        }
        return module;
    }

    /** The screen's main list: its largest RecyclerView on screen. */
    private View largestList(View root) {
        View[] best = {null};
        int[] bestArea = {0};
        findLargestList(root, best, bestArea);
        return best[0];
    }

    private void findLargestList(View view, View[] best, int[] bestArea) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (isRecyclerView(view)) {
            if (view.getGlobalVisibleRect(rect)) {
                int area = rect.width() * rect.height();
                if (area > bestArea[0]) {
                    bestArea[0] = area;
                    best[0] = view;
                }
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) findLargestList(group.getChildAt(i), best, bestArea);
        }
    }

    private static boolean isRecyclerView(View view) {
        Class<?> type = view.getClass();
        Boolean known = recyclerClasses.get(type);
        if (known != null) return known;
        boolean result = false;
        for (Class<?> c = type; c != null && c != View.class; c = c.getSuperclass()) {
            if (RECYCLER_VIEW.equals(c.getName())) {
                result = true;
                break;
            }
        }
        recyclerClasses.put(type, result);
        return result;
    }

    private boolean isOnScreen(View list) {
        return list.isAttachedToWindow() && list.isShown()
                && list.getGlobalVisibleRect(rect)
                && rect.height() >= list.getHeight() / 3
                && rect.width() >= list.getWidth() / 2;
    }

    private boolean contains(View list) {
        for (WeakReference<View> ref : onScreen) {
            if (ref.get() == list) return true;
        }
        return false;
    }

    private boolean hasOnScreen(Kind kind) {
        for (WeakReference<View> ref : onScreen) {
            View list = ref.get();
            if (list != null && kinds.get(list) == kind) return true;
        }
        return false;
    }

    /** The biggest gated list on screen, which the gate covers. */
    private View primary() {
        View best = null;
        int bestArea = 0;
        for (WeakReference<View> ref : onScreen) {
            View list = ref.get();
            if (list == null || !isOnScreen(list)) continue;
            int area = rect.width() * rect.height();
            if (area > bestArea) {
                bestArea = area;
                best = list;
            }
        }
        return best;
    }

    // endregion

    // region Frost

    private void applyAll(float level) {
        for (WeakReference<View> ref : onScreen) {
            View list = ref.get();
            if (list != null && !applied.containsKey(list)) applied.put(list, 0f);
        }
        for (View list : new ArrayList<>(applied.keySet())) {
            if (list == null) continue;
            Kind kind = kinds.get(list);
            applyLevel(list, kind != null && isEnabled(kind) ? level : 0f);
        }
    }

    private void applyLevel(View list, float level) {
        Float previous = applied.get(list);
        if (previous != null && Math.abs(previous - level) < 0.005f && (level > 0f || previous == 0f)) {
            syncTintBounds(list);
            return;
        }
        boolean blur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
        if (blur) {
            float radius = level * MAX_BLUR_DP * density;
            list.setRenderEffect(radius < 0.5f ? null
                    : RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
        }
        ColorDrawable tint = tints.get(list);
        if (tint == null && level > 0f) {
            tint = new ColorDrawable();
            tints.put(list, tint);
            list.getOverlay().add(tint);
        }
        if (tint != null) {
            tint.setColor(isDark() ? Color.BLACK : Color.WHITE);
            float maxTint = blur ? MAX_TINT_WITH_BLUR : MAX_TINT_WITHOUT_BLUR;
            tint.setAlpha(Math.round(level * maxTint * 255));
            syncTintBounds(list);
        }
        applied.put(list, level);
    }

    private void syncTintBounds(View list) {
        ColorDrawable tint = tints.get(list);
        if (tint != null) tint.setBounds(0, 0, list.getWidth(), list.getHeight());
    }

    private boolean isDark() {
        int mode = activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    // endregion

    // region Gate

    private void showGate() {
        if (gate == null) {
            ViewGroup content = activity.findViewById(android.R.id.content);
            if (content == null) return;
            gate = new GateView(activity, isDark());
            content.addView(gate, new FrameLayout.LayoutParams(0, 0));
        }
        gate.setBadge(ConsciousGatePatch.badge(reason));
        boolean closed = reason != Reason.OPEN;
        gate.setClosed(closed,
                closed ? ConsciousGatePatch.title(reason) : null,
                closed ? ConsciousGatePatch.body(reason) : null,
                ConsciousGatePatch.canTakeExtra(),
                ConsciousGatePatch.EXTRA_MINUTES.get(),
                ConsciousGatePatch.EXTRA_WAIT.get());
        syncGate();
    }

    private void hideGate() {
        if (gate == null) return;
        ViewGroup content = (ViewGroup) gate.getParent();
        if (content != null) content.removeView(gate);
        gate = null;
    }

    /** Keeps the gate over the visible part of the gated list, clear of the bars around it. */
    private void syncGate() {
        try {
            if (gate == null || !(gate.getParent() instanceof ViewGroup)) return;
            View list = primary();
            if (list == null) {
                gate.hide();
                return;
            }
            list.getGlobalVisibleRect(rect);
            // The lists scroll under their screen's header; keep the header usable.
            View header = header(kinds.get(list));
            if (header != null && header.isShown() && header.getGlobalVisibleRect(otherRect)
                    && otherRect.top <= rect.top && otherRect.bottom > rect.top) {
                rect.top = Math.min(rect.bottom, otherRect.bottom);
            }
            View bar = tabBar();
            if (bar != null && bar.isShown() && bar.getGlobalVisibleRect(otherRect) && otherRect.top < rect.bottom) {
                rect.bottom = Math.max(rect.top, otherRect.top);
            }
            ViewGroup content = (ViewGroup) gate.getParent();
            content.getGlobalVisibleRect(otherRect);
            rect.offset(-otherRect.left, -otherRect.top);

            gate.setList(list);
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) gate.getLayoutParams();
            if (params.leftMargin != rect.left || params.topMargin != rect.top
                    || params.width != rect.width() || params.height != rect.height()) {
                params.leftMargin = rect.left;
                params.topMargin = rect.top;
                params.width = rect.width();
                params.height = rect.height();
                gate.setLayoutParams(params);
            }
            gate.show();
        } catch (Throwable t) {
            Log.e(ConsciousGatePatch.TAG, "Failed to place gate", t);
        }
    }

    private View tabBar() {
        if (tabBar == null || !tabBar.isAttachedToWindow()) tabBar = findView("tab_bar");
        return tabBar;
    }

    /**
     * The header over a screen's list: "For you" and its buttons on the home feed, the search
     * bar on Explore, the Reels and Friends tabs on Reels.
     */
    private View header(Kind kind) {
        if (kind == null) return null;
        View header = headers.get(kind);
        if (header != null && header.isAttachedToWindow() && header.isShown()) return header;
        for (String name : HEADER_IDS[kind.ordinal()]) {
            header = findShownView(name);
            if (header != null) break;
        }
        headers.put(kind, header);
        return header;
    }

    /** The shown view with this id; screens on other tabs can have one too. */
    private View findShownView(String name) {
        int id = findId(name);
        if (id == 0) return null;
        View first = activity.findViewById(id);
        if (first != null && first.isShown() && first.getGlobalVisibleRect(otherRect)) return first;
        return findShownView(activity.getWindow().getDecorView(), id);
    }

    private View findShownView(View view, int id) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view.getId() == id && view.getGlobalVisibleRect(otherRect)) return view;
        if (view instanceof ViewGroup && !contains(view)) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findShownView(group.getChildAt(i), id);
                if (found != null) return found;
            }
        }
        return null;
    }

    private View findView(String name) {
        int id = findId(name);
        return id == 0 ? null : activity.findViewById(id);
    }

    private int findId(String name) {
        Resources res = activity.getResources();
        // Resources keep the original package name even if the app was cloned/renamed.
        int id = res.getIdentifier(name, "id", "com.instagram.android");
        if (id == 0) id = res.getIdentifier(name, "id", activity.getPackageName());
        return id;
    }

    // endregion

    // region Reels

    /**
     * Instagram keeps a reel playing while its screen is open, even behind the gate, so a
     * closed gate leaves Reels instead: from the Reels tab to the home tab, otherwise back to
     * the screen the reel was opened from.
     */
    /** The home button of the tab bar, found by its content description. */
    private View findHomeTab() {
        View bar = tabBar();
        if (!(bar instanceof ViewGroup)) return null;
        ArrayList<View> found = new ArrayList<>();
        bar.findViewsWithText(found, "Home", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        for (View view : found) {
            if (view.isClickable()) return view;
        }
        return found.isEmpty() ? null : found.get(0);
    }

    private void leaveReels() {
        View primary = primary();
        if (primary == null || kinds.get(primary) != Kind.REELS) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastLeave < 1500) return;
        lastLeave = now;
        if (now - lastLeaveToast > 10_000) {
            lastLeaveToast = now;
            Toast.makeText(activity, "Reels are closed \u00B7 Conscious gate", Toast.LENGTH_SHORT).show();
        }
        boolean reelsTab = "clips_viewer_clips_tab".equals(listModules.get(primary));
        primary.post(() -> {
            if (activity.isFinishing()) return;
            View home = reelsTab ? findHomeTab() : null;
            if (home != null) home.performClick();
            else activity.onBackPressed();
        });
    }

    // endregion
}
