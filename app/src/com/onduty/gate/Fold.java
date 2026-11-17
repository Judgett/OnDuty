package com.onduty.gate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 收起通知后的「无声折叠摘要」：只统计条数和最近应用，不保存任何通知内容。
 */
public class Fold {

    /** 通知渠道 ID，低重要性：无声、不振动、不锁屏显示。 */
    private static final String CHANNEL = "fold";
    /** 汇总通知的固定 ID，更新时复用同一条，不堆叠。 */
    private static final int ID = 2001;
    /** 刷新节流：1.5 秒内只更新一次，防止通知风暴刷屏。 */
    private static final long THROTTLE_MS = 1500L;

    /** 上次发布汇总的时刻（elapsedRealtime，不受系统时间调整影响）。 */
    private static long lastPostAt = 0;
    /** 静默时段累计收起的条数。 */
    private static int count = 0;
    /** 最近收起的应用名，最多保留 4 个。 */
    private static final List<String> recent = new ArrayList<String>();

    /** 创建低功耗通知渠道；失败时静默忽略，不影响主功能。 */
    public static void ensureChannel(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL, "收起摘要",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("静默时段收起通知后的无声汇总，只含条数与时间");
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) {
        }
    }

    /** 收起一条通知时累加计数，必要时刷新汇总；受开关与节流双重约束。 */
    public static synchronized void bump(Context c, String label) {
        if (!Guard.summaryOn(c)) return;
        count++;
        if (label != null && label.length() > 0 && !recent.contains(label)) {
            recent.add(0, label);
            while (recent.size() > 4) {
                recent.remove(recent.size() - 1);
            }
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastPostAt < THROTTLE_MS) return;
        lastPostAt = now;
        post(c);
    }

    /** 切回放行时清零计数并撤掉汇总通知。 */
    public static synchronized void clear(Context c) {
        boolean had = count > 0;
        count = 0;
        recent.clear();
        if (!had) return;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(ID);
        } catch (Throwable ignored) {
        }
    }

    /** 发布或更新「已收起 N 条消息」汇总，只含条数与应用名，不含通知正文。 */
    private static void post(Context c) {
        if (count <= 0) return;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            ensureChannel(c);

            String text = recent.isEmpty() ? "静默时段已收起这些应用的通知"
                    : "最近：" + TextUtils.join("、", recent);

            Intent open = new Intent(c, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(c, 0, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            Notification.Builder b = new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_clock)
                    .setContentTitle("已收起 " + count + " 条消息")
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(true)
                    .setCategory(Notification.CATEGORY_STATUS);

            nm.notify(ID, b.build());
        } catch (Throwable ignored) {
        }
    }
}
