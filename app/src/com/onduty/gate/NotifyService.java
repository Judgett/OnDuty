package com.onduty.gate;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashMap;

/**
 * 后台常驻服务：通知监听 + 判定引擎 + 定点收起。
 *
 * <p>三条触发路径都会调用 {@link #engine()} 重算判定：
 * <ol>
 *   <li>{@link #onNotificationPosted} 收到通知时（走 8 秒缓存）；</li>
 *   <li>60 秒一次的定时轮询；</li>
 *   <li>网络可用性 / 能力变化回调。</li>
 * </ol>
 * 判定逻辑本身全部委托给 {@link Guard}，本类只负责调度与执行撤回。
 */
public class NotifyService extends NotificationListenerService {

    /** 定时轮询间隔：判定引擎每分钟至少刷新一次。 */
    private static final long TICK_MS = 60 * 1000L;
    /** 判定结果缓存有效期：同一波通知共用一个结果，避免逐条重算。 */
    private static final long MODE_TTL_MS = 8 * 1000L;

    /** 主线程 Handler，用于轮询与网络回调切回主线程后重算。 */
    private Handler handler;
    /** 周期性轮询任务。 */
    private Runnable ticker;
    /** 网络状态读取器，仅用于地点判据。 */
    private ConnectivityManager cm;
    /** 默认网络回调：网络切换/重连时立即重算，避免等满 60 秒。 */
    private ConnectivityManager.NetworkCallback cb;
    /** 包名 → 应用名标签缓存，供无声摘要显示。 */
    private final HashMap<String, String> labels = new HashMap<String, String>();

    /** 最近一次判定结果；-1 表示尚未判定。 */
    private int cachedMode = -1;
    /** 最近一次判定的时间戳，配合 MODE_TTL_MS 决定是否需要重算。 */
    private long cachedAt = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        Fold.ensureChannel(this);
        ticker = new Runnable() {
            @Override
            public void run() {
                engine();
                if (handler != null) handler.postDelayed(this, TICK_MS);
            }
        };
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Guard.addLog(this, "通知监听已连接");
        engine();
        if (handler != null) {
            handler.removeCallbacks(ticker);
            handler.postDelayed(ticker, TICK_MS);
        }
        registerNet();
    }

    @Override
    public void onListenerDisconnected() {
        if (handler != null) handler.removeCallbacks(ticker);
        unregisterNet();
        Guard.addLog(this, "通知监听断开，回落为放行");
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        if (handler != null) handler.removeCallbacks(ticker);
        unregisterNet();
        super.onDestroy();
    }

    /**
     * 收到通知时决定是否撤回。只对目标 App、且当前判定为静默时动手；
     * 撤回后调用 {@link Fold#bump} 累加无声摘要计数。
     */
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (pkg == null || pkg.equals(getPackageName())) return;
        if (mode() != Guard.MODE_SILENCE) return;
        if (!Guard.hasTarget(this, pkg)) return;
        try {
            cancelNotification(sbn.getKey());
        } catch (Throwable ignored) {
        }
        Fold.bump(this, labelOf(pkg));
    }

    /** 带短期缓存的判定读取：8 秒内复用上次结果，避免同一波通知逐条重算。 */
    private int mode() {
        long now = System.currentTimeMillis();
        if (cachedMode < 0 || now - cachedAt > MODE_TTL_MS) {
            engine();
        }
        return cachedMode;
    }

    /** 重算判定并记录日志；切回放行时清掉残留的无声摘要。 */
    private void engine() {
        long now = System.currentTimeMillis();
        StringBuilder reason = new StringBuilder();
        int mode = Guard.resolveMode(this, now, reason);
        Guard.logTransition(this, mode, reason.toString());
        if (mode == Guard.MODE_ALLOW) {
            Fold.clear(this);
        }
        cachedMode = mode;
        cachedAt = now;
    }

    private void registerNet() {
        try {
            if (cm == null) {
                cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            }
            if (cm == null || cb != null) return;
            cb = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    postEngine();
                }

                @Override
                public void onLost(Network network) {
                    postEngine();
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    postEngine();
                }
            };
            cm.registerDefaultNetworkCallback(cb);
        } catch (Throwable ignored) {
        }
    }

    private void unregisterNet() {
        try {
            if (cm != null && cb != null) cm.unregisterNetworkCallback(cb);
        } catch (Throwable ignored) {
        }
        cb = null;
    }

    private void postEngine() {
        if (handler == null) return;
        handler.post(new Runnable() {
            @Override
            public void run() {
                engine();
            }
        });
    }

    private String labelOf(String pkg) {
        String l = labels.get(pkg);
        if (l != null) return l;
        try {
            PackageManager pm = getPackageManager();
            l = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            l = pkg;
        }
        labels.put(pkg, l);
        return l;
    }
}
