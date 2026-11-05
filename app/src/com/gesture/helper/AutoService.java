package com.gesture.helper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.view.accessibility.AccessibilityEvent;

import java.util.List;

/**
 * 无障碍手势服务：负责真正把点击 / 滑动注入到系统输入层。
 * 只做「注入」这一件事，不含任何业务逻辑，方便单独开关与排查。
 */
public class AutoService extends AccessibilityService {

    private static AutoService instance;
    private static final Object LOCK = new Object();
    private static long lastGestureAt = 0L;
    private static volatile String foregroundPackage = "";
    private static volatile String lastSeen = "";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        CharSequence pn = event.getPackageName();
        if (pn != null && pn.length() > 0) {
            foregroundPackage = pn.toString();
        }
    }

    /** 用 UsageStats 主动查当前前台包名。没授权时返回 null。 */
    public static String foregroundByUsage(Context c) {
        try {
            UsageStatsManager usm =
                    (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) {
                return null;
            }
            long end = System.currentTimeMillis();
            List<UsageStats> list =
                    usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, end - 120000L, end);
            if (list == null || list.isEmpty()) {
                return null;
            }
            UsageStats best = null;
            for (UsageStats s : list) {
                if (s == null || s.getPackageName() == null) {
                    continue;
                }
                if (best == null || s.getLastTimeUsed() > best.getLastTimeUsed()) {
                    best = s;
                }
            }
            return best == null ? null : best.getPackageName();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 综合判断前台是不是游戏。
     * 返回 TRUE/FALSE 表示判断成功，返回 null 表示压根查不到前台信息
     * ——Unity 游戏不发窗口事件、又没授权使用情况访问时就是这个情况，
     * 此时调用方应该放行而不是死等。
     */
    public static Boolean gameInForeground(Context c) {
        String pkg = foregroundByUsage(c);
        if (pkg == null || pkg.isEmpty()) {
            pkg = foregroundPackage;
        }
        if (pkg == null || pkg.isEmpty()) {
            lastSeen = "";
            return null;
        }
        lastSeen = pkg;
        String saved = Scripts.getGamePkg(c);
        if (saved != null && !saved.isEmpty() && saved.equals(pkg)) {
            return Boolean.TRUE;
        }
        return Boolean.valueOf(isGame(pkg));
    }

    private static boolean isGame(String p) {
        String low = p.toLowerCase(java.util.Locale.US);
        return low.contains("onmyoji") || low.contains("netease");
    }

    /** 最近一次看到的包名，用于面板诊断显示。 */
    public static String lastSeen() {
        return lastSeen;
    }

    public static String foreground() {
        return foregroundPackage;
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return false;
    }

    public static boolean ready() {
        return instance != null;
    }

    /** 连续手势之间留出最小间隔，避免系统丢弃正在进行的 gesture。 */
    private static void guard() {
        long wait = 90L - (System.currentTimeMillis() - lastGestureAt);
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastGestureAt = System.currentTimeMillis();
    }

    public static boolean tap(float px, float py, int holdMs) {
        if (instance == null) {
            return false;
        }
        synchronized (LOCK) {
            guard();
            Path path = new Path();
            path.moveTo(px, py);
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, holdMs));
            return instance.dispatchGesture(builder.build(), null, null);
        }
    }

    public static boolean swipe(float x1, float y1, float x2, float y2, int durationMs) {
        if (instance == null) {
            return false;
        }
        synchronized (LOCK) {
            guard();
            Path path = new Path();
            path.moveTo(x1, y1);
            path.lineTo(x2, y2);
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, Math.max(50, durationMs)));
            return instance.dispatchGesture(builder.build(), null, null);
        }
    }

    public static boolean back() {
        if (instance == null) {
            return false;
        }
        return instance.performGlobalAction(GLOBAL_ACTION_BACK);
    }

    public static boolean home() {
        if (instance == null) {
            return false;
        }
        return instance.performGlobalAction(GLOBAL_ACTION_HOME);
    }
}
