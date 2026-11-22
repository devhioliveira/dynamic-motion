package io.dynamicmotion.zoryx.nowbar;

import android.animation.ValueAnimator;
import android.app.PendingIntent;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.Chronometer;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.function.Consumer;

import l9.h;
import l9.r;

final class IslandWindow {
    private static final int TYPE_STATUS_BAR_SUB_PANEL = 2017;
    private static final int COMPACT_WIDTH = 126;
    private static final int COMPACT_HEIGHT = 37;
    private static final int EXPANDED_WIDTH = 292;
    private static final int EXPANDED_HEIGHT = 91;
    private static final int MARGIN = 3;

    private final FrameLayout content;
    private final ImageView icon;
    private final TextView title;
    private final TextView detail;
    private final Chronometer timer;
    private final WindowManager manager;
    private final WindowManager.LayoutParams params;
    private final FrameLayout root;
    private final r renderer;
    private final h geometry;
    private final DisplayMetrics rendererMetrics = new DisplayMetrics();
    private final float density;
    private final Consumer<Throwable> onError;
    private final Runnable onFrame;
    private View statusBar;
    private OngoingActivity activity;
    private ValueAnimator morph;
    private float expandedFraction;
    private boolean expanded;
    private boolean added;
    private boolean drawn;
    private boolean failed;
    private float downX;
    private float downY;

    IslandWindow(View anchor, Runnable onFrame, Consumer<Throwable> onError) {
        this.onFrame = onFrame;
        this.onError = onError;
        Display display = anchor.getDisplay();
        if (display == null) {
            throw new IllegalStateException("The status bar display is not attached");
        }
        Context context = anchor.getContext().createWindowContext(display, TYPE_STATUS_BAR_SUB_PANEL, null);
        manager = context.getSystemService(WindowManager.class);
        if (manager == null) {
            throw new IllegalStateException("SystemUI window manager is unavailable");
        }
        rendererMetrics.setTo(context.getResources().getDisplayMetrics());
        density = rendererMetrics.density;

        params = new WindowManager.LayoutParams(dp(COMPACT_WIDTH + 2 * MARGIN),
                dp(COMPACT_HEIGHT + 2 * MARGIN), TYPE_STATUS_BAR_SUB_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setFitInsetsTypes(0);
        params.windowAnimations = 0;
        params.setTitle("Dynamic Motion");

        root = new FrameLayout(context) {
            @Override
            protected void dispatchDraw(Canvas canvas) {
                if (failed) {
                    return;
                }
                int checkpoint = canvas.save();
                try {
                    super.dispatchDraw(canvas);
                    if (!drawn && !renderer.getPillPath().isEmpty() && activity != null) {
                        drawn = true;
                        post(IslandWindow.this.onFrame);
                    }
                } catch (Throwable error) {
                    fail(error);
                } finally {
                    canvas.restoreToCount(checkpoint);
                }
            }

            @Override
            public boolean dispatchTouchEvent(MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    downX = event.getX();
                    downY = event.getY();
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    if (Math.abs(event.getX() - downX) < dp(24)
                            && Math.abs(event.getY() - downY) < dp(24)) {
                        if (event.getEventTime() - event.getDownTime() > 500) {
                            openActivity();
                        } else {
                            toggleExpanded();
                        }
                    }
                }
                return true;
            }

            @Override
            protected void onConfigurationChanged(Configuration configuration) {
                super.onConfigurationChanged(configuration);
                post(IslandWindow.this.onFrame);
            }
        };

        renderer = new r(context);
        geometry = new h(context, density, rendererMetrics, null);
        renderer.l = geometry;
        renderer.h0(density, dp(MARGIN));
        root.addView(renderer, new FrameLayout.LayoutParams(-1, -1));

        content = new FrameLayout(context);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        title = label(context, true);
        detail = label(context, false);
        timer = new Chronometer(context);
        timer.setTextColor(0xFFD9D9DF);
        timer.setTextSize(11);
        timer.setGravity(Gravity.CENTER_VERTICAL);
        title.setTextColor(Color.WHITE);
        detail.setTextColor(0xFFD9D9DF);
        content.addView(icon, new FrameLayout.LayoutParams(dp(17), dp(17)));
        content.addView(title, new FrameLayout.LayoutParams(1, dp(22)));
        content.addView(detail, new FrameLayout.LayoutParams(1, dp(26)));
        content.addView(timer, new FrameLayout.LayoutParams(1, dp(21)));
    }

    void show(OngoingActivity next, View anchor) {
        statusBar = anchor;
        if (activity == null || !activity.key.equals(next.key)) {
            expanded = false;
            expandedFraction = 0;
            cancelMorph();
        }
        boolean iconChanged = activity == null || !activity.key.equals(next.key);
        activity = next;
        title.setText(next.title);
        detail.setText(next.detail);
        if (iconChanged) {
            Drawable image = null;
            try {
                image = next.icon == null ? null : next.icon.loadDrawable(root.getContext());
            } catch (RuntimeException ignored) {
                // A chip can outlive its source application or resources.
            }
            icon.setImageDrawable(image);
        }
        if (next.chronoBase != 0) {
            if (timer.getBase() != next.chronoBase || timer.isCountDown() != next.countDown) {
                timer.setCountDown(next.countDown);
                timer.setBase(next.chronoBase);
            }
            timer.start();
        } else {
            timer.stop();
        }
        updateGeometry();
        if (!added) {
            manager.addView(root, params);
            added = true;
        }
    }

    private void toggleExpanded() {
        if (failed || activity == null) {
            return;
        }
        expanded = !expanded;
        cancelMorph();
        morph = ValueAnimator.ofFloat(expandedFraction, expanded ? 1 : 0);
        morph.setDuration(expanded ? 340 : 270);
        morph.setInterpolator(new PathInterpolator(0.18f, 0.78f, 0.24f, 1));
        morph.addUpdateListener(value -> {
            try {
                expandedFraction = (float) value.getAnimatedValue();
                updateGeometry();
            } catch (Throwable error) {
                fail(error);
            }
        });
        morph.start();
    }

    private void updateGeometry() {
        Rect screen = manager.getMaximumWindowMetrics().getBounds();
        int maxWidth = Math.max(dp(32), Math.min(screen.width() - dp(8), dp(EXPANDED_WIDTH)));
        int width = Math.min(maxWidth, dp(COMPACT_WIDTH + (EXPANDED_WIDTH - COMPACT_WIDTH) * expandedFraction));
        int height = dp(COMPACT_HEIGHT + (EXPANDED_HEIGHT - COMPACT_HEIGHT) * expandedFraction);
        int windowWidth = width + 2 * dp(MARGIN);
        int windowHeight = height + 2 * dp(MARGIN);

        int center = screen.width() / 2;
        int centerY = dp(23);
        DisplayCutout cutout = cutout();
        if (cutout != null && !cutout.getBoundingRectTop().isEmpty()) {
            Rect camera = cutout.getBoundingRectTop();
            center = camera.centerX();
            centerY = camera.centerY();
        }
        int x = Math.max(0, Math.min(center - windowWidth / 2, screen.width() - windowWidth));
        int y = Math.max(0, centerY - dp(COMPACT_HEIGHT) / 2 - dp(MARGIN));

        boolean windowChanged = params.width != windowWidth || params.height != windowHeight
                || params.x != x || params.y != y;
        params.width = windowWidth;
        params.height = windowHeight;
        params.x = x;
        params.y = y;

        rendererMetrics.widthPixels = windowWidth;
        geometry.l = width;
        geometry.m = height;
        renderer.k0 = true;
        renderer.A();
        renderer.F.invalidate();
        renderer.invalidate();
        renderContent(width, height, cutout, x);
        if (added && windowChanged) {
            manager.updateViewLayout(root, params);
        }
    }

    private void renderContent(int width, int height, DisplayCutout cutout, int windowX) {
        int padding = dp(MARGIN + 10);
        int compactY = dp(MARGIN) + (height - dp(20)) / 2;
        int expandedY = dp(MARGIN + COMPACT_HEIGHT + 6);
        boolean detailsVisible = expandedFraction > 0.55f;
        Rect camera = cutout == null ? new Rect() : cutout.getBoundingRectTop();
        int iconX = padding;
        int textX = padding + dp(22);
        if (!detailsVisible && !camera.isEmpty()) {
            textX = Math.max(textX, camera.right - windowX + dp(5));
        }
        int textWidth = Math.max(dp(12), width + dp(MARGIN) - padding - textX);
        move(icon, iconX, compactY + dp(2), dp(16), dp(16));
        move(title, detailsVisible ? padding : textX, detailsVisible ? expandedY : compactY,
                detailsVisible ? width - 2 * dp(10) : textWidth, dp(20));
        boolean showTimer = activity.chronoBase != 0;
        title.setVisibility(!detailsVisible && showTimer ? View.INVISIBLE : View.VISIBLE);
        move(timer, detailsVisible ? padding : textX, detailsVisible ? expandedY + dp(20) : compactY,
                detailsVisible ? width - 2 * dp(10) : textWidth, dp(20));
        timer.setVisibility(showTimer ? View.VISIBLE : View.GONE);
        move(detail, padding, expandedY + dp(20), width - 2 * dp(10), dp(20));
        detail.setVisibility(detailsVisible && !showTimer && !TextUtils.isEmpty(activity.detail)
                ? View.VISIBLE : View.GONE);
        icon.setVisibility(detailsVisible || icon.getDrawable() == null ? View.INVISIBLE : View.VISIBLE);
    }

    private DisplayCutout cutout() {
        WindowInsets insets = statusBar == null ? null : statusBar.getRootWindowInsets();
        return insets == null ? null : insets.getDisplayCutout();
    }

    private TextView label(Context context, boolean medium) {
        TextView label = new TextView(context);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setTextSize(medium ? 12 : 11);
        try {
            Context source = context.createPackageContext(NowBarModule.PACKAGE, 0);
            label.setTypeface(Typeface.createFromAsset(source.getAssets(),
                    medium ? "fonts/Inter_18pt-Medium.ttf" : "fonts/Inter_18pt-Regular.ttf"));
        } catch (Exception ignored) {
            label.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        }
        return label;
    }

    private static void move(View view, int x, int y, int width, int height) {
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) view.getLayoutParams();
        if (p.leftMargin != x || p.topMargin != y || p.width != width || p.height != height) {
            p.leftMargin = x;
            p.topMargin = y;
            p.width = width;
            p.height = height;
            view.setLayoutParams(p);
        }
    }

    private void openActivity() {
        PendingIntent intent = activity == null ? null : activity.contentIntent;
        if (intent != null) {
            try {
                intent.send();
            } catch (PendingIntent.CanceledException | SecurityException ignored) {
                // The activity or its tap action can expire before the user taps.
            }
        }
    }

    private void cancelMorph() {
        if (morph != null) {
            morph.cancel();
            morph = null;
        }
    }

    boolean isDrawingActivity() {
        return added && drawn && !failed && root.isAttachedToWindow();
    }

    void close() {
        cancelMorph();
        timer.stop();
        if (added) {
            added = false;
            manager.removeViewImmediate(root);
        }
    }

    private void fail(Throwable error) {
        if (!failed) {
            failed = true;
            root.post(() -> onError.accept(error));
        }
    }

    private int dp(float value) {
        return Math.round(value * density);
    }
}
