package app.southstar21.extension.instagram.liquidglass;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Copies of the video surfaces playing under the bar.
 * <p>
 * Instagram plays feed videos in SurfaceViews, which the system composites as separate layers:
 * their pixels never pass through the view hierarchy, so when the glass re-records what's
 * behind it, a playing video is only a transparent hole there. Left alone the glass filled
 * the hole with its base color and turned solid, and the edge blur had nothing to blur.
 * <p>
 * So each frame, every SurfaceView under the bar is copied with {@link PixelCopy}, small (the
 * copy is blurred anyway), and {@link #draw} puts the latest copy into the hole: drawn
 * behind what's already recorded, so the video's own overlays (mute button, captions) stay on
 * top of it.
 */
@SuppressLint("NewApi")
final class SurfaceSnapshots {
    private static final long SCAN_MS = 400;
    /** Copies are this many times smaller than the surface on each side. */
    private static final int DOWNSCALE = 4;

    private final View bar;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Entry> entries = new ArrayList<>();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final int[] location = new int[2];
    private final int[] parentLocation = new int[2];
    private final Rect visible = new Rect();
    private final RectF bounds = new RectF();
    private final RectF clip = new RectF();
    private long lastScan;

    private static final class Entry {
        final WeakReference<SurfaceView> view;
        /** The latest finished copy, and the one being copied into. */
        Bitmap shown, spare;
        boolean inFlight;

        Entry(SurfaceView view) {
            this.view = new WeakReference<>(view);
        }
    }

    SurfaceSnapshots(View bar) {
        this.bar = bar;
        // Only fills what's still transparent: the holes the surfaces punched.
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OVER));
    }

    /**
     * Once a frame: refreshes the copies of the video surfaces that overlap {@code region}, in
     * the bar's parent's coordinates.
     */
    void update(RectF region) {
        if (!(bar.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) bar.getParent();
        long now = SystemClock.uptimeMillis();
        if (now - lastScan > SCAN_MS) {
            lastScan = now;
            rescan(parent);
        }
        if (entries.isEmpty()) return;
        parent.getLocationInWindow(parentLocation);
        for (Entry entry : entries) {
            SurfaceView view = entry.view.get();
            if (!usable(view) || !visibleBounds(view)) continue;
            if (!RectF.intersects(clip, region)) continue;
            request(entry, view);
        }
    }

    /**
     * Draws the latest copies into {@code canvas}, which is in the bar's parent's coordinates,
     * behind what it already holds. Call after recording the content, before the base color.
     */
    void draw(Canvas canvas) {
        if (entries.isEmpty() || !(bar.getParent() instanceof ViewGroup)) return;
        ((ViewGroup) bar.getParent()).getLocationInWindow(parentLocation);
        for (Entry entry : entries) {
            SurfaceView view = entry.view.get();
            if (entry.shown == null || !usable(view) || !visibleBounds(view)) continue;
            int save = canvas.save();
            canvas.clipRect(clip);
            canvas.drawBitmap(entry.shown, null, bounds, paint);
            canvas.restoreToCount(save);
        }
    }

    private static boolean usable(SurfaceView view) {
        return view != null && view.isAttachedToWindow() && view.getVisibility() == View.VISIBLE
                && view.getWidth() > 0 && view.getHeight() > 0
                && view.getHolder().getSurface() != null && view.getHolder().getSurface().isValid();
    }

    /** Sets {@link #bounds} (the whole surface) and {@link #clip} (its visible part), in the parent's coordinates. */
    private boolean visibleBounds(SurfaceView view) {
        view.getLocationInWindow(location);
        float left = location[0] - parentLocation[0];
        float top = location[1] - parentLocation[1];
        bounds.set(left, top, left + view.getWidth(), top + view.getHeight());
        if (!view.getGlobalVisibleRect(visible)) return false;
        View root = view.getRootView();
        root.getLocationInWindow(location);
        clip.set(visible.left + location[0] - parentLocation[0], visible.top + location[1] - parentLocation[1],
                visible.right + location[0] - parentLocation[0], visible.bottom + location[1] - parentLocation[1]);
        return !clip.isEmpty();
    }

    private void request(Entry entry, SurfaceView view) {
        if (entry.inFlight) return;
        int w = Math.max(1, view.getWidth() / DOWNSCALE);
        int h = Math.max(1, view.getHeight() / DOWNSCALE);
        if (entry.spare == null || entry.spare.getWidth() != w || entry.spare.getHeight() != h) {
            entry.spare = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        Bitmap target = entry.spare;
        entry.inFlight = true;
        try {
            // The whole surface, scaled into the small bitmap; drawn back over the view's bounds.
            PixelCopy.request(view, target, result -> {
                entry.inFlight = false;
                if (result != PixelCopy.SUCCESS) return;
                Bitmap previous = entry.shown;
                entry.shown = target;
                entry.spare = previous;
            }, handler);
        } catch (RuntimeException e) {
            // A surface torn down between the check and the copy.
            entry.inFlight = false;
        }
    }

    /** Finds the SurfaceViews among the content under the bar, keeping the copies of ones still there. */
    private void rescan(ViewGroup parent) {
        List<SurfaceView> found = new ArrayList<>();
        int barIndex = parent.indexOfChild(bar);
        for (int i = 0; i < barIndex; i++) collect(parent.getChildAt(i), found);
        List<Entry> kept = new ArrayList<>();
        for (SurfaceView view : found) {
            Entry existing = null;
            for (Entry entry : entries) {
                if (entry.view.get() == view) {
                    existing = entry;
                    break;
                }
            }
            kept.add(existing != null ? existing : new Entry(view));
        }
        entries.clear();
        entries.addAll(kept);
    }

    private static void collect(View view, List<SurfaceView> out) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof SurfaceView) {
            out.add((SurfaceView) view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out);
        }
    }
}
