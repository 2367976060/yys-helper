package com.gesture.helper;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_CAPTURE = 1001;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView tvAccState;
    private TextView tvOverlayState;
    private TextView tvCaptureState;
    private TextView tvRunState;
    private TextView tvBatState;
    private TextView tvUseState;
    private Button btnAcc;
    private Button btnOverlay;
    private Button btnCapture;
    private Button btnBat;
    private Button btnUse;
    private Button btnLaunch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildRoot());
        askNotification();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void askNotification() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 77);
        }
    }

    /** 尽量一步跳到本服务的开关页，跳不过去再退回总列表。 */
    private void openServicePage() {
        ComponentName cn = new ComponentName(this, AutoService.class);
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Intent it = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
                it.putExtra("extra_component_name", cn.flattenToString());
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it);
                Toast.makeText(this, "找「手势辅助服务」，把开关打开", Toast.LENGTH_LONG).show();
                return;
            } catch (Throwable ignored) {
            }
        }
        try {
            Intent it = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Bundle b = new Bundle();
            b.putString(":settings:fragment_args_key", cn.flattenToString());
            it.putExtra(":settings:fragment_args_key", cn.flattenToString());
            it.putExtra(":settings:show_fragment_args", b);
            startActivity(it);
        } catch (Throwable t) {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }
        Toast.makeText(this, "在「已下载服务」里找「手势辅助服务」", Toast.LENGTH_LONG).show();
    }

    private boolean usageGranted() {
        if (Build.VERSION.SDK_INT < 21) {
            return true;
        }
        try {
            android.app.AppOpsManager aom =
                    (android.app.AppOpsManager) getSystemService(APP_OPS_SERVICE);
            int mode = aom.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), getPackageName());
            return mode == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void copyServiceName() {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("service", "手势辅助服务"));
            Toast.makeText(this, "已复制：手势辅助服务", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败，请手动记下：手势辅助服务", Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ UI

    private View buildRoot() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0B0416"));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(18), pad, dp(24));

        // 标题区
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View badge = badge();
        head.addView(badge);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(14), 0, 0, 0);
        TextView t1 = text("阴阳师助手", 23, "#F4ECDE", true);
        TextView t2 = text("本地自动化 · 模拟点击挂机", 13, "#A99CBE", false);
        titles.addView(t1);
        titles.addView(t2);
        head.addView(titles);
        root.addView(head);

        root.addView(gap(dp(16)));

        // 状态条
        tvRunState = text("状态：未启动", 13, "#E8C87A", false);
        root.addView(cardSimple(tvRunState));
        root.addView(gap(dp(12)));

        // 权限卡片
        LinearLayout accCard = buildCard(
                "① 无障碍服务（必需）",
                "脚本靠它把点击注入到游戏里。\n"
                        + "注意：为避免被游戏针对性扫描，本服务在系统里的名字是「手势辅助服务」，"
                        + "不是「阴阳师助手」。点下面的按钮可直接跳到它的开关页。\n"
                        + "如果列表里压根没有这一项，重启一次手机再来看。",
                "打开无障碍总列表", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    }
                });
        tvAccState = (TextView) accCard.getChildAt(1);
        btnAcc = (Button) accCard.getChildAt(3);

        Button accDetail = ghostButton("一键打开本服务开关页");
        accDetail.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openServicePage();
            }
        });
        accCard.addView(accDetail);

        Button accCopy = ghostButton("复制服务名「手势辅助服务」");
        accCopy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyServiceName();
            }
        });
        accCard.addView(accCopy);

        root.addView(accCard);
        root.addView(gap(dp(10)));

        LinearLayout ovCard = buildCard(
                "② 悬浮窗权限（必需）",
                "悬浮控制面板需要显示在其他应用之上。开启后回到游戏就能看到浮动按钮。",
                "去开启", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent it = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(it);
                    }
                });
        tvOverlayState = (TextView) ovCard.getChildAt(1);
        btnOverlay = (Button) ovCard.getChildAt(3);
        root.addView(ovCard);
        root.addView(gap(dp(10)));

        LinearLayout capCard = buildCard(
                "③ 屏幕取色（可选）",
                "授权后脚本能判断战斗是否结束、结算界面是否出现。不授权也能用，会退化成纯定时点击。",
                "去授权", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        MediaProjectionManager mpm = (MediaProjectionManager)
                                getSystemService(MEDIA_PROJECTION_SERVICE);
                        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
                    }
                });
        tvCaptureState = (TextView) capCard.getChildAt(1);
        btnCapture = (Button) capCard.getChildAt(3);
        root.addView(capCard);
        root.addView(gap(dp(10)));

        LinearLayout batCard = buildCard(
                "④ 后台保活（建议）",
                "系统省电策略会在锁屏或后台杀掉服务，导致挂机中断。把本应用设为「不优化」可显著减少掉线。",
                "去设置", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (Build.VERSION.SDK_INT >= 23) {
                            try {
                                startActivity(new Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:" + getPackageName())));
                            } catch (Exception e) {
                                startActivity(new Intent(
                                        Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                            }
                        }
                    }
                });
        tvBatState = (TextView) batCard.getChildAt(1);
        btnBat = (Button) batCard.getChildAt(3);
        root.addView(batCard);
        root.addView(gap(dp(10)));

        LinearLayout useCard = buildCard(
                "⑤ 使用情况访问（可选，但建议开）",
                "阴阳师是全屏 Unity 游戏，不产生系统窗口事件，脚本只能靠这个权限主动查询"
                        + "「现在哪个 App 在前台」。\n"
                        + "不开也能跑：脚本会自动跳过前台保护。开了才能做到切出去自动暂停、回来继续。",
                "去授权", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "请手动在设置里搜索「使用情况访问」",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
        tvUseState = (TextView) useCard.getChildAt(1);
        btnUse = (Button) useCard.getChildAt(3);
        root.addView(useCard);
        root.addView(gap(dp(16)));

        btnLaunch = goldButton("启动悬浮控制台");
        btnLaunch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!AutoService.ready() || !Settings.canDrawOverlays(MainActivity.this)) {
                    Toast.makeText(MainActivity.this, "请先把 ① 和 ② 打开", Toast.LENGTH_LONG).show();
                    return;
                }
                Intent it = new Intent(MainActivity.this, FloatService.class);
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(it);
                } else {
                    startService(it);
                }
                Toast.makeText(MainActivity.this, "已启动，回到游戏即可看到悬浮按钮", Toast.LENGTH_SHORT).show();
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        refresh();
                    }
                }, 400);
            }
        });
        root.addView(btnLaunch);

        root.addView(gap(dp(10)));

        Button stopBtn = ghostButton("停止脚本并关闭悬浮窗");
        stopBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Engine.stop();
                stopService(new Intent(MainActivity.this, FloatService.class));
                Toast.makeText(MainActivity.this, "已关闭", Toast.LENGTH_SHORT).show();
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        refresh();
                    }
                }, 400);
            }
        });
        root.addView(stopBtn);

        root.addView(gap(dp(18)));
        root.addView(helpCard());
        root.addView(gap(dp(12)));
        root.addView(warnCard());

        scroll.addView(root);
        return scroll;
    }

    private View badge() {
        TextView v = new TextView(this);
        v.setText("御");
        v.setGravity(Gravity.CENTER);
        v.setTextSize(22);
        v.setTextColor(Color.parseColor("#1A0A2E"));
        v.setTypeface(Typeface.DEFAULT_BOLD);
        int s = dp(52);
        v.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColors(new int[]{Color.parseColor("#F1DC9E"), Color.parseColor("#C9A75A")});
        v.setBackground(g);
        return v;
    }

    private LinearLayout buildCard(String title, String desc, String btnText,
                                   View.OnClickListener onClick) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(Color.parseColor("#22103B"));
        g.setStroke(1, Color.parseColor("#40E8C87A"));
        card.setBackground(g);

        TextView tvTitle = text(title, 15, "#E8C87A", true);
        TextView tvState = text("状态：检查中", 12, "#A99CBE", false);
        TextView tvDesc = text(desc, 12, "#8E82A6", false);
        tvDesc.setLineSpacing(0, 1.25f);
        Button b = ghostButton(btnText);
        b.setOnClickListener(onClick);

        card.addView(tvTitle);
        card.addView(tvState);
        card.addView(tvDesc);
        card.addView(b);
        return card;
    }

    private View cardSimple(View inner) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setColor(Color.parseColor("#150826"));
        g.setStroke(1, Color.parseColor("#2BE8C87A"));
        card.setBackground(g);
        card.addView(inner);
        return card;
    }

    private View helpCard() {
        TextView tv = new TextView(this);
        SpannableString ss = new SpannableString(
                "使用步骤\n"
                        + "1. 打开上面 ①②③ 三项权限；\n"
                        + "2. 点「启动悬浮控制台」，回到阴阳师（横屏）；\n"
                        + "3. 悬浮球展开后选择方案，按开始；\n"
                        + "4. 坐标不准时用「取点」重录一次，脚本会自动保存。\n\n"
                        + "提示：内置坐标是按 2408×1080 横屏推算的模板，不同机型 / 版本 UI 会有偏差，"
                        + "建议第一次用时用取点功能校准关键按钮。");
        tv.setText(ss);
        tv.setTextSize(12.5f);
        tv.setTextColor(Color.parseColor("#B9ACD0"));
        tv.setLineSpacing(0, 1.35f);
        return cardSimple(tv);
    }

    private View warnCard() {
        TextView tv = new TextView(this);
        SpannableString ss = new SpannableString(
                "风险提示\n"
                        + "本工具只是模拟人手点击，不修改游戏数据、不读取账号。但自动化挂机通常违反游戏用户协议，"
                        + "存在被检测、警告乃至封号的风险。请控制使用频率，不要 24 小时不间断运行，不要用于代练牟利。");
        tv.setText(ss);
        tv.setTextSize(12.5f);
        tv.setTextColor(Color.parseColor("#FFB3B3"));
        tv.setLineSpacing(0, 1.35f);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setColor(Color.parseColor("#2A0E1E"));
        g.setStroke(1, Color.parseColor("#4DFF6B6B"));
        card.setBackground(g);
        card.addView(tv);
        return card;
    }

    // ------------------------------------------------------------ helpers

    private TextView text(String s, float sp, String color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(Color.parseColor(color));
        tv.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        tv.setSingleLine(false);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        return tv;
    }

    private Button goldButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextColor(Color.parseColor("#1A0A2E"));
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setBackground(getResources().getDrawable(R.drawable.btn_gold));
        b.setPadding(0, dp(14), 0, dp(14));
        return b;
    }

    private Button ghostButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextColor(Color.parseColor("#E8C87A"));
        b.setTextSize(13.5f);
        b.setAllCaps(false);
        b.setBackground(getResources().getDrawable(R.drawable.btn_ghost));
        int p = dp(12);
        b.setPadding(dp(10), p, dp(10), p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        lp.gravity = Gravity.END;
        b.setLayoutParams(lp);
        return b;
    }

    private View gap(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, h));
        return v;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    // ------------------------------------------------------------- state

    private void refresh() {
        boolean acc = AutoService.ready();
        boolean ov = Settings.canDrawOverlays(this);
        boolean cap = ScreenGrabber.pending() != null;

        tvAccState.setText(acc ? "状态：已开启 ✓" : "状态：未开启");
        tvAccState.setTextColor(Color.parseColor(acc ? "#6FE3C4" : "#FF9BC0"));
        btnAcc.setVisibility(acc ? View.GONE : View.VISIBLE);

        tvOverlayState.setText(ov ? "状态：已开启 ✓" : "状态：未开启");
        tvOverlayState.setTextColor(Color.parseColor(ov ? "#6FE3C4" : "#FF9BC0"));
        btnOverlay.setVisibility(ov ? View.GONE : View.VISIBLE);

        tvCaptureState.setText(cap ? "状态：已授权 ✓（重启悬浮窗后生效）" : "状态：未授权（可跳过）");
        tvCaptureState.setTextColor(Color.parseColor(cap ? "#6FE3C4" : "#8E82A6"));
        btnCapture.setVisibility(cap ? View.GONE : View.VISIBLE);

        boolean bat = true;
        if (Build.VERSION.SDK_INT >= 23) {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            bat = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        }
        tvBatState.setText(bat ? "状态：已放行 ✓" : "状态：会被省电策略杀掉");
        tvBatState.setTextColor(Color.parseColor(bat ? "#6FE3C4" : "#FF9BC0"));
        btnBat.setVisibility(bat ? View.GONE : View.VISIBLE);

        boolean use = usageGranted();
        tvUseState.setText(use ? "状态：已授权 ✓" : "状态：未授权（不开也能跑）");
        tvUseState.setTextColor(Color.parseColor(use ? "#6FE3C4" : "#8E82A6"));
        btnUse.setVisibility(use ? View.GONE : View.VISIBLE);

        tvRunState.setText("状态：" + (Engine.isRunning()
                ? (Engine.isPaused() ? "已暂停" : "脚本运行中")
                : "未启动"));
        btnLaunch.setText(acc && ov ? "重新启动悬浮控制台" : "启动悬浮控制台");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE && resultCode == Activity.RESULT_OK && data != null) {
            ScreenGrabber.keep(data);
            Toast.makeText(this, "取色授权成功", Toast.LENGTH_SHORT).show();
        }
    }
}
