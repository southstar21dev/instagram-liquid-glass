package app.southstar21.extension.instagram.consciousgate;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

import app.southstar21.extension.instagram.settings.Icons;

/**
 * Covers a gated screen's list from the moment it starts to frost.
 * <ul>
 *   <li>While frosting, taps are blocked so nothing can be opened or interacted with, but drags
 *       are passed straight to the list's own scrolling, so it still scrolls. A badge says the
 *       gate is active and how much time is left.</li>
 *   <li>Once closed, every touch is swallowed and a card says why, with an optional button
 *       for extra minutes that counts down before it can be used.</li>
 * </ul>
 */
@SuppressLint("ViewConstructor")
final class GateView extends FrameLayout {
    private static final int ACCENT = 0xFF0095F6;
    private static final int ICON_COLOR = 0xFF30B0C7;
    private static final long FADE_MS = 350;

    private final float density;
    private final TextView badgeLabel;
    private final View card;
    private final TextView title;
    private final TextView body;
    private final TextView extra;
    private final int[] ownLocation = new int[2];
    private final int[] listLocation = new int[2];
    private WeakReference<View> list = new WeakReference<>(null);
    private boolean shown;
    private boolean closed;
    private boolean extraAvailable;
    private int extraMinutes;
    /** Seconds until the extra time button can be used. */
    private int countdown;
    private int waitSeconds;
    private final Runnable countdownTick = this::countdownTick;

    GateView(Context context, boolean dark) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        int text = dark ? 0xFFF5F5F5 : 0xFF111111;
        int secondary = dark ? 0xB3FFFFFF : 0x99000000;
        int surface = dark ? 0xE61C1C1E : 0xEBFFFFFF;
        int hairline = dark ? 0x26FFFFFF : 0x14000000;

        setClickable(true);
        setFocusable(true);
        setAlpha(0f);
        setVisibility(GONE);

        LinearLayout badge = new LinearLayout(context);
        badge.setOrientation(LinearLayout.HORIZONTAL);
        badge.setGravity(Gravity.CENTER_VERTICAL);
        badge.setPadding(dp(8), dp(6), dp(14), dp(6));
        badge.setBackground(rounded(surface, dp(18), hairline));
        badge.setElevation(dp(6));
        View badgeIcon = new View(context);
        badgeIcon.setBackground(Icons.tile(Icons.Glyph.HOURGLASS, ICON_COLOR, density));
        badge.addView(badgeIcon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        badgeLabel = new TextView(context);
        badgeLabel.setTextColor(text);
        badgeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        badgeLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        badgeLabel.setSingleLine(true);
        LinearLayout.LayoutParams labelParams = wrap();
        labelParams.leftMargin = dp(8);
        badge.addView(badgeLabel, labelParams);
        LayoutParams badgeParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        badgeParams.topMargin = dp(12);
        addView(badge, badgeParams);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(24);
        card.setPadding(pad, dp(28), pad, dp(20));
        card.setBackground(rounded(surface, dp(28), hairline));
        card.setElevation(dp(12));
        card.setVisibility(GONE);

        View icon = new View(context);
        icon.setBackground(Icons.tile(Icons.Glyph.HOURGLASS, ICON_COLOR, density));
        card.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));

        title = new TextView(context);
        title.setTextColor(text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = wrap();
        titleParams.topMargin = dp(16);
        card.addView(title, titleParams);

        body = new TextView(context);
        body.setTextColor(secondary);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        body.setGravity(Gravity.CENTER);
        body.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams bodyParams = wrap();
        bodyParams.topMargin = dp(8);
        card.addView(body, bodyParams);

        extra = new TextView(context);
        extra.setTextColor(0xFFFFFFFF);
        extra.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        extra.setTypeface(Typeface.DEFAULT_BOLD);
        extra.setGravity(Gravity.CENTER);
        extra.setPadding(dp(20), dp(12), dp(20), dp(12));
        extra.setBackground(rounded(ACCENT, dp(24), 0));
        extra.setOnClickListener(v -> {
            if (countdown <= 0) ConsciousGatePatch.takeExtra();
        });
        LinearLayout.LayoutParams extraParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        extraParams.topMargin = dp(20);
        card.addView(extra, extraParams);

        this.card = card;
        addView(card, new LayoutParams(dp(300), LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    /** The list under the gate, which drags are passed to while the gate is open. */
    void setList(View list) {
        if (this.list.get() != list) this.list = new WeakReference<>(list);
    }

    void setBadge(String text) {
        if (!TextUtils.equals(badgeLabel.getText(), text)) badgeLabel.setText(text);
    }

    /**
     * Closes the gate (card, no touches) or keeps it open (badge, scrolling only).
     *
     * @param waitSeconds how long the extra time button counts down before it can be used.
     */
    void setClosed(boolean closed, String titleText, String bodyText,
                   boolean extraAvailable, int extraMinutes, int waitSeconds) {
        if (closed) {
            if (!TextUtils.equals(title.getText(), titleText)) title.setText(titleText);
            if (!TextUtils.equals(body.getText(), bodyText)) body.setText(bodyText);
        }
        this.waitSeconds = waitSeconds;
        if (this.extraAvailable != extraAvailable || this.extraMinutes != extraMinutes) {
            this.extraAvailable = extraAvailable;
            this.extraMinutes = extraMinutes;
            extra.setVisibility(extraAvailable ? VISIBLE : GONE);
            updateExtraLabel();
        }
        if (this.closed == closed) return;
        this.closed = closed;
        card.animate().cancel();
        if (closed) {
            card.setVisibility(VISIBLE);
            card.setAlpha(0f);
            card.setTranslationY(dp(12));
            card.animate().alpha(1f).translationY(0f).setDuration(FADE_MS).start();
            startCountdown();
        } else {
            card.setVisibility(GONE);
            removeCallbacks(countdownTick);
        }
    }

    // region Extra time countdown

    private void startCountdown() {
        removeCallbacks(countdownTick);
        countdown = waitSeconds;
        updateExtraLabel();
        if (countdown > 0) postDelayed(countdownTick, 1000);
    }

    private void countdownTick() {
        countdown--;
        updateExtraLabel();
        if (countdown > 0) postDelayed(countdownTick, 1000);
    }

    private void updateExtraLabel() {
        boolean waiting = countdown > 0;
        extra.setText(waiting
                ? "Wait " + countdown + " s"
                : extraMinutes + " more minute" + (extraMinutes == 1 ? "" : "s"));
        extra.setAlpha(waiting ? 0.45f : 1f);
        extra.setEnabled(!waiting);
    }

    // endregion

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        // Closed: only the card's button works, the rest is swallowed by this clickable view.
        if (closed) return super.dispatchTouchEvent(event);

        // Open: hand the gesture to the RecyclerView's own touch handling, which scrolls it
        // but never reaches its items, so nothing can be tapped, liked or opened.
        View target = list.get();
        if (target != null && target.isAttachedToWindow()) {
            getLocationOnScreen(ownLocation);
            target.getLocationOnScreen(listLocation);
            MotionEvent copy = MotionEvent.obtain(event);
            try {
                copy.offsetLocation(ownLocation[0] - listLocation[0], ownLocation[1] - listLocation[1]);
                target.onTouchEvent(copy);
            } finally {
                copy.recycle();
            }
        }
        return true;
    }

    void show() {
        if (shown) return;
        shown = true;
        setVisibility(VISIBLE);
        animate().cancel();
        animate().alpha(1f).setDuration(FADE_MS).start();
        // Coming back to the gate starts the wait again.
        if (closed) startCountdown();
    }

    void hide() {
        if (!shown) return;
        shown = false;
        animate().cancel();
        setAlpha(0f);
        setVisibility(GONE);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(countdownTick);
    }

    private GradientDrawable rounded(int color, float radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (stroke != 0) drawable.setStroke(Math.max(1, dp(0.5f)), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return Math.round(value * density);
    }
}
