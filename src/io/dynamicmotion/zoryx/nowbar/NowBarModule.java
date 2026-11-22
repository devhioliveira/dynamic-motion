package io.dynamicmotion.zoryx.nowbar;

import android.app.KeyguardManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class NowBarModule implements IXposedHookLoadPackage {
    static final String SYSTEM_UI = "com.android.systemui";
    static final String PACKAGE = "io.dynamicmotion.zoryx";
    private static final String ONGOING = SYSTEM_UI + ".statusbar.phone.ongoingactivity.";
    private static final String TAG = "[Dynamic Motion Native] ";

    private final List<WeakReference<View>> candidates = new ArrayList<>();
    private WeakReference<View> statusBar = new WeakReference<>(null);
    private WeakReference<View> hidden = new WeakReference<>(null);
    private Handler main;
    private Class<?> dataHelper;
    private IslandWindow island;
    private KeyguardManager keyguard;
    private PowerManager power;
    private Object statusState;
    private int originalVisibility;
    private boolean scheduled;
    private boolean rendererFailed;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam pkg) {
        if (!SYSTEM_UI.equals(pkg.packageName) || !SYSTEM_UI.equals(pkg.processName)
                || Build.VERSION.SDK_INT < 35 || !Build.MANUFACTURER.equalsIgnoreCase("samsung")) {
            return;
        }
        ClassLoader loader = pkg.classLoader;
        dataHelper = XposedHelpers.findClassIfExists(ONGOING + "OngoingActivityDataHelper", loader);
        if (dataHelper == null) {
            XposedBridge.log(TAG + "Samsung ongoing activity data source unavailable");
            return;
        }

        String controller = ONGOING + "OngoingActivityController";
        after(loader, controller, "updateParentViewVisibility", param -> {
            Object root = OngoingActivity.field(param.thisObject, "mParentView");
            if (root instanceof View) {
                remember((View) root);
            }
        });
        after(loader, ONGOING + "ChipAnimationController", "animateChipShow", this::viewArgument);
        after(loader, SYSTEM_UI + ".statusbar.pipeline.shared.ui.binder.HomeStatusBarViewBinderImpl",
                "adjustVisibility", this::viewArgument);

        for (String method : new String[]{"onAsyncInflationFinished", "OaDataListChanged", "add$1",
                "update", "update$1$1", "remove", "updateMediaChipData", "removeOngoingActivityData"}) {
            after(loader, controller, method, param -> schedule());
        }
        for (String method : new String[]{"removeOngoingActivityByKey", "updateOngoingList",
                "notifyUpdateItemNowbarObservers", "notifyRemoveItemNowbarObservers",
                "notifyRankingStateChanged"}) {
            after(loader, dataHelper.getName(), method, param -> schedule());
        }

        String barController = SYSTEM_UI + ".statusbar.phone.PhoneStatusBarViewController";
        after(loader, barController, "onViewAttached", param -> {
            Object root = OngoingActivity.field(param.thisObject, "mView");
            if (root instanceof View) {
                refreshFromStatusBar((View) root);
            }
        });
        after(loader, barController, "onViewDetached", param -> {
            if (OngoingActivity.field(param.thisObject, "mView") == statusBar.get()) {
                statusBar.clear();
                schedule();
            }
        });
        String stateController = SYSTEM_UI + ".statusbar.StatusBarStateControllerImpl";
        after(loader, stateController, "setState", param -> {
            statusState = param.thisObject;
            schedule();
        });
        after(loader, stateController, "setDozing", param -> {
            statusState = param.thisObject;
            schedule();
        });
        XposedBridge.log(TAG + "Samsung SystemUI hooks registered");
    }

    private void refreshFromStatusBar(View root) {
        statusBar = new WeakReference<>(root);
        remember(root);
    }

    private void viewArgument(XC_MethodHook.MethodHookParam param) {
        if (param.args.length != 0 && param.args[0] instanceof View) {
            remember((View) param.args[0]);
        }
    }

    private void remember(View root) {
        if (rendererFailed) {
            return;
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main().post(() -> remember(root));
            return;
        }
        int capsuleId = resourceId(root, "ongoing_activity_capsule");
        int containerId = resourceId(root, "samsung_ongoing_activity_chip_container");
        View target = capsuleId == 0 ? null : root.findViewById(capsuleId);
        if (target == null && containerId != 0) {
            target = root.findViewById(containerId);
            if (target != null && containsOtherChips(target)) {
                target = null;
            }
        }
        if (target != null) {
            View current = target;
            candidates.removeIf(reference -> reference.get() == null || reference.get() == current);
            candidates.add(new WeakReference<>(current));
            if (island != null && island.isDrawingActivity() && current == hidden.get()) {
                hideNative(current);
            }
        }
        schedule();
    }

    private boolean containsOtherChips(View root) {
        for (String name : new String[]{"ongoing_call_chip", "samsung_ongoing_cast_chip"}) {
            int id = resourceId(root, name);
            if (id != 0 && root.findViewById(id) != null) {
                return true;
            }
        }
        return false;
    }

    private int resourceId(View view, String name) {
        return view.getResources().getIdentifier(name, "id", SYSTEM_UI);
    }

    private synchronized Handler main() {
        if (main == null) {
            main = new Handler(Looper.getMainLooper());
        }
        return main;
    }

    private void schedule() {
        if (rendererFailed) {
            return;
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main().post(this::schedule);
            return;
        }
        if (!scheduled) {
            scheduled = true;
            main().post(this::refresh);
        }
    }

    private void refresh() {
        scheduled = false;
        try {
            View root = statusBar.get();
            View chip = findCapsule(root);
            if (rendererFailed || chip == null || !canRender(chip)) {
                closeIsland();
                return;
            }
            OngoingActivity activity = OngoingActivity.readFirst(dataHelper);
            if (activity == null) {
                closeIsland();
                return;
            }
            if (island == null) {
                island = new IslandWindow(chip, this::onFrame, this::onRendererError);
            }
            island.show(activity, root != null ? root : chip);
            if (island.isDrawingActivity()) {
                hideNative(chip);
            }
        } catch (Throwable error) {
            onRendererError(error);
        }
    }

    private boolean canRender(View chip) {
        if (keyguard == null) {
            keyguard = chip.getContext().getSystemService(KeyguardManager.class);
            power = chip.getContext().getSystemService(PowerManager.class);
        }
        if (keyguard == null || keyguard.isKeyguardLocked() || power == null || !power.isInteractive()) {
            return false;
        }
        Object state = OngoingActivity.call(statusState, "getState");
        Object dozing = OngoingActivity.call(statusState, "isDozing");
        return (!(state instanceof Number) || ((Number) state).intValue() == 0)
                && !Boolean.TRUE.equals(dozing);
    }

    private View findCapsule(View bar) {
        candidates.removeIf(reference -> {
            View view = reference.get();
            return view == null || !view.isAttachedToWindow();
        });
        View fallback = null;
        for (WeakReference<View> reference : candidates) {
            View view = reference.get();
            if (view == null || (bar != null && !isInViewTree(view, bar))) {
                continue;
            }
            if (view.getVisibility() != View.VISIBLE && view != hidden.get()) {
                continue;
            }
            if (view.getId() == resourceId(view, "ongoing_activity_capsule")) {
                return view;
            }
            fallback = view;
        }
        return fallback;
    }

    private static boolean isInViewTree(View view, View root) {
        View child = view;
        while (child != null) {
            if (child == root) {
                return true;
            }
            child = child.getParent() instanceof ViewGroup ? (ViewGroup) child.getParent() : null;
        }
        return false;
    }

    private void onFrame() {
        if (island != null && island.isDrawingActivity()) {
            schedule();
        }
    }

    private void hideNative(View chip) {
        if (hidden.get() != chip) {
            restoreNative();
            originalVisibility = chip.getVisibility();
            hidden = new WeakReference<>(chip);
        }
        if (chip.getVisibility() != View.GONE) {
            chip.animate().cancel();
            chip.setVisibility(View.GONE);
        }
    }

    private void restoreNative() {
        View chip = hidden.get();
        hidden.clear();
        if (chip != null && chip.isAttachedToWindow() && chip.getVisibility() == View.GONE) {
            chip.setVisibility(originalVisibility);
        }
    }

    private void closeIsland() {
        restoreNative();
        IslandWindow previous = island;
        island = null;
        if (previous != null) {
            previous.close();
        }
    }

    private void onRendererError(Throwable error) {
        rendererFailed = true;
        XposedBridge.log(TAG + "Restoring the Samsung capsule: " + error);
        try {
            closeIsland();
        } catch (Throwable cleanupError) {
            XposedBridge.log(TAG + cleanupError);
        }
    }

    private void after(ClassLoader loader, String className, String method, After callback) {
        try {
            Class<?> type = XposedHelpers.findClassIfExists(className, loader);
            if (type != null) {
                XposedBridge.hookAllMethods(type, method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!param.hasThrowable()) {
                            callback.run(param);
                        }
                    }
                });
            }
        } catch (LinkageError | RuntimeException error) {
            XposedBridge.log(TAG + className + "." + method + ": " + error);
        }
    }

    private interface After {
        void run(XC_MethodHook.MethodHookParam param);
    }
}
