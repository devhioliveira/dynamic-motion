package io.dynamicmotion.zoryx.nowbar;

import android.app.Notification;
import android.app.PendingIntent;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import java.util.List;

import de.robv.android.xposed.XposedHelpers;

final class OngoingActivity {
    final String key;
    final CharSequence title;
    final CharSequence detail;
    final Icon icon;
    final PendingIntent contentIntent;
    final long chronoBase;
    final boolean countDown;

    private OngoingActivity(String key, CharSequence title, CharSequence detail, Icon icon,
            PendingIntent contentIntent, long chronoBase, boolean countDown) {
        this.key = key;
        this.title = title;
        this.detail = detail;
        this.icon = icon;
        this.contentIntent = contentIntent;
        this.chronoBase = chronoBase;
        this.countDown = countDown;
    }

    static OngoingActivity readFirst(Class<?> helper) {
        Object items;
        try {
            items = XposedHelpers.getStaticObjectField(helper, "mOngoingActivityLists");
        } catch (NoSuchFieldError error) {
            return null;
        }
        if (!(items instanceof List<?>)) {
            return null;
        }
        for (Object entry : ((List<?>) items).toArray()) {
            OngoingActivity activity = from(entry);
            if (activity != null) {
                return activity;
            }
        }
        return null;
    }

    private static OngoingActivity from(Object entry) {
        Object source = field(field(entry, "mNotificationEntry"), "mSbn");
        StatusBarNotification status = source instanceof StatusBarNotification
                ? (StatusBarNotification) source : null;
        Notification n = status == null ? null : status.getNotification();
        Bundle extras = n == null || n.extras == null ? Bundle.EMPTY : n.extras;
        CharSequence title = first(text(field(entry, "mNowbarPrimaryInfo")),
                text(field(entry, "mPrimaryInfo")), extras.getCharSequence(Notification.EXTRA_TITLE),
                text(field(entry, "mAppName")));
        if (TextUtils.isEmpty(title)) {
            return null;
        }

        CharSequence detail = first(text(field(entry, "mNowbarSecondaryInfo")),
                text(field(entry, "mSecondaryInfo")), extras.getCharSequence(Notification.EXTRA_TEXT));
        Object chipIcon = field(entry, "mNowbarIcon");
        if (!(chipIcon instanceof Icon)) {
            chipIcon = field(entry, "mAppIcon");
        }
        Icon icon = chipIcon instanceof Icon ? (Icon) chipIcon : n == null ? null : n.getSmallIcon();
        long base = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) && n != null && n.when > 0
                ? SystemClock.elapsedRealtime() + (n.when - System.currentTimeMillis()) : 0;
        CharSequence stableKey = first(text(field(entry, "mNotiID")),
                status == null ? null : status.getKey(), title);
        return new OngoingActivity(stableKey.toString(), title, detail, icon,
                n == null ? null : n.contentIntent, base,
                extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN));
    }

    private static CharSequence first(CharSequence... values) {
        for (CharSequence value : values) {
            if (!TextUtils.isEmpty(value)) {
                return value;
            }
        }
        return null;
    }

    private static CharSequence text(Object value) {
        return value instanceof CharSequence ? (CharSequence) value : null;
    }

    static Object field(Object receiver, String name) {
        if (receiver != null) {
            try {
                return XposedHelpers.getObjectField(receiver, name);
            } catch (NoSuchFieldError ignored) {
                // Samsung sometimes renames the field between firmware builds.
            }
        }
        return null;
    }

    static Object call(Object receiver, String method) {
        if (receiver != null) {
            try {
                return XposedHelpers.callMethod(receiver, method);
            } catch (NoSuchMethodError ignored) {
                // The state controller differs between firmware builds.
            }
        }
        return null;
    }
}
