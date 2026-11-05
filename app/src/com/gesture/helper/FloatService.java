package com.gesture.helper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 悬浮控制台。形态：悬浮球(48dp) → 迷你条(约30dp) → 完整面板(Tab 分页)。
 *
 * 面板采用「主控 / 步骤 / 日志」三页：三页共用一个撑满剩余高度的滚动区，
 * 这样步骤列表和日志都有完整空间，不会被固定控件挤成一条缝。
 */
public class FloatService extends Service {

    private static final String CHANNEL = "yys_run";
    private static final int NOTIFY_ID = 9001;
    private static final int PANEL_W_DP = 258;

    /** 面板不透明度档位，挡视线时可以调低。 */
    private static final float[] ALPHAS = {1.0f, 0.88f, 0.74f, 0.6f};

    private WindowManager wm;
    private Handler ui = new Handler(Looper.getMainLooper());

    private View bubble;
    private View panel;
    private FrameLayout pickLayer;

    private WindowManager.LayoutParams lpBubble;
    private WindowManager.LayoutParams lpPanel;
    private WindowManager.LayoutParams lpPick;

    private int plan = 0;
    private List<Model.Node> nodes = new ArrayList<>();
    private int alphaIdx = 0;

    // 面板内控件
    private TextView tvStatus;
    private TextView tvRound;
    private TextView tvPlan;
    private TextView tvLog;
    private TextView tvFg;
    private LinearLayout stepsBox;
    private TextView btnPlay;
    private TextView btnPause;
    private TextView tvGap;
    private TextView tvJitter;
    private TextView tvLoop;
    private TextView tvFgOnly;
    private TextView tvAlpha;
    private View pageMain;
    private View pageSteps;
    private View pageLog;
    private TextView[] tabs = new TextView[3];
    private int tabIndex = 0;
    private ScrollView logScroll;

    // 取点 / 校准
    private int pickTarget = -1;
    private TextView tvPickStep;
    private List<Integer> calQueue;
    /** 仅批量校准才需要的两个按钮，单步取点时隐藏，避免误导。 */
    private View skipBtn;
    /** 取点层刚弹出时的忽略期，防止同一根手指连跳两步。 */
    private long pickGuardUntil = 0L;
    private View endBtn;
    private int calPos = -1;

    private final List<String> logBuf = new ArrayList<>();
    private int screenW = 2400;
    private int screenH = 1080;

    // ============================================================ 生命周期

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        int[] sc = Engine.screen(this);
        screenW = sc[0];
        screenH = sc[1];
        plan = Scripts.getPlan(this);
        Scripts.migrate(this);
        nodes = Scripts.load(this, plan);
        alphaIdx = Scripts.prefs(this).getInt("alphaIdx", 0);
        if (alphaIdx < 0 || alphaIdx >= ALPHAS.length) {
            alphaIdx = 0;
        }
        buildBubble();
        buildPanel();
        buildPickLayer();
        log("控制台已启动，屏幕 " + screenW + "x" + screenH);
        log("当前方案：" + Scripts.NAMES[plan] + "，共 " + nodes.size() + " 步");
        // 记住上次形态：0=收起为球，2=展开面板
        if (Scripts.prefs(this).getInt("uiMode", 2) == 2) {
            showPanel();
        } else {
            showBubble();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundSafe();
        Intent data = ScreenGrabber.pending();
        if (data != null && !ScreenGrabber.isAlive()) {
            int[] sc = Engine.screen(this);
            boolean ok = ScreenGrabber.start(this, data, sc[0], sc[1], sc[2]);
            log(ok ? "取色已就绪，可用「等到出现」做状态判断"
                    : "取色启动失败，将按纯定时模式运行");
        }
        if (panel != null && panel.getParent() != null) {
            showPanel();
        }
        return START_STICKY;
    }

    private void startForegroundSafe() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "脚本运行",
                        NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(ch);
            }
            Notification.Builder nb;
            if (Build.VERSION.SDK_INT >= 26) {
                nb = new Notification.Builder(this, CHANNEL);
            } else {
                nb = new Notification.Builder(this);
            }
            nb.setContentTitle("阴阳师助手运行中")
                    .setContentText("点击打开控制台")
                    .setSmallIcon(R.drawable.ic_notify)
                    .setOngoing(true);
            Notification n = nb.build();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFY_ID, n);
            }
        } catch (Throwable t) {
            log("前台通知失败：" + t.getMessage());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Engine.stop();
        ScreenGrabber.release();
        ui.removeCallbacks(fgTicker);
        detach(bubble);
        detach(panel);
        detach(pickLayer);
        super.onDestroy();
    }

    // ============================================================ 悬浮球

    private void buildBubble() {
        TextView v = new TextView(this);
        v.setText("御");
        v.setGravity(Gravity.CENTER);
        v.setTextSize(17);
        v.setTextColor(Color.parseColor("#1A0A2E"));
        v.setTypeface(Typeface.DEFAULT_BOLD);
        int s = dp(46);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColors(new int[]{Color.parseColor("#F1DC9E"), Color.parseColor("#C9A75A")});
        g.setStroke(2, Color.parseColor("#66FFFFFF"));
        v.setBackground(g);
        bubble = v;
        lpBubble = overlayParams(s, s, Gravity.TOP | Gravity.START);
        int[] d = defaultPos("bubbleX", "bubbleY", s, s);
        lpBubble.x = d[0];
        lpBubble.y = d[1];
        attachDrag(v, lpBubble, new Runnable() {
            @Override
            public void run() {
                detach(bubble);
                showPanel();
            }
        });
    }

    private void showBubble() {
        Scripts.prefs(this).edit().putInt("uiMode", 0).apply();
        attach(bubble, lpBubble);
    }

    private void setPlayIcon(boolean running) {
        if (btnPlay != null) {
            btnPlay.setText(running ? "暂停" : "开始");
        }
    }

    // ============================================================ 面板

    private void panelHeight() {
        int h = screenH - dp(14);
        h = Math.min(h, dp(430));
        h = Math.max(h, dp(250));
        if (lpPanel != null) {
            lpPanel.height = h;
        }
    }

    private void buildPanel() {
        int contentW = dp(PANEL_W_DP);
        int over = dp(6);
        int w = contentW + over * 2;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(rod(true));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackground(scrollBody());
        body.setPadding(dp(7), dp(3), dp(7), dp(3));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(contentW, 0, 1f);
        blp.leftMargin = over;
        blp.rightMargin = over;
        body.setLayoutParams(blp);

        View title = titleBar();
        body.addView(title);
        body.addView(statusRow());
        body.addView(fgRow());
        body.addView(tabRow());

        FrameLayout content = new FrameLayout(this);
        content.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        pageMain = buildMainPage();
        pageSteps = buildStepsPage();
        pageLog = buildLogPage();
        pageMain.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pageSteps.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pageLog.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(pageMain);
        content.addView(pageSteps);
        content.addView(pageLog);
        body.addView(content);

        root.addView(body);
        root.addView(rod(false));

        panel = root;
        lpPanel = overlayParams(w, dp(300), Gravity.TOP | Gravity.START);
        panelHeight();
        int[] d = defaultPos("panelX", "panelY", w, lpPanel.height);
        lpPanel.x = d[0];
        lpPanel.y = d[1];
        // 标题栏作为拖动手柄：长按 3 秒解锁后才能拖动，按钮（—/×）不受影响。
        attachDrag(title, lpPanel, null);
        root.setAlpha(ALPHAS[alphaIdx]);

        selectTab(0);
        updatePlanText();
        syncParams();
        renderSteps();
    }

    private View titleBar() {
        LinearLayout grip = new LinearLayout(this);
        grip.setOrientation(LinearLayout.HORIZONTAL);
        grip.setGravity(Gravity.CENTER_VERTICAL);
        grip.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(22)));

        TextView badge = new TextView(this);
        badge.setText("御");
        badge.setTextSize(10f);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextColor(Color.parseColor("#1A0A2E"));
        badge.setGravity(Gravity.CENTER);
        int s = dp(16);
        badge.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColors(new int[]{Color.parseColor("#F1DC9E"), Color.parseColor("#C9A75A")});
        badge.setBackground(bg);
        grip.addView(badge);

        TextView name = tiny("阴阳师助手", 11.5f, "#E8C87A", true);
        name.setPadding(dp(5), 0, 0, 0);
        name.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        grip.addView(name);
        grip.addView(chip("—", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                detach(panel);
                showBubble();
            }
        }));
        grip.addView(chip("×", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Engine.stop();
                stopSelf();
            }
        }));
        return grip;
    }

    private View statusRow() {
        LinearLayout st = new LinearLayout(this);
        st.setOrientation(LinearLayout.HORIZONTAL);
        st.setGravity(Gravity.CENTER_VERTICAL);
        st.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(17)));
        tvStatus = tiny("就绪", 10.5f, "#6FE3C4", true);
        tvRound = tiny("第 0 轮", 10f, "#A99CBE", false);
        tvRound.setGravity(Gravity.END);
        tvRound.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        st.addView(tvStatus);
        st.addView(tvRound);
        return st;
    }

    private View fgRow() {
        tvFg = tiny("前台：检测中", 9.5f, "#8E82A6", false);
        tvFg.setSingleLine(true);
        tvFg.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        tvFg.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(16)));
        tvFg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Scripts.setGamePkg(FloatService.this, "");
                log("已清除记住的游戏包名，下次点开始会重新记录");
                updateFgPkg();
                toast("已清除，回到游戏后重新点开始即可");
            }
        });
        return tvFg;
    }

    private View tabRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));
        final String[] names = {"主控", "步骤", "日志"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextSize(11f);
            t.setGravity(Gravity.CENTER);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(22), 1f);
            lp.rightMargin = dp(3);
            t.setLayoutParams(lp);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(idx);
                }
            });
            tabs[i] = t;
            row.addView(t);
        }
        return row;
    }

    private void selectTab(int idx) {
        tabIndex = idx;
        for (int i = 0; i < 3; i++) {
            if (tabs[i] == null) {
                continue;
            }
            boolean on = i == idx;
            tabs[i].setTextColor(Color.parseColor(on ? "#1A0A2E" : "#B9ACD0"));
            tabs[i].setBackground(getResources().getDrawable(
                    on ? R.drawable.btn_gold : R.drawable.btn_ghost));
        }
        if (pageMain != null) {
            pageMain.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        }
        if (pageSteps != null) {
            pageSteps.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        }
        if (pageLog != null) {
            pageLog.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);
        }
        if (idx == 1) {
            renderSteps();
        }
        if (idx == 2) {
            flushLog();
        }
    }

    private void updateTabLabels() {
        if (tabs[1] != null) {
            tabs[1].setText("步骤 " + nodes.size());
        }
        if (tabs[2] != null) {
            tabs[2].setText("日志 " + logBuf.size());
        }
    }

    // ---------------------------------------- 主控页

    private View buildMainPage() {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        // 方案
        LinearLayout planRow = new LinearLayout(this);
        planRow.setOrientation(LinearLayout.HORIZONTAL);
        planRow.setGravity(Gravity.CENTER_VERTICAL);
        planRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));
        TextView prev = chip("◀", false, null);
        tvPlan = tiny("", 11f, "#F4ECDE", true);
        tvPlan.setGravity(Gravity.CENTER);
        tvPlan.setSingleLine(true);
        tvPlan.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        tvPlan.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView next = chip("▶", false, null);
        View.OnClickListener sw = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                plan = (plan + (Integer.valueOf(1).equals(v.getTag()) ? 1
                        : Scripts.NAMES.length - 1)) % Scripts.NAMES.length;
                Scripts.setPlan(FloatService.this, plan);
                nodes = Scripts.load(FloatService.this, plan);
                updatePlanText();
                updateTabLabels();
                renderSteps();
                log("切换到 " + Scripts.NAMES[plan] + "，共 " + nodes.size() + " 步");
            }
        };
        prev.setTag(0);
        next.setTag(1);
        prev.setOnClickListener(sw);
        next.setOnClickListener(sw);
        planRow.addView(prev);
        planRow.addView(tvPlan);
        planRow.addView(next);
        box.addView(planRow);

        // 主控三键
        LinearLayout ctrl = new LinearLayout(this);
        ctrl.setOrientation(LinearLayout.HORIZONTAL);
        ctrl.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(26)));
        btnPlay = chipWide("开始", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlay();
            }
        });
        btnPause = chipWide("暂停", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!Engine.isRunning()) {
                    toast("还没开始");
                    return;
                }
                boolean p = !Engine.isPaused();
                Engine.setPaused(p);
                setStatus(p ? "已暂停" : "运行中", p ? "#FF9BC0" : "#6FE3C4");
                setPlayIcon(!p);
                btnPlay.setText(p ? "继续" : "暂停");
                log(p ? "已暂停" : "已继续");
            }
        });
        ctrl.addView(btnPlay);
        ctrl.addView(btnPause);
        ctrl.addView(chipWide("停止", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Engine.stop();
                setStatus("已停止", "#A99CBE");
                setPlayIcon(false);
                btnPlay.setText("开始");
                log("已停止");
            }
        }));
        box.addView(ctrl);
        box.addView(hair());

        // 参数
        box.addView(paramRow("步间隔", tvGap = tiny("", 10.5f, "#F4ECDE", false),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        int cur = Scripts.getGap(FloatService.this);
                        Scripts.setGap(FloatService.this, cur >= 2000 ? 0 : cur + 200);
                        syncParams();
                    }
                }));
        box.addView(paramRow("随机偏移", tvJitter = tiny("", 10.5f, "#F4ECDE", false),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        int cur = Scripts.getJitter(FloatService.this);
                        Scripts.setJitter(FloatService.this, cur >= 40 ? 0 : cur + 5);
                        syncParams();
                    }
                }));
        box.addView(paramRow("循环次数", tvLoop = tiny("", 10.5f, "#F4ECDE", false),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        int cur = Scripts.getLoops(FloatService.this);
                        int next;
                        if (cur == 0) {
                            next = 1;
                        } else if (cur < 10) {
                            next = cur + 1;
                        } else if (cur < 50) {
                            next = cur + 10;
                        } else if (cur < 300) {
                            next = cur + 50;
                        } else {
                            next = 0;
                        }
                        Scripts.setLoops(FloatService.this, next);
                        syncParams();
                    }
                }));
        box.addView(paramRow("仅游戏前台", tvFgOnly = tiny("", 10.5f, "#F4ECDE", false),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Scripts.setFgOnly(FloatService.this, !Scripts.getFgOnly(FloatService.this));
                        syncParams();
                    }
                }));
        box.addView(paramRow("面板透明度", tvAlpha = tiny("", 10.5f, "#F4ECDE", false),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        alphaIdx = (alphaIdx + 1) % ALPHAS.length;
                        Scripts.prefs(FloatService.this).edit()
                                .putInt("alphaIdx", alphaIdx).apply();
                        if (panel != null) {
                            panel.setAlpha(ALPHAS[alphaIdx]);
                        }
                        syncParams();
                    }
                }));
        box.addView(hair());

        // 校准：最重要的入口
        box.addView(chipWideBig("校准全部坐标（逐步取点）", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startCalibrate();
            }
        }));
        LinearLayout t1 = new LinearLayout(this);
        t1.setOrientation(LinearLayout.HORIZONTAL);
        t1.addView(chipWide("还原默认坐标", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Scripts.reset(FloatService.this, plan);
                nodes = Scripts.load(FloatService.this, plan);
                renderSteps();
                updateTabLabels();
                log("已还原 " + Scripts.NAMES[plan] + " 默认坐标");
            }
        }));
        t1.addView(chipWide("试点击中心", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!AutoService.ready()) {
                    toast("无障碍服务未开启");
                    return;
                }
                Engine.tapOnce(FloatService.this, 0.5f, 0.5f);
                log("测试点击：屏幕中心（看游戏有没有反应）");
            }
        }));
        box.addView(t1);

        LinearLayout t2 = new LinearLayout(this);
        t2.setOrientation(LinearLayout.HORIZONTAL);
        t2.addView(chipWide("收起为小球", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                detach(panel);
                showBubble();
            }
        }));
        t2.addView(chipWide("打开主界面", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent it = new Intent(FloatService.this, MainActivity.class);
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it);
            }
        }));
        box.addView(t2);
        box.addView(hair());
        box.addView(tiny("坐标不准就用「校准全部坐标」，它会带你一步一步点完。",
                9.5f, "#8E82A6", false));
        box.addView(tiny("「+找小怪打 / +找首领打」全屏识别图标后自己点；点完会自己点出战、"
                + "打完整场、点「点击屏幕继续」，再回头找下一只。", 9.5f, "#8E82A6", false));
        box.addView(tiny("右侧「×场次」是这一轮最多打几场；连续找不到 12 次就认为这批清完了。",
                9.5f, "#8E82A6", false));
        box.addView(tiny("不碰游戏里的「自动挑战」，全程脚本自己点。",
                9.5f, "#8E82A6", false));
        box.addView(tiny("想验证识别准不准：步骤右边点「测」，会报当前相似度百分比。",
                9.5f, "#8E82A6", false));

        sv.addView(box);
        return sv;
    }

    // ---------------------------------------- 步骤页

    private View buildStepsPage() {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(tiny("点条目或「定位」重新取点；「时长」切换等待秒数", 9.5f, "#8E82A6", false));
        stepsBox = new LinearLayout(this);
        stepsBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(stepsBox);
        box.addView(hair());

        LinearLayout add = new LinearLayout(this);
        add.setOrientation(LinearLayout.HORIZONTAL);
        add.addView(chipWide("+点击", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                nodes.add(Model.Node.tap(0.5f, 0.5f));
                persist();
                renderSteps();
                enterPick(nodes.size() - 1);
            }
        }));
        add.addView(chipWide("+等待", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                nodes.add(Model.Node.sleep(1000));
                persist();
                renderSteps();
            }
        }));
        add.addView(chipWide("+等出现", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Model.Node n = new Model.Node(Model.WAIT_COLOR);
                n.x = 0.5f;
                n.y = 0.5f;
                n.ms = 20000;
                n.note = "等画面出现";
                nodes.add(n);
                persist();
                renderSteps();
                log("已加「等出现」：点它的「定位」，对着要等的东西（比如首领头顶标识）点一下就行");
                enterPick(nodes.size() - 1);
            }
        }));
        add.addView(chipWide("+返回", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                nodes.add(Model.Node.back());
                persist();
                renderSteps();
            }
        }));
        box.addView(add);

        LinearLayout add3 = new LinearLayout(this);
        add3.setOrientation(LinearLayout.HORIZONTAL);
        add3.addView(chipWide("+找小怪打", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Model.Node n = new Model.Node(Model.FIND_TAP);
                n.tpl = 0;
                n.pct = 85;
                n.times = 12;
                n.ms = 45000;
                n.note = "自己找小怪打";
                nodes.add(n);
                persist();
                renderSteps();
                log("已加「自己找小怪打」：全屏识别刀形标识，找到就点，不用设坐标");
            }
        }));
        add3.addView(chipWide("+找首领打", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Model.Node n = new Model.Node(Model.FIND_TAP);
                n.tpl = 1;
                n.pct = 82;
                n.times = 14;
                n.ms = 45000;
                n.note = "自己找首领打";
                nodes.add(n);
                persist();
                renderSteps();
                log("已加「自己找首领打」：全屏识别红金刀柄标识，找到就点");
            }
        }));
        box.addView(add3);

        LinearLayout add2 = new LinearLayout(this);
        add2.setOrientation(LinearLayout.HORIZONTAL);
        add2.addView(chipWide("+识首领", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Model.Node n = new Model.Node(Model.WAIT_MATCH);
                n.tpl = 1;
                n.pct = 72;
                n.ms = 30000;
                n.note = "等首领标识出现";
                nodes.add(n);
                persist();
                renderSteps();
                log("已加「识别首领标识」：全屏搜索，位置无关，换主线也能认");
            }
        }));
        add2.addView(chipWide("清空", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                nodes.clear();
                persist();
                renderSteps();
                log("已清空步骤");
            }
        }));
        add2.addView(chipWide("还原默认", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Scripts.reset(FloatService.this, plan);
                nodes = Scripts.load(FloatService.this, plan);
                renderSteps();
                log("已还原 " + Scripts.NAMES[plan] + " 默认坐标");
            }
        }));
        box.addView(add2);
        sv.addView(box);
        return sv;
    }

    // ---------------------------------------- 日志页

    private View buildLogPage() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(tiny("运行日志（最新在底部）", 9.5f, "#8E82A6", false));
        TextView clear = chipWide("清空", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logBuf.clear();
                if (tvLog != null) {
                    tvLog.setText("");
                }
                updateTabLabels();
            }
        });
        clear.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(20)));
        top.addView(clear);
        box.addView(top);

        logScroll = new ScrollView(this);
        logScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        tvLog = new TextView(this);
        tvLog.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.8f);
        tvLog.setTextColor(Color.parseColor("#B9ACD0"));
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setPadding(dp(2), dp(2), dp(2), dp(2));
        logScroll.addView(tvLog);
        box.addView(logScroll);
        return box;
    }

    // ============================================================ 显示控制

    private void showPanel() {
        if (panel == null) {
            return;
        }
        Scripts.prefs(this).edit().putInt("uiMode", 2).apply();
        panelHeight();
        updatePlanText();
        updateTabLabels();
        syncParams();
        renderSteps();
        attach(panel, lpPanel);
        ui.removeCallbacks(fgTicker);
        ui.post(fgTicker);
        if (tabIndex == 2) {
            flushLog();
        }
    }

    // ============================================================ 卷轴外观

    private View rod(boolean top) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int h = dp(10);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h);
        if (top) {
            lp.bottomMargin = dp(1);
        } else {
            lp.topMargin = dp(1);
        }
        bar.setLayoutParams(lp);

        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(4));
        g.setColors(new int[]{Color.parseColor("#6B4A2C"), Color.parseColor("#2A1810")});
        g.setStroke(1, Color.parseColor("#8A6B3D"));
        GradientDrawable knob = new GradientDrawable();
        knob.setShape(GradientDrawable.OVAL);
        knob.setColors(new int[]{Color.parseColor("#F6E7B4"), Color.parseColor("#A9853F")});

        View left = new View(this);
        left.setLayoutParams(new LinearLayout.LayoutParams(dp(9), dp(8)));
        left.setBackground(knob);
        View mid = new View(this);
        mid.setLayoutParams(new LinearLayout.LayoutParams(0, dp(6), 1f));
        mid.setBackground(g);
        View right = new View(this);
        right.setLayoutParams(new LinearLayout.LayoutParams(dp(9), dp(8)));
        right.setBackground(knob);

        bar.addView(left);
        bar.addView(mid);
        bar.addView(right);
        return bar;
    }

    private View cap() {
        View v = new View(this);
        int s = dp(8);
        v.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColors(new int[]{Color.parseColor("#F1DC9E"), Color.parseColor("#8A6B3D")});
        v.setBackground(g);
        return v;
    }

    private GradientDrawable scrollBody() {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(10));
        g.setColor(0xF2140A24);
        g.setStroke(1, Color.parseColor("#5CE8C87A"));
        return g;
    }

    // ============================================================ 取点 / 校准

    private void buildPickLayer() {
        final FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(0x88000000);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(Gravity.CENTER_HORIZONTAL);
        bar.setPadding(dp(24), dp(18), dp(24), dp(18));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(0xF2140A24);
        bg.setStroke(1, Color.parseColor("#88E8C87A"));
        bar.setBackground(bg);

        tvPickStep = new TextView(this);
        tvPickStep.setText("点一下要点的位置");
        tvPickStep.setGravity(Gravity.CENTER);
        tvPickStep.setTextSize(14);
        tvPickStep.setTextColor(Color.parseColor("#F4ECDE"));
        tvPickStep.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(tvPickStep);

        TextView sub = new TextView(this);
        sub.setText("点屏幕任意位置取点；下方按钮可跳过/结束");
        sub.setGravity(Gravity.CENTER);
        sub.setTextSize(11);
        sub.setTextColor(Color.parseColor("#A99CBE"));
        sub.setPadding(0, dp(4), 0, 0);
        bar.addView(sub);

        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
        bp.topMargin = dp(56);
        bar.setLayoutParams(bp);
        layer.addView(bar);

        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        acts.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        ap.bottomMargin = dp(48);
        acts.setLayoutParams(ap);
        skipBtn = chipWide("跳过这一步", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                log("跳过一步");
                nextCalStep();
            }
        });
        endBtn = chipWide("结束校准", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                calQueue = null;
                calPos = -1;
                pickTarget = -1;
                detach(pickLayer);
                showPanel();
                log("校准结束，共 " + nodes.size() + " 步");
            }
        });
        acts.addView(skipBtn);
        acts.addView(endBtn);
        acts.addView(chipWide("取消", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickTarget = -1;
                detach(pickLayer);
                showPanel();
                log("已取消取点");
            }
        }));
        layer.addView(acts);

        layer.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_UP) {
                    if (System.currentTimeMillis() < pickGuardUntil) {
                        // 遮罩是刚弹出来的，这一下多半是上一次点击的余震，忽略
                        return true;
                    }
                    applyPick(e.getRawX(), e.getRawY());
                }
                return true;
            }
        });
        pickLayer = layer;
        lpPick = overlayParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP | Gravity.START);
        lpPick.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
    }

    /** 把所有需要坐标的步骤排成队列，一步步带着用户取点。 */
    private void startCalibrate() {
        calQueue = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            int t = nodes.get(i).type;
            if (t == Model.TAP || t == Model.WAIT_COLOR || t == Model.WAIT_NOT) {
                calQueue.add(i);
            }
        }
        if (calQueue.isEmpty()) {
            toast("当前方案没有需要取点的步骤");
            return;
        }
        calPos = -1;
        log("开始校准，共 " + calQueue.size() + " 个位置需要确认");
        nextCalStep();
    }

    private void nextCalStep() {
        if (calQueue == null) {
            return;
        }
        calPos++;
        if (calPos >= calQueue.size()) {
            calQueue = null;
            calPos = -1;
            pickTarget = -1;
            detach(pickLayer);
            showPanel();
            log("校准完成，坐标已保存");
            toast("校准完成");
            return;
        }
        int idx = calQueue.get(calPos);
        enterPick(idx);
        Model.Node n = nodes.get(idx);
        String what = (n.note == null || n.note.isEmpty()) ? Model.Node.tap(n.x, n.y).label() : n.note;
        if (tvPickStep != null) {
            tvPickStep.setText("第 " + (calPos + 1) + "/" + calQueue.size() + " 步\n" + what);
        }
    }

    private void enterPick(int index) {
        if (pickLayer == null) {
            return;
        }
        pickTarget = index;
        boolean batch = calQueue != null;
        if (skipBtn != null) {
            skipBtn.setVisibility(batch ? View.VISIBLE : View.GONE);
        }
        if (endBtn != null) {
            endBtn.setVisibility(batch ? View.VISIBLE : View.GONE);
        }
        if (!batch && tvPickStep != null) {
            Model.Node n = nodes.get(index);
            String what = (n.note == null || n.note.isEmpty()) ? n.label() : n.note;
            tvPickStep.setText("取点：第 " + (index + 1) + " 步\n" + what);
        }
        detach(panel);
        pickGuardUntil = System.currentTimeMillis() + 350L;
        attach(pickLayer, lpPick);
    }

    private void applyPick(float rx, float ry) {
        detach(pickLayer);
        final float nx = clamp01(rx / screenW);
        final float ny = clamp01(ry / screenH);
        int target = pickTarget;
        if (target < 0 || target >= nodes.size()) {
            nodes.add(Model.Node.tap(nx, ny));
            target = nodes.size() - 1;
        } else {
            Model.Node n = nodes.get(pickTarget);
            // 原来是「等出现/等消失」的，取点只更新坐标，类型保持不变
            if (n.type != Model.SWIPE && n.type != Model.WAIT_COLOR
                    && n.type != Model.WAIT_NOT) {
                n.type = Model.TAP;
            }
            n.x = nx;
            n.y = ny;
        }
        persist();
        log(String.format(Locale.US, "已取点 (%.3f, %.3f)", nx, ny));

        final int index = target;
        final Model.Node picked = nodes.get(index);
        final boolean needColor = picked.type == Model.WAIT_COLOR
                || picked.type == Model.WAIT_NOT;

        // UI 必须立刻回来。之前延迟 260ms 才弹面板，用户以为没生效会再点一次，
        // 结果下一下正好落在刚弹出的步骤行上，又进了取点 —— 这就是「一直重复这一步」。
        if (calQueue != null) {
            nextCalStep();
        } else {
            showPanel();
            selectTab(1);
            toast("第 " + (index + 1) + " 步坐标已保存");
        }

        if (needColor) {
            // 抓色要等遮罩真正从合成层消失，放后台，不阻塞 UI
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(260);
                    } catch (InterruptedException ignored) {
                    }
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            grabColor(index);
                        }
                    });
                }
            }, "yys-grab").start();
        }
    }

    /**
     * 取点后顺手把这个位置的颜色记下来。
     * 这样把它切换成「等出现 / 等消失」时就有判定依据，不用手输颜色值。
     */
    private void grabColor(int index) {
        if (index < 0 || index >= nodes.size()) {
            return;
        }
        Model.Node n = nodes.get(index);
        int px = (int) (n.x * screenW);
        int py = (int) (n.y * screenH);
        int c = ScreenGrabber.colorAt(px, py);
        if (c < 0) {
            log("取色失败：没开「屏幕录制/取色」权限，这一步只能按时间走");
            return;
        }
        n.color = 0xFF000000 | c;
        persist();
        int pct = ScreenGrabber.ratio(px, py, dp(n.rad), n.color, n.tol);
        String tail = (pct >= 0) ? ("，当前同类像素 " + pct + "% / 阈值 " + n.pct + "%") : "";
        log("已取色 " + Model.hex(c) + tail);
        if (pct >= 0 && pct >= n.pct) {
            log("提示：这个颜色现在就已经出现了，做成「等消失」更合适");
        }
    }

    private float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    // ============================================================ 步骤列表

    private void renderSteps() {
        if (stepsBox == null) {
            return;
        }
        stepsBox.removeAllViews();
        for (int i = 0; i < nodes.size(); i++) {
            stepsBox.addView(stepRow(i));
        }
        if (nodes.isEmpty()) {
            stepsBox.addView(tiny("（空）点「+点击」开始录制", 10, "#8E82A6", false));
        }
        updateTabLabels();
    }

    private View stepRow(final int index) {
        final Model.Node n = nodes.get(index);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(3), dp(2), dp(3), dp(2));
        row.setMinimumHeight(dp(31));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = dp(1);
        row.setLayoutParams(rp);

        TextView idx = tiny((index + 1) + ".", 9.5f, "#6B5C85", false);
        idx.setWidth(dp(15));
        row.addView(idx);

        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        String note = (n.note == null || n.note.isEmpty()) ? "" : n.note;
        if (!note.isEmpty()) {
            TextView t1 = tiny(note, 10f, "#E4D9F2", true);
            t1.setSingleLine(true);
            t1.setEllipsize(TextUtils.TruncateAt.END);
            txt.addView(t1);
        }
        TextView t2 = tiny(n.label(), 9.5f, "#8E82A6", false);
        t2.setSingleLine(true);
        t2.setEllipsize(TextUtils.TruncateAt.END);
        txt.addView(t2);
        row.addView(txt);

        final boolean locatable = n.type == Model.TAP || n.type == Model.TAP_BURST
                || n.type == Model.WAIT_COLOR || n.type == Model.WAIT_NOT;
        if (locatable) {
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    enterPick(index);
                }
            });
        }

        // 类型：点 / 连 / 等 / 消 / 时 / 返
        row.addView(chip(typeMark(n.type), false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleType(index);
            }
        }));

        // 参数位：定位 / 次数 / 时长 / 实测
        if (locatable) {
            row.addView(chip("定位", true, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    enterPick(index);
                }
            }));
        } else if (n.type == Model.SLEEP) {
            row.addView(chip("时长", true, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cycleSleep(n);
                }
            }));
        } else if (n.type == Model.WAIT_MATCH || n.type == Model.FIND_TAP) {
            row.addView(chip(n.tpl == 1 ? "首领" : "小怪", true, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    n.tpl = n.tpl == 1 ? 0 : 1;
                    // 实测（2408 手机尺度仿真）：小怪画面小怪 0.90~0.97、首领模板仅 0.54；
                    // 首领画面首领 0.91+。两类都定 80%，留足余量又不互相误判。
                    n.pct = 90;
                    persist();
                    renderSteps();
                    log("第 " + (index + 1) + " 步改为「"
                            + (n.type == Model.FIND_TAP ? "自己找" : "识别")
                            + Vision.tplName(n.tpl) + "」，阈值 " + n.pct + "%");
                }
            }));
        } else {
            row.addView(chip(" ", false, null));
        }

        if (n.type == Model.TAP_BURST || n.type == Model.FIND_TAP) {
            row.addView(chip("×" + n.times, false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    n.times = n.times >= 24 ? 6 : n.times + 4;
                    persist();
                    renderSteps();
                }
            }));
        }
        if (n.type == Model.FIND_TAP) {
            final boolean go = n.x > 0.01f && n.y > 0.01f;
            row.addView(chip(go ? "出战开" : "出战关", go, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (go) {
                        n.x = 0f;
                        n.y = 0f;
                    } else {
                        n.x = Scripts.GO_X;
                        n.y = Scripts.GO_Y;
                    }
                    persist();
                    renderSteps();
                    log("第 " + (index + 1) + " 步「找怪」"
                            + (go ? "只点怪、不点出战" : "点完怪顺手点出战按钮"));
                }
            }));
        }
        if (n.type == Model.WAIT_COLOR || n.type == Model.WAIT_NOT
                || n.type == Model.WAIT_MATCH || n.type == Model.FIND_TAP) {
            row.addView(chip("测", false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (n.type == Model.WAIT_MATCH || n.type == Model.FIND_TAP) {
                        testMatch(index);
                    } else {
                        testJudge(index);
                    }
                }
            }));
        }
        if (n.type == Model.TAP || n.type == Model.SLEEP
                || n.type == Model.BACK || n.type == Model.SWIPE) {
            row.addView(chip("删", false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (index < nodes.size()) {
                        nodes.remove(index);
                        persist();
                        renderSteps();
                    }
                }
            }));
        }
        return row;
    }

    private String typeMark(int t) {
        switch (t) {
            case Model.TAP:
                return "点";
            case Model.TAP_BURST:
                return "连";
            case Model.WAIT_COLOR:
                return "等";
            case Model.WAIT_NOT:
                return "消";
            case Model.SLEEP:
                return "时";
            case Model.WAIT_MATCH:
                return "识";
            case Model.FIND_TAP:
                return "找";
            case Model.BACK:
                return "返";
            default:
                return "?";
        }
    }

    /** 点「点/连/等/消/时/返」循环切换类型。 */
    private void cycleType(int index) {
        if (index < 0 || index >= nodes.size()) {
            return;
        }
        Model.Node n = nodes.get(index);
        switch (n.type) {
            case Model.TAP:
                n.type = Model.TAP_BURST;
                if (n.times <= 0) {
                    n.times = 20;
                }
                if (n.ms <= 0) {
                    n.ms = 1100;
                }
                log("第 " + (index + 1) + " 步改为「连点」，次数用右边按钮改");
                break;
            case Model.TAP_BURST:
                n.type = Model.WAIT_COLOR;
                n.ms = 20000;
                if (n.color == 0xFFFFFFFF) {
                    log("第 " + (index + 1) + " 步改为「等出现」：先点「定位」取一次色，否则判定无效");
                } else {
                    log("第 " + (index + 1) + " 步改为「等出现」");
                }
                break;
            case Model.WAIT_COLOR:
                n.type = Model.WAIT_NOT;
                log("第 " + (index + 1) + " 步改为「等消失」");
                break;
            case Model.WAIT_NOT:
                n.type = Model.WAIT_MATCH;
                n.ms = 30000;
                n.pct = 72;
                n.tpl = 1;
                log("第 " + (index + 1) + " 步改为「识别首领标识」，全屏搜索，换地图也能认");
                break;
            case Model.WAIT_MATCH:
                n.type = Model.FIND_TAP;
                n.ms = 45000;
                n.pct = 90;
                if (n.times <= 0) {
                    n.times = 12;
                }
                log("第 " + (index + 1) + " 步改为「自己找" + Vision.tplName(n.tpl)
                        + "打」：全屏找图标，找到就点它");
                break;
            case Model.FIND_TAP:
                n.type = Model.SLEEP;
                n.ms = 2000;
                log("第 " + (index + 1) + " 步改为「等待 2s」");
                break;
            case Model.SLEEP:
                n.type = Model.BACK;
                log("第 " + (index + 1) + " 步改为「返回键」");
                break;
            default:
                n.type = Model.TAP;
                log("第 " + (index + 1) + " 步改为「点击」");
                break;
        }
        persist();
        renderSteps();
    }

    private void cycleSleep(Model.Node n) {
        int[] p = Model.SLEEP_PRESETS;
        int next = p[0];
        for (int i = 0; i < p.length; i++) {
            if (p[i] > n.ms) {
                next = p[i];
                break;
            }
        }
        n.ms = next;
        persist();
        renderSteps();
    }

    /** 立刻用这一步的坐标/颜色实测一次，把占比写到日志，方便判断阈值合不合适。 */
    private void testJudge(int index) {
        if (index < 0 || index >= nodes.size()) {
            return;
        }
        Model.Node n = nodes.get(index);
        int px = (int) (n.x * screenW);
        int py = (int) (n.y * screenH);
        int pct = ScreenGrabber.ratio(px, py, dp(n.rad), n.color, n.tol);
        if (pct < 0) {
            toast("取不到画面，先开「屏幕录制」权限");
            log("实测失败：没有屏幕录制权限");
            return;
        }
        boolean on = pct >= Math.max(1, n.pct);
        toast("当前占比 " + pct + "%，阈值 " + n.pct + "% → " + (on ? "已出现" : "未出现"));
        log("实测第 " + (index + 1) + " 步：占比 " + pct + "% / 阈值 " + n.pct
                + "% → " + (on ? "条件成立" : "条件不成立"));
    }

    /** 实测一次图标识别的相似度。放后台线程算，避免卡住悬浮窗。 */
    private void testMatch(final int index) {
        if (index < 0 || index >= nodes.size()) {
            return;
        }
        final Model.Node n = nodes.get(index);
        log("正在全屏找" + Vision.tplName(n.tpl) + "…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                Templates.ensure(FloatService.this);
                final Vision.Frame fr = ScreenGrabber.grab(Vision.SCALE);
                if (fr == null) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("取不到画面，先开「屏幕录制 / 取色」权限");
                            log("识别失败：没有屏幕录制权限");
                        }
                    });
                    return;
                }
                int[][] tpls = Vision.tpl(n.tpl);
                if (tpls == null || tpls.length == 0) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("模板没加载成功");
                            log("识别失败：assets 里的模板没读出来");
                        }
                    });
                    return;
                }
                Vision.Hit[] hits = Vision.searchMulti(fr, tpls, 3, 0.30f);
                Vision.Hit best = null;
                for (Vision.Hit h : hits) {
                    if (h.size > 0 && h.score > -1f) {
                        best = h;
                        break;
                    }
                }
                if (best == null) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("画面里没找到标识");
                            log("画面 " + fr.w + "x" + fr.h + " ｜ 没找到任何标识");
                        }
                    });
                    return;
                }
                // 定位用灰度（圆内掩模），类型用颜色判——两个结果都打出来
                final int pct = Math.round(best.score * 100);
                final int typ = Vision.classify(fr, best);
                final int cx = (best.x + best.size / 2) * Vision.SCALE;
                final int cy = (best.y + best.size / 2) * Vision.SCALE;
                final String fsize = fr.w + "x" + fr.h + (fr.h > fr.w ? "（竖屏，方向异常）" : "");
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        boolean ok = pct >= n.pct;
                        boolean kindOk = typ < 0 || typ == n.tpl;
                        String kindTxt = typ < 0 ? "颜色判不出" : ("颜色像" + Vision.tplName(typ));
                        toast("相似度 " + pct + "% / 阈值 " + n.pct + "% → "
                                + (ok ? "识别到" : "没识别到"));
                        log("画面 " + fsize + " ｜ " + kindTxt + "，" + pct + "% / 阈值 "
                                + n.pct + "% → " + (ok && kindOk ? "命中" : "未命中")
                                + "，位置 (" + cx + "," + cy + ")");
                        if (ok && !kindOk) {
                            log("  这个位置其实是" + Vision.tplName(typ)
                                    + "，脚本会跳过它去找下一个");
                        }
                    }
                });
            }
        }, "yys-match").start();
    }

    // ============================================================ 运行

    private void togglePlay() {
        if (Engine.isRunning() && !Engine.isPaused()) {
            Engine.setPaused(true);
            setPlayIcon(false);
            setStatus("已暂停", "#FF9BC0");
            btnPlay.setText("继续");
            log("已暂停");
        } else if (Engine.isRunning()) {
            Engine.setPaused(false);
            setPlayIcon(true);
            setStatus("运行中", "#6FE3C4");
            btnPlay.setText("暂停");
            log("已继续");
        } else {
            doStart();
        }
    }

    private void doStart() {
        if (!AutoService.ready()) {
            toast("请先开启无障碍服务");
            log("启动失败：无障碍服务未开启");
            return;
        }
        if (nodes.isEmpty()) {
            toast("当前方案没有步骤");
            log("启动失败：没有步骤");
            return;
        }
        if (Engine.isRunning()) {
            toast("已经在运行了");
            return;
        }
        btnPlay.setText("暂停");
        setPlayIcon(true);
        setStatus("运行中", "#6FE3C4");

        AutoService.gameInForeground(this);
        String cur = AutoService.lastSeen();
        if (cur != null && !cur.isEmpty()) {
            Scripts.setGamePkg(this, cur);
            log("已记住游戏包名：" + cur);
        } else {
            log("提示：查不到前台包名，建议授权「使用情况访问」");
        }
        updateFgPkg();

        Engine.start(this, nodes, new Engine.Sink() {
            @Override
            public void log(final String line) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        FloatService.this.log(line);
                    }
                });
            }

            @Override
            public void state(final String s) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        setStatus(s, "运行中".equals(s) ? "#6FE3C4" : "#A99CBE");
                    }
                });
            }

            @Override
            public void round(final int r) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        tvRound.setText("第 " + r + " 轮");
                    }
                });
            }
        });
    }

    private void setStatus(String s, String color) {
        if (tvStatus != null) {
            tvStatus.setText(s);
            tvStatus.setTextColor(Color.parseColor(color));
        }
    }

    private void persist() {
        Scripts.save(this, plan, nodes);
    }

    private final Runnable fgTicker = new Runnable() {
        @Override
        public void run() {
            updateFgPkg();
            ui.postDelayed(this, 1500L);
        }
    };

    private void updateFgPkg() {
        if (tvFg == null) {
            return;
        }
        AutoService.gameInForeground(this);
        String pkg = AutoService.lastSeen();
        if (pkg == null || pkg.isEmpty()) {
            tvFg.setText("前台：查不到（点此清除已记包名）");
            tvFg.setTextColor(Color.parseColor("#FF9BC0"));
            return;
        }
        String saved = Scripts.getGamePkg(this);
        String low = pkg.toLowerCase(Locale.US);
        boolean match = (!saved.isEmpty() && saved.equals(pkg))
                || low.contains("onmyoji") || low.contains("netease");
        tvFg.setText("前台：" + pkg + (match ? "  ✓" : "  ✗ 点此重设"));
        tvFg.setTextColor(Color.parseColor(match ? "#6FE3C4" : "#FF9BC0"));
    }

    private void updatePlanText() {
        String s = (plan + 1) + "/" + Scripts.NAMES.length + " " + Scripts.NAMES[plan];
        if (tvPlan != null) {
            tvPlan.setText(s);
        }
    }

    private void syncParams() {
        if (tvGap == null) {
            return;
        }
        tvGap.setText(Scripts.getGap(this) + "ms");
        tvJitter.setText(Scripts.getJitter(this) + "px");
        int l = Scripts.getLoops(this);
        tvLoop.setText(l == 0 ? "无限" : l + " 次");
        boolean fg = Scripts.getFgOnly(this);
        tvFgOnly.setText(fg ? "开" : "关");
        tvFgOnly.setTextColor(Color.parseColor(fg ? "#6FE3C4" : "#A99CBE"));
        tvAlpha.setText((int) (ALPHAS[alphaIdx] * 100) + "%");
    }

    private void log(String line) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        logBuf.add(ts + "  " + line);
        if (logBuf.size() > 300) {
            logBuf.remove(0);
        }
        if (tabIndex == 2) {
            flushLog();
        } else if (tabs[2] != null) {
            tabs[2].setText("日志 " + logBuf.size());
        }
    }

    private void flushLog() {
        if (tvLog == null) {
            return;
        }
        int from = Math.max(0, logBuf.size() - 150);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < logBuf.size(); i++) {
            sb.append(logBuf.get(i)).append('\n');
        }
        tvLog.setText(sb.toString());
        if (logScroll != null) {
            logScroll.post(new Runnable() {
                @Override
                public void run() {
                    logScroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
        if (tabs[2] != null) {
            tabs[2].setText("日志 " + logBuf.size());
        }
    }

    // ============================================================ 拖动 / 挂载

    /**
     * 拖动手柄。默认锁定——不能直接拖动，防止挂机时误触把窗口拖跑。
     * 用法：在某个位置**按住不动满 3 秒**解锁（震动一下 + 提示），解锁后手指继续
     * 移动即可拖动，松手落地并记住位置。短按（<3 秒松开）仍作为普通点击处理。
     */
    private void attachDrag(final View handle, final WindowManager.LayoutParams lp,
                            final Runnable onTap) {
        final float[] s = new float[4];
        final boolean[] moved = new boolean[1];
        final boolean[] armed = new boolean[1];
        final Handler press = new Handler(Looper.getMainLooper());
        final Runnable arm = new Runnable() {
            @Override
            public void run() {
                armed[0] = true;
                moved[0] = false;
                s[2] = lp.x;
                s[3] = lp.y;
                vibrate(45);
                toast("已解锁，拖动到位置后松手");
            }
        };
        handle.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        // 落在标题栏的按钮上（— / ×）时，放行给系统处理点击，不拦截。
                        if (insideClickableChild(handle, e.getRawX(), e.getRawY())) {
                            return false;
                        }
                        s[0] = e.getRawX();
                        s[1] = e.getRawY();
                        s[2] = lp.x;
                        s[3] = lp.y;
                        moved[0] = false;
                        armed[0] = false;
                        press.removeCallbacks(arm);
                        press.postDelayed(arm, 3000L);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - s[0]);
                        int dy = (int) (e.getRawY() - s[1]);
                        if (!armed[0]) {
                            // 还没解锁：滑动超阈值就取消长按（视为误触），不拖动
                            if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                                press.removeCallbacks(arm);
                            }
                            return true;
                        }
                        if (!moved[0] && Math.abs(dx) < 4 && Math.abs(dy) < 4) {
                            return true;
                        }
                        moved[0] = true;
                        int maxY = lp.height > 0 ? screenH - lp.height : screenH - dp(40);
                        lp.x = clamp((int) (s[2] + dx), 0, Math.max(0, screenW - lp.width));
                        lp.y = clamp((int) (s[3] + dy), 0, Math.max(0, maxY));
                        try {
                            wm.updateViewLayout(hostOf(handle), lp);
                        } catch (Throwable ignored) {
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        press.removeCallbacks(arm);
                        if (armed[0] && moved[0]) {
                            savePos(lp);
                        } else if (!armed[0] && !moved[0] && onTap != null) {
                            onTap.run();
                        }
                        armed[0] = false;
                        moved[0] = false;
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    /** 震动反馈（拖动解锁时使用）。 */
    private void vibrate(long ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(ms);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 触摸点是否落在 handle 的某个可点击子控件（如标题栏的 —/× 按钮）内。 */
    private boolean insideClickableChild(View handle, float rx, float ry) {
        if (!(handle instanceof ViewGroup)) {
            return false;
        }
        ViewGroup g = (ViewGroup) handle;
        int[] loc = new int[2];
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (!c.isClickable()) {
                continue;
            }
            c.getLocationOnScreen(loc);
            if (rx >= loc[0] && rx <= loc[0] + c.getWidth()
                    && ry >= loc[1] && ry <= loc[1] + c.getHeight()) {
                return true;
            }
        }
        return false;
    }

    /** 手柄是子 View，真正挂在 WindowManager 上的是它的根布局。 */
    private View hostOf(View handle) {
        if (handle == bubble || handle == panel) {
            return handle;
        }
        View p = (View) handle.getParent();
        while (p != null) {
            if (p == panel || p == bubble) {
                return p;
            }
            p = (View) p.getParent();
        }
        return panel != null ? panel : bubble;
    }

    private int clamp(int v, int lo, int hi) {
        if (hi < lo) {
            hi = lo;
        }
        return Math.max(lo, Math.min(hi, v));
    }

    private void attach(View v, WindowManager.LayoutParams lp) {
        if (v == null || lp == null || v.getParent() != null) {
            return;
        }
        try {
            wm.addView(v, lp);
        } catch (Throwable ignored) {
        }
    }

    private void detach(View v) {
        if (v == null || v.getParent() == null) {
            return;
        }
        try {
            wm.removeView(v);
        } catch (Throwable ignored) {
        }
    }

    private int[] defaultPos(String kx, String ky, int w, int h) {
        SharedPreferences p = Scripts.prefs(this);
        int x = p.getInt(kx, Integer.MIN_VALUE);
        int y = p.getInt(ky, Integer.MIN_VALUE);
        // 一次性迁移：之前默认靠右，统一翻到左边；之后用户可自由拖动并保存。
        boolean migrated = p.getBoolean("leftMigrated", false);
        if (x == Integer.MIN_VALUE) {
            x = dp(8);
        } else if (!migrated) {
            x = dp(8);
        }
        if (y == Integer.MIN_VALUE) {
            y = dp(120);
        }
        if (!migrated) {
            p.edit().putBoolean("leftMigrated", true).apply();
        }
        return new int[]{clamp(x, 0, Math.max(0, screenW - w)),
                clamp(y, 0, Math.max(0, screenH - h))};
    }

    private void savePos(WindowManager.LayoutParams lp) {
        boolean isBubble = (lp == lpBubble);
        Scripts.prefs(this).edit()
                .putInt(isBubble ? "bubbleX" : "panelX", lp.x)
                .putInt(isBubble ? "bubbleY" : "panelY", lp.y)
                .apply();
    }

    // ============================================================ UI 小工具

    private WindowManager.LayoutParams overlayParams(int w, int h, int gravity) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = gravity;
        return lp;
    }

    private TextView tiny(String s, float sp, String color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(Color.parseColor(color));
        tv.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        return tv;
    }

    /** 固定宽高的小方钮。用 TextView 而非 Button，避免 Material 主题的最小高度。 */
    private TextView chip(String s, boolean gold, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(10.5f);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(Color.parseColor(gold ? "#1A0A2E" : "#E8C87A"));
        tv.setBackground(getResources().getDrawable(gold ? R.drawable.btn_gold : R.drawable.btn_ghost));
        int h = dp(21);
        int w = s.length() <= 1 ? dp(21) : (s.length() == 2 ? dp(28) : dp(36));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.rightMargin = dp(2);
        tv.setLayoutParams(lp);
        if (l != null) {
            tv.setOnClickListener(l);
        }
        return tv;
    }

    /** 等分宽度的扁钮，用于一行多个按钮。 */
    private TextView chipWide(String s, boolean gold, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(10.5f);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(Color.parseColor(gold ? "#1A0A2E" : "#E8C87A"));
        tv.setBackground(getResources().getDrawable(gold ? R.drawable.btn_gold : R.drawable.btn_ghost));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(22), 1f);
        lp.rightMargin = dp(3);
        tv.setLayoutParams(lp);
        if (l != null) {
            tv.setOnClickListener(l);
        }
        return tv;
    }

    /** 整行的宽按钮，用于最重要的操作。 */
    private TextView chipWideBig(String s, boolean gold, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(11f);
        tv.setGravity(Gravity.CENTER);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(Color.parseColor(gold ? "#1A0A2E" : "#E8C87A"));
        tv.setBackground(getResources().getDrawable(gold ? R.drawable.btn_gold : R.drawable.btn_ghost));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(26));
        lp.bottomMargin = dp(3);
        tv.setLayoutParams(lp);
        if (l != null) {
            tv.setOnClickListener(l);
        }
        return tv;
    }

    private View paramRow(String name, TextView value, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(20)));
        TextView n = tiny(name, 10.5f, "#A99CBE", false);
        n.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        value.setGravity(Gravity.END);
        value.setWidth(dp(50));
        row.addView(n);
        row.addView(value);
        row.addView(chip("改", false, l));
        return row;
    }

    private View hair() {
        View v = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.topMargin = dp(3);
        lp.bottomMargin = dp(3);
        v.setLayoutParams(lp);
        v.setBackgroundColor(Color.parseColor("#2BE8C87A"));
        return v;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int[] sc = Engine.screen(this);
        if (sc[0] == screenW && sc[1] == screenH) {
            return;
        }
        screenW = sc[0];
        screenH = sc[1];
        boolean wasPanel = panel != null && panel.getParent() != null;
        detach(bubble);
        detach(panel);
        buildBubble();
        buildPanel();
        if (wasPanel) {
            showPanel();
        } else {
            showBubble();
        }
    }
}
