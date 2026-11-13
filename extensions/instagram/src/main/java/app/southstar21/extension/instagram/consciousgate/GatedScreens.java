package app.southstar21.extension.instagram.consciousgate;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.EnumMap;
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
 * "feed_timeline" for the home feed, "clips_viewer_*" for Reels, "reel_*" for stories,
 * "explore_popular" for Explore, "serp_*" for search results ("serp_users" is the Accounts tab,
 * which stays open) and "feed_contextual_*" for post feeds. Each gated screen's surface (its
 * largest list, or the whole screen for stories) is frosted with a blur on Android 12+ plus a
 * tint, and covered by the {@link GateView}, which is kept over the surface every frame.
 */
@SuppressLint("NewApi")
final class GatedScreens {
    enum Kind {
        HOME, REELS, EXPLORE, STORIES, FEEDS
    }

    private static final String RECYCLER_VIEW = "androidx.recyclerview.widget.RecyclerView";
    /** While the screens are frosted or closed, a newly shown screen is picked up this fast. */
    private static final long ACTIVE_RESCAN_MS = 150;
    /** Without layout or scroll changes, the screen is still walked this often. */
    private static final long IDLE_RESCAN_MS = 1000;
    private static final float MAX_BLUR_DP = 30;
    /** Tint at full frost, with and without blur. */
    private static final float MAX_TINT_WITH_BLUR = 0.35f;
    private static final float MAX_TINT_WITHOUT_BLUR = 0.9f;
    private static final long THAW_MS = 700;

    /** Headers over each kind's surface, which stay usable, by view id. */
    private static final String[][] HEADER_IDS = {
            {"main_feed_action_bar_container", "main_feed_action_bar"},
            {"clips_viewer_action_bar"},
            {"explore_action_bar"},
            {},
            {"action_bar_container"},
    };

    private static final Map<Class<?>, Boolean> recyclerClasses = new HashMap<>();

    private final Activity activity;
    private final float density;
    private final int fragmentTagId;
    private final WeakHashMap<Object, String> modules = new WeakHashMap<>();

    /** Surfaces of the gated screens on screen right now, and what each one is. */
    private final List<WeakReference<View>> onScreen = new ArrayList<>();
    private final WeakHashMap<View, Kind> kinds = new WeakHashMap<>();
    private final WeakHashMap<View, Object> fragments = new WeakHashMap<>();
    /** Frost level and tint of every surface frosted so far. */
    private final WeakHashMap<View, Float> applied = new WeakHashMap<>();
    private final WeakHashMap<View, ColorDrawable> tints = new WeakHashMap<>();
    /** Reels and stories screens paused behind a closed gate: when last, and how often. */
    private final WeakHashMap<Object, long[]> paused = new WeakHashMap<>();
    private static final long REPAUSE_MS = 1000;
    private static final int MAX_PAUSES = 8;
    private final Map<Kind, View> headers = new EnumMap<>(Kind.class);

    private final Rect rect = new Rect();
    private final Rect otherRect = new Rect();
    private float level;
    private Reason reason = Reason.OPEN;
    private long lastScan;
    private long lastWalk;
    /** Set when the view tree changes, so the next scan walks it again. */
    private boolean dirty = true;
    private View tabBar;

    private GateView gate;
    private ValueAnimator thawAnimator;

    GatedScreens(Activity activity) {
        this.activity = activity;
        this.density = activity.getResources().getDisplayMetrics().density;
        this.fragmentTagId = findId("fragment_container_view_tag");
        View decor = activity.getWindow().getDecorView();
        ViewTreeObserver observer = decor.getViewTreeObserver();
        observer.addOnGlobalLayoutListener(() -> dirty = true);
        observer.addOnScrollChangedListener(() -> dirty = true);
        observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
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
        resumeMedia();
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
        if (reason != Reason.OPEN) pauseMedia();
        else resumeMedia();
    }

    // region Finding the screens

    static Kind kindOf(String module) {
        if (module == null || module.isEmpty()) return null;
        if (module.equals("feed_timeline")) return Kind.HOME;
        if (module.startsWith("clips_viewer")) return Kind.REELS;
        if (module.startsWith("reel_")) return Kind.STORIES;
        if (module.startsWith("explore")) return Kind.EXPLORE;
        // Search results, everything but the Accounts tab, and posts opened from them.
        if (module.startsWith("serp_") && !module.startsWith("serp_users")) return Kind.EXPLORE;
        if (module.equals("feed_contextual_chain") || module.equals("feed_contextual_keyword")) return Kind.EXPLORE;
        // Your own posts and saved posts stay open.
        if (module.equals("feed_contextual_self_profile") || module.contains("saved")) return null;
        if (module.startsWith("feed_contextual") || module.startsWith("feed_location")
                || module.startsWith("feed_hashtag")) {
            return Kind.FEEDS;
        }
        return null;
    }

    private static boolean isEnabled(Kind kind) {
        switch (kind) {
            case HOME:
                return ConsciousGatePatch.GATE_HOME.get();
            case REELS:
                return ConsciousGatePatch.GATE_REELS.get();
            case STORIES:
                return ConsciousGatePatch.GATE_STORIES.get();
            case FEEDS:
                return ConsciousGatePatch.GATE_FEEDS.get();
            default:
                return ConsciousGatePatch.GATE_EXPLORE.get();
        }
    }

    /**
     * Finds the gated surfaces on screen. The whole view tree is only walked again after it
     * changed, or once a second; otherwise the surfaces found last time are checked again.
     */
    private void scan() {
        long now = SystemClock.uptimeMillis();
        lastScan = now;
        if (fragmentTagId == 0) return;
        if (!dirty && now - lastWalk < IDLE_RESCAN_MS) {
            for (int i = onScreen.size() - 1; i >= 0; i--) {
                View surface = onScreen.get(i).get();
                Kind kind = surface == null ? null : kinds.get(surface);
                if (surface == null || kind == null || !isEnabled(kind) || !isOnScreen(surface)) onScreen.remove(i);
            }
            return;
        }
        dirty = false;
        lastWalk = now;
        onScreen.clear();
        walk(activity.getWindow().getDecorView(), null);
    }

    private void walk(View view, Object parentFragment) {
        if (view.getVisibility() != View.VISIBLE) return;
        Object fragment = view.getTag(fragmentTagId);
        if (fragment != null && fragment != parentFragment) {
            Kind kind = kindOf(moduleOf(fragment));
            if (kind != null && isEnabled(kind)) {
                View surface = surfaceOf(view, kind);
                if (surface != null && isOnScreen(surface) && !contains(surface)) {
                    onScreen.add(new WeakReference<>(surface));
                    kinds.put(surface, kind);
                    fragments.put(surface, fragment);
                }
            }
        }
        // Instagram's tabs and pagers are RecyclerViews too, so only the gated surfaces
        // themselves are skipped; their items can't hold another screen.
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

    /** What gets frosted and covered: the screen's largest list, or for stories the whole screen. */
    private View surfaceOf(View root, Kind kind) {
        if (kind == Kind.STORIES) return root;
        View[] best = {null};
        int[] bestArea = {0};
        findLargestList(root, root.getTag(fragmentTagId), best, bestArea);
        return best[0];
    }

    /** Searches this screen only: screens nested in it, like search results in Explore, have their own lists. */
    private void findLargestList(View view, Object fragment, View[] best, int[] bestArea) {
        if (view.getVisibility() != View.VISIBLE) return;
        Object tag = view.getTag(fragmentTagId);
        if (tag != null && tag != fragment) return;
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
            for (int i = 0; i < group.getChildCount(); i++) {
                findLargestList(group.getChildAt(i), fragment, best, bestArea);
            }
        }
    }

    static boolean isRecyclerView(View view) {
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

    private boolean isOnScreen(View surface) {
        return surface.isAttachedToWindow() && surface.isShown()
                && surface.getGlobalVisibleRect(rect)
                && rect.height() >= surface.getHeight() / 3
                && rect.width() >= surface.getWidth() / 2;
    }

    private boolean contains(View surface) {
        for (WeakReference<View> ref : onScreen) {
            if (ref.get() == surface) return true;
        }
        return false;
    }

    /** The biggest gated surface on screen, which the gate covers. */
    private View primary() {
        View best = null;
        int bestArea = 0;
        for (WeakReference<View> ref : onScreen) {
            View surface = ref.get();
            if (surface == null || !isOnScreen(surface)) continue;
            int area = rect.width() * rect.height();
            if (area > bestArea) {
                bestArea = area;
                best = surface;
            }
        }
        return best;
    }

    // endregion

    // region Frost

    private void applyAll(float level) {
        for (WeakReference<View> ref : onScreen) {
            View surface = ref.get();
            if (surface != null && !applied.containsKey(surface)) applied.put(surface, 0f);
        }
        for (View surface : new ArrayList<>(applied.keySet())) {
            if (surface == null) continue;
            Kind kind = kinds.get(surface);
            applyLevel(surface, kind != null && isEnabled(kind) ? level : 0f);
        }
    }

    private void applyLevel(View surface, float level) {
        Float previous = applied.get(surface);
        if (previous != null && Math.abs(previous - level) < 0.005f && (level > 0f || previous == 0f)) {
            syncTintBounds(surface);
            return;
        }
        boolean blur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
        if (blur) {
            float radius = level * MAX_BLUR_DP * density;
            surface.setRenderEffect(radius < 0.5f ? null
                    : RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
        }
        ColorDrawable tint = tints.get(surface);
        if (tint == null && level > 0f) {
            tint = new ColorDrawable();
            tints.put(surface, tint);
            surface.getOverlay().add(tint);
        }
        if (tint != null) {
            tint.setColor(isDark() ? Color.BLACK : Color.WHITE);
            float maxTint = blur ? MAX_TINT_WITH_BLUR : MAX_TINT_WITHOUT_BLUR;
            tint.setAlpha(Math.round(level * maxTint * 255));
            syncTintBounds(surface);
        }
        applied.put(surface, level);
    }

    private void syncTintBounds(View surface) {
        ColorDrawable tint = tints.get(surface);
        if (tint != null) tint.setBounds(0, 0, surface.getWidth(), surface.getHeight());
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
        View primary = primary();
        Kind kind = primary == null ? null : kinds.get(primary);
        gate.setBadge(ConsciousGatePatch.badge(reason));
        boolean closed = reason != Reason.OPEN;
        gate.setClosed(closed,
                closed ? ConsciousGatePatch.title(reason, kind) : null,
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

    /** Keeps the gate over the visible part of the gated surface, clear of the bars around it. */
    private void syncGate() {
        try {
            if (gate == null || !(gate.getParent() instanceof ViewGroup)) return;
            View surface = primary();
            if (surface == null) {
                gate.hide();
                return;
            }
            surface.getGlobalVisibleRect(rect);
            // Lists scroll under their screen's header; keep the header usable.
            View header = header(kinds.get(surface));
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

            gate.setSurface(surface, isRecyclerView(surface));
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
     * The header over a screen's surface: "For you" and its buttons on the home feed, the
     * search bar on Explore, the Reels and Friends tabs on Reels, the title of post feeds.
     */
    private View header(Kind kind) {
        if (kind == null) return null;
        View header = headers.get(kind);
        if (header != null && header.isAttachedToWindow() && header.isShown()) return header;
        header = null;
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

    // region Playback

    /**
     * Instagram keeps reels and stories playing behind the gate, so a closed gate pauses their
     * screen the way leaving it would, through the fragment's own onPause(). Instagram can
     * start playback again right after, for example once a tab has finished switching in, so
     * the pause is repeated a few times while audio is still playing. Playback starts again on
     * the next swipe or tap once the gate opens.
     */
    private void pauseMedia() {
        long now = SystemClock.uptimeMillis();
        AudioManager audio = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        for (WeakReference<View> ref : onScreen) {
            View surface = ref.get();
            if (surface == null) continue;
            Kind kind = kinds.get(surface);
            if (kind != Kind.REELS && kind != Kind.STORIES) continue;
            Object fragment = fragments.get(surface);
            if (fragment == null) continue;
            long[] state = paused.get(fragment);
            if (state != null) {
                boolean playing = audio != null && audio.isMusicActive();
                if (!playing || now - state[0] < REPAUSE_MS || state[1] >= MAX_PAUSES) continue;
            } else {
                state = new long[2];
                paused.put(fragment, state);
            }
            state[0] = now;
            state[1]++;
            try {
                fragment.getClass().getMethod("onPause").invoke(fragment);
            } catch (Throwable t) {
                Log.e(ConsciousGatePatch.TAG, "Failed to pause " + moduleOf(fragment), t);
            }
        }
    }

    private void resumeMedia() {
        if (paused.isEmpty()) return;
        for (Object fragment : new ArrayList<>(paused.keySet())) {
            if (fragment == null) continue;
            try {
                fragment.getClass().getMethod("onResume").invoke(fragment);
            } catch (Throwable t) {
                Log.e(ConsciousGatePatch.TAG, "Failed to resume " + moduleOf(fragment), t);
            }
        }
        paused.clear();
    }

    // endregion
}
