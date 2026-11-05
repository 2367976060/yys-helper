package com.gesture.helper;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.util.List;
import java.util.Random;

/**
 * 脚本执行引擎：单线程顺序执行步骤，支持循环、暂停、停止。
 * 归一化坐标在这里换算成真实像素，并叠加随机偏移。
 */
public final class Engine {

    public interface Sink {
        void log(String line);

        void state(String s);

        void round(int r);
    }

    private static Thread worker;
    private static volatile boolean running;
    private static volatile boolean paused;
    private static volatile boolean stopped;
    /** 由 start() 从 DisplayMetrics 写入，用于把判定半径从 dp 换算成像素。 */
    private static float density = 2.5f;
    /** 抖动专用随机源（拖屏等工具方法用，不参与步骤执行）。 */
    private static final Random RNG = new Random();

    private static int dpToPx(int dp) {
        return Math.round(dp * density);
    }

    /**
     * 探索地图横向扫描器。
     *
     * 【v1.13 为什么必须要有它】
     * 阴阳师探索主线是**横屏可拖动的大地图**，一屏只显示地图的一段。
     * 旧版本在全屏找不到图标时就认为"这一批怪打完了"，直接转去找首领——
     * 结果画面外左右两侧还有没打的怪，首领自然也不会出现，脚本就空转到超时。
     * 现在找不到就先拖动画面翻屏，两侧都翻干净仍没有，才算真的清场。
     */
    private static final class Scan {
        /** 每侧最多翻几次（每次约 0.6 屏宽，即每侧约 1.8 屏）。 */
        private static final int PER_SIDE = 3;
        /** 1 = 向右看（手指往左拖），-1 = 向左看。 */
        private int dir = 1;
        private int used = 0;

        void reset() {
            dir = 1;
            used = 0;
        }

        /** 下一次翻屏方向；两侧都翻完了返回 0。 */
        int next() {
            if (used >= PER_SIDE) {
                if (dir == 1) {
                    dir = -1;
                    used = 0;
                } else {
                    return 0;
                }
            }
            used++;
            return dir;
        }

        /** 已经拖到头了（画面没变化），本侧直接算翻完。 */
        void atEdge() {
            used = PER_SIDE;
        }

        String text() {
            return "第 " + used + "/" + PER_SIDE + " 屏" + (dir > 0 ? "（向右）" : "（向左）");
        }

        int dir() {
            return dir;
        }

        int used() {
            return used;
        }
    }

    /**
     * 在探索地图上横向拖一屏。返回 false 表示画面没动（已经拖到地图边界）。
     * 拖动点放在画面中部（y≈56%），避开顶部横幅和底部「固定阵容/自动挑战」按钮，
     * 也不会落进左侧悬浮窗。
     */
    private static boolean dragMap(int dir, int w, int h) {
        Vision.Frame before = ScreenGrabber.grabGray(Vision.SCALE);
        float y = h * 0.560f + (RNG.nextInt(24) - 12);
        float fromX = dir > 0 ? w * 0.820f : w * 0.180f;
        float toX = dir > 0 ? w * 0.180f : w * 0.820f;
        AutoService.swipe(fromX, y, toX, y, 320 + RNG.nextInt(120));
        pauseLoop(720);
        Vision.Frame after = ScreenGrabber.grabGray(Vision.SCALE);
        if (before == null || after == null) {
            // 取不到帧就别乱下结论，当成"动过了"，让主循环继续判
            return true;
        }
        int n = Math.min(before.gray.length, after.gray.length);
        long sum = 0;
        int cnt = 0;
        for (int i = 0; i < n; i += 5) {
            sum += Math.abs(before.gray[i] - after.gray[i]);
            cnt++;
        }
        // 实测：换一个视角整屏平均差 37~44；结算/对话界面只有局部动效，差 < 6。
        // 阈值取 6，宁可把"没动"误判成"动了"（多翻一屏），也不要反过来误判清场。
        return cnt > 0 && sum / (double) cnt > 6.0;
    }

    /**
     * 战后小宝箱：阴阳师站在中间，宝箱是它左下方那个「圆形深底 + 橙金色箱体」的图标。
     * 不点它就一直停在这个画面回不到探索地图（用户说的「卡在那不动」）。
     * 位置会随机型偏几十像素，所以按模板找，命中就把图标中心点掉。
     *
     * @return 是否点到了
     */
    private static Vision.Hit findChest(Vision.Frame fr) {
        Vision.Hit h = chestHit(fr);
        return (h != null && h.score >= CHEST_TH) ? h : null;
    }

    /** 画面上最像宝箱的那一个（不管过不过阈值），主循环要拿它的分数写日志。 */
    private static Vision.Hit chestHit(Vision.Frame fr) {
        int[][] tpls = Templates.get(3);
        if (fr == null || tpls == null || tpls.length == 0) {
            return null;
        }
        Vision.Hit h = Vision.searchMulti(fr, tpls, 1, -2f, true)[0];
        return (h != null && h.size > 0) ? h : null;
    }

    /**
     * 一次找出画面上所有宝箱（同屏可能同时摆着好几个，每个都要点开才回地图）。
     *
     * 【v1.20 为什么改成全屏 + 0.80】
     * 上一版只在 misses>=1 时找、阈值 0.85、还限定在画面中下部。用户反馈
     * 「还是有宝箱不点击，然后就卡着这个界面」，拿他那张卡住的截图复算：
     * 宝箱在 (1444,716)、0.919 —— 说明模板本身没问题，是流程没走到那一步。
     * 阈值重新扫了全部 7 张实机截图：真宝箱 0.861 / 0.919，非宝箱界面最高
     * 0.755（章节列表 0.755、怪物地图 0.754），0.80 上下都有 0.05 以上余量，
     * 所以阈值降到 0.80、搜索区域放到整屏，能多接住一点光照差一点的宝箱。
     */
    private static final float CHEST_TH = 0.80f;

    private static Vision.Hit[] findChests(Vision.Frame fr, int max) {
        int[][] tpls = Templates.get(3);
        if (fr == null || tpls == null || tpls.length == 0 || max <= 0) {
            return new Vision.Hit[0];
        }
        Vision.Hit[] all = Vision.searchMulti(fr, tpls, max, CHEST_TH, true);
        int n = 0;
        for (Vision.Hit h : all) {
            if (h != null && h.size > 0 && h.score >= CHEST_TH) {
                n++;
            }
        }
        if (n == 0) {
            return new Vision.Hit[0];
        }
        Vision.Hit[] out = new Vision.Hit[n];
        System.arraycopy(all, 0, out, 0, n);
        return out;
    }

    /** 宝箱模板在当前画面的最高分，诊断用（找不到宝箱时也要知道差多少）。 */
    private static float chestTop(Vision.Frame fr) {
        int[][] tpls = Templates.get(3);
        if (fr == null || tpls == null || tpls.length == 0) {
            return 0f;
        }
        return Vision.match(fr, tpls, null);
    }

    /**
     * 章节选择界面右下角那块「探索」木牌。
     * 打完首领如果没有宝箱，游戏会自己退回这个界面；不点「探索」就永远停在这儿，
     * 而地图拖不动、也找不到怪，脚本只能干等。
     *
     * @return 是否点到了
     */
    private static Vision.Hit findChapter(Vision.Frame fr) {
        return findChapter(fr, 0.62f);
    }

    private static Vision.Hit findChapter(Vision.Frame fr, float th) {
        int[][] tpls = Templates.get(4);
        if (fr == null || tpls == null || tpls.length == 0) {
            return null;
        }
        // 实测：「探索」木牌在章节详情页 0.716~0.887；限定右下角后，探索地图只有
        // 0.419~0.449、战斗 0.36~0.38、宝箱界面 0.356。
        // 上一版线画在 0.70，而实机有一张只有 0.716——只差 0.016，光照一变就点不到，
        // 人就会一直卡在章节页。现在降到 0.62，和地图（0.449）之间仍留 0.17 的余量。
        // 限定区域仍是关键：全屏搜时地图能到 0.591，会误点右下角。
        return Vision.searchInRect(fr, tpls, 0.58f, 0.72f, 1.0f, 1.0f, th, true);
    }

    /**
     * 【v1.21】「确认退出？」白色对话框。
     *
     * 上一版想用返回键把"点错开的面板"退掉，结果在游戏里按返回直接弹出了
     * 退出确认框（用户截图里那个白色 取消/确认），脚本又认不出它，彻底卡死。
     * 返回键已经彻底删掉，这里再兜一道：万一又冒出来，自动点「取消」继续。
     *
     * 判据：屏幕正中一大块近乎纯白的矩形。实测这个框中央纯白占比 97.9%，
     * 正常画面（地图/章节/庭院/宝箱）最高只有 16.9%，阈值取 70% 很安全。
     */
    private static boolean quitDialog(Vision.Frame fr) {
        if (fr == null) {
            return false;
        }
        int x0 = Math.round(0.46f * fr.w);
        int x1 = Math.round(0.75f * fr.w);
        int y0 = Math.round(0.26f * fr.h);
        int y1 = Math.round(0.66f * fr.h);
        int bright = 0;
        int cnt = 0;
        for (int y = y0; y < y1; y += 2) {
            int base = y * fr.w;
            for (int x = x0; x < x1; x += 2) {
                cnt++;
                if (fr.gray[base + x] > 225) {
                    bright++;
                }
            }
        }
        return cnt > 0 && bright * 100 >= cnt * 70;
    }

    /**
     * 【v1.22】庭院主界面。
     *
     * 打完首领经常不是回到「章节选择」页，而是直接退回庭院：左边站着阴阳师、
     * 右边是「主线/玩法」章节面板、左上角挂着妖气/年兽的倒计时头像。
     * 这个界面上一个怪都没有，旧逻辑只会一路 miss、拖屏、再 miss，直到超时。
     *
     * 判据用底部那排金色玩法按钮（御魂/觉醒材料/妖气探索/结界突破/式神委派/
     * 秘闻副本/地域鬼王）：它们常年不动、颜色饱和度高。实测金色像素占比
     * 庭院 19.5%~25.6%，探索地图 0.0%、战斗 0.0%、喂食面板 0.0%、卡住的宝箱页 0.0%，
     * 取 8% 分界，两边各留 11 个点以上的余量。
     */
    private static boolean yardScreen(Vision.Frame fr) {
        if (fr == null || fr.rgb == null) {
            return false;
        }
        int x0 = Math.round(0.05f * fr.w);
        int x1 = Math.round(0.66f * fr.w);
        int y0 = Math.round(0.86f * fr.h);
        int gold = 0;
        int cnt = 0;
        for (int y = y0; y < fr.h; y += 2) {
            int base = y * fr.w;
            for (int x = x0; x < x1; x += 2) {
                int p = fr.rgb[base + x];
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                cnt++;
                if (r > 120 && g > 90 && b < 90 && r - b > 55) {
                    gold++;
                }
            }
        }
        return cnt > 0 && gold * 100 >= cnt * 8;
    }

    /**
     * 庭院右侧章节面板里三行章节的落点（1920x861 基准量出来的）。
     * 从最下面一行开始点：列表一般停在最新章，最下面那行就是正在刷的那一章。
     * 三行章名文字实测中心 y = 309 / 436 / 562（比例 0.359 / 0.506 / 0.652），
     * 行距 127px；x 取 1600（比例 0.833），落在章名文字正中间。
     */
    private static final float[][] YARD_ROWS = {
            {0.833f, 0.652f},
            {0.833f, 0.506f},
            {0.833f, 0.359f},
    };

    /**
     * 在庭院里点开一章，再点「探索」进图。
     * 每一行点完都抓帧复核：能看到「探索」木牌才算真的进去了，
     * 点空了就换上一行——绝不盲点到底。
     */
    private static boolean enterFromYard(Sink sink, int w, int h) {
        for (int i = 0; i < YARD_ROWS.length && !stopped; i++) {
            float[] p = YARD_ROWS[i];
            Vision.Frame before = ScreenGrabber.grab(Vision.SCALE);
            if (before == null) {
                return false;
            }
            AutoService.tap(p[0] * w, p[1] * h, 55 + RNG.nextInt(25));
            pauseLoop(1900);
            Vision.Frame f2 = ScreenGrabber.grab(Vision.SCALE);
            if (f2 == null) {
                return false;
            }
            Vision.Hit chap = findChapter(f2);
            if (chap != null) {
                tapHit(chap);
                sink.log("  → 在庭院点开章节，再点「探索」进图（第 " + (YARD_ROWS.length - i) + " 行）");
                pauseLoop(2800);
                return true;
            }
            if (!frameChanged(before.gray, f2.gray)) {
                continue;
            }
            // 画面变了却没看到「探索」木牌：多半还在加载/开了个子面板，
            // 交给主循环下一轮重新认，别再继续盲点别的行。
            sink.log("  → 庭院里点开了一个界面（暂未看到「探索」），下一轮再认");
            pauseLoop(1200);
            return true;
        }
        return false;
    }

    /** 点命中点的中心（Hit 存的是左上角模板坐标）。 */
    private static void tapHit(Vision.Hit hit) {
        int half = hit.size / 2;
        AutoService.tap((hit.x + half) * Vision.SCALE, (hit.y + half) * Vision.SCALE, 55 + RNG.nextInt(20));
    }

    /**
     * 战后回到地图。宝箱领完后游戏会自己退回「章节选择」界面，不点右下那块
     * 「探索」木牌就没有怪可打——这一步是整局循环的关键。
     * 按「章节 → 宝箱」的顺序收拾，最多 4 轮；两样都没有就认为已经在地图上。
     */
    private static void ensureOnMap(Sink sink, int sw, int sh) {
        int chapTaps = 0;
        int chestTaps = 0;
        // 战后可能连着掉好几个宝箱，每个都要点开才回得到地图，所以轮数放宽到 6，
        // 宝箱最多点 4 个、章节最多点 2 次。
        for (int i = 0; i < 6 && !stopped; i++) {
            Vision.Frame fr = ScreenGrabber.grab(Vision.SCALE);
            if (fr == null) {
                return;
            }
            Vision.Hit chap = chapTaps < 2 ? findChapter(fr) : null;
            if (chap != null) {
                chapTaps++;
                tapHit(chap);
                sink.log("  → 回到章节选择界面，点「探索」重新进图（还是这一关）");
                pauseLoop(2600);
                continue;
            }
            // 【v1.22】打完首领常常直接退回庭院（不是章节选择页）。
            // 不在这里点开章节，就会卡在庭院里一直找怪。
            if (chapTaps < 2 && yardScreen(fr)) {
                chapTaps++;
                if (enterFromYard(sink, sw, sh)) {
                    continue;
                }
            }
            Vision.Hit chest = chestTaps < 4 ? findChest(fr) : null;
            if (chest != null) {
                chestTaps++;
                tapHit(chest);
                sink.log("  → 点开战后宝箱领奖（第 " + chestTaps + " 个）");
                pauseLoop(1500);
                continue;
            }
            if (chestTaps > 0) {
                sink.log("  → 宝箱都领完了（共 " + chestTaps + " 个）");
            }
            return;
        }
    }

    /** 两帧灰度差得够不够多（判断"点了以后画面换没换"）。 */
    private static boolean frameChanged(int[] a, int[] b) {
        if (a == null || b == null || a.length != b.length) {
            return true;
        }
        long sum = 0;
        int cnt = 0;
        for (int i = 0; i < a.length; i += 3) {
            sum += Math.abs(a[i] - b[i]);
            cnt++;
        }
        return cnt > 0 && sum / (double) cnt > 6.0;
    }

    private static String labelOf(Model.Node n) {
        return n == null ? "" : n.label();
    }

    public static boolean isRunning() {
        return running;
    }

    public static boolean isPaused() {
        return paused;
    }

    public static void setPaused(boolean p) {
        paused = p;
    }

    public static void stop() {
        stopped = true;
    }

    public static int[] screen(Context c) {
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        if (wm != null) {
            wm.getDefaultDisplay().getRealMetrics(dm);
            return new int[]{dm.widthPixels, dm.heightPixels, dm.densityDpi};
        }
        return new int[]{2400, 1080, 320};
    }

    public static void start(final Context c, final List<Model.Node> nodes, final Sink sink) {
        if (running) {
            return;
        }
        stopped = false;
        paused = false;
        running = true;
        final int[] sc = screen(c);
        // 横屏游戏：一律「长边当宽、短边当高」。
        // 这里是模板缩放的基准：如果启动那一刻手机还是竖屏（sc[0]=1080），
        // 模板会按 1080 缩小成 15px 的糊图，而画面里图标是 33px，识别永远掉到 40%。
        final int w = Math.max(sc[0], sc[1]);
        final int h = Math.min(sc[0], sc[1]);
        density = sc[2] / 160f;
        final int loops = Scripts.getLoops(c);
        final int jitter = Scripts.getJitter(c);
        final int gap = Scripts.getGap(c);
        final boolean fgOnly = Scripts.getFgOnly(c);
        final Random rnd = new Random();
        // 先把真实图标模板按当前屏幕宽度加载好，识别才有数据可用
        Templates.load(c, w);
        beat("刚启动");
        startWatchdog(sink);

        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    sink.state("运行中");
                    int round = 0;
                    while (!stopped && (loops <= 0 || round < loops)) {
                        round++;
                        sink.round(round);
                        sink.log("—— 第 " + round + " 轮 ——");
                        for (Model.Node n : nodes) {
                            if (stopped) {
                                break;
                            }
                            waitIfPaused();
                            if (stopped) {
                                break;
                            }
                            if (fgOnly && !waitForeground(c, sink)) {
                                break;
                            }
                            exec(n, w, h, jitter, rnd, sink);
                            if (gap > 0) {
                                sleep(wobble(gap, rnd));
                            }
                        }
                    }
                    sink.state(stopped ? "已停止" : "已完成 " + round + " 轮");
                    sink.log(stopped ? "已手动停止" : "全部完成，共 " + round + " 轮");
                } catch (Throwable t) {
                    sink.state("异常：" + t.getMessage());
                    sink.log("错误 " + t);
                } finally {
                    running = false;
                    stopped = false;
                    fgCached = null;
                    fgWarned = false;
                }
            }
        }, "yys-engine");
        worker.start();
    }

    /** 给固定延时加 ±15% 的抖动，节奏更像人手。 */
    private static long wobble(long base, Random rnd) {
        if (base <= 0) {
            return 0;
        }
        long delta = Math.max(1, base * 15 / 100);
        return base - delta + (rnd == null ? 0 : rnd.nextInt((int) (2 * delta)));
    }

    private static void waitIfPaused() {
        while (paused && !stopped) {
            sleep(200);
        }
    }

    /** 连点之间的间隔，带 ±20% 抖动，停手后立刻返回。 */
    private static void pauseLoop(int ms) {
        if (ms <= 0) {
            return;
        }
        long delta = Math.max(1, ms * 20L / 100);
        long wait = ms - delta + (long) (Math.random() * 2 * delta);
        sleep(wait);
    }

    private static long fgQueriedAt = 0;
    private static Boolean fgCached = null;
    private static boolean fgWarned = false;

    /**
     * 只在游戏处于前台时执行，切出去就挂起等待，回来继续。
     * 查不到前台信息时直接放行 —— 否则 Unity 游戏不发窗口事件会导致永远死等。
     */
    private static boolean waitForeground(final Context c, Sink sink) {
        Boolean in = queryFg(c);
        if (in == null) {
            if (!fgWarned) {
                fgWarned = true;
                sink.log("查不到前台应用，已跳过前台保护（可授权「使用情况访问」恢复）");
            }
            return true;
        }
        if (in) {
            return true;
        }
        sink.state("等待游戏回到前台");
        int waited = 0;
        while (!stopped) {
            sleep(500);
            Boolean now = queryFg(c);
            if (now == null || now) {
                break;
            }
            if (++waited > 240) {
                sink.state("等待超时，已停止");
                sink.log("长时间没检测到游戏在前台，自动停止");
                return false;
            }
            if (waited % 6 == 0) {
                sink.log("等待中，当前前台是 " + AutoService.lastSeen());
            }
        }
        if (!stopped) {
            sink.state("运行中");
        }
        return !stopped;
    }

    /** 查询带 1 秒缓存，避免每一步都去 IPC。 */
    private static Boolean queryFg(Context c) {
        long now = System.currentTimeMillis();
        if (fgCached != null && now - fgQueriedAt < 1000L) {
            return fgCached;
        }
        fgCached = AutoService.gameInForeground(c);
        fgQueriedAt = now;
        return fgCached;
    }

    private static void exec(Model.Node n, int w, int h, int jitter, Random rnd, Sink sink) {
        switch (n.type) {
            case Model.TAP: {
                float px = n.x * w + (jitter > 0 ? (rnd.nextInt(2 * jitter) - jitter) : 0);
                float py = n.y * h + (jitter > 0 ? (rnd.nextInt(2 * jitter) - jitter) : 0);
                // 按下时长也抖一下，避免每一下都长得一模一样
                boolean ok = AutoService.tap(px, py, 45 + rnd.nextInt(50));
                sink.log((ok ? "点击 " : "点击失败 ") + Model.Node.tap(n.x, n.y).label());
                break;
            }
            case Model.SLEEP: {
                long ms = wobble(n.ms, rnd);
                sink.log("等待 " + (ms >= 1000 ? (ms / 1000f) + "s" : ms + "ms"));
                sleep(ms);
                break;
            }
            case Model.SWIPE: {
                AutoService.swipe(n.x * w, n.y * h, n.x2 * w, n.y2 * h, n.ms);
                sink.log("滑动 " + n.label());
                break;
            }
            case Model.BACK:
                AutoService.back();
                sink.log("返回键");
                break;
            case Model.WAIT_COLOR:
            case Model.WAIT_NOT: {
                boolean wantMatch = n.type == Model.WAIT_COLOR;
                long deadline = System.currentTimeMillis() + Math.max(1000, n.ms);
                boolean hit = false;
                boolean noGrab = false;
                int lastPct = -1;
                int px = (int) (n.x * w);
                int py = (int) (n.y * h);
                int rad = Math.max(0, dpToPx(n.rad));
                while (System.currentTimeMillis() < deadline && !stopped) {
                    int pct = ScreenGrabber.ratio(px, py, rad, n.rgb(), n.tol);
                    if (pct < 0) {
                        noGrab = true;
                        break;
                    }
                    lastPct = pct;
                    boolean match = pct >= Math.max(1, n.pct);
                    if (match == wantMatch) {
                        hit = true;
                        break;
                    }
                    sleep(400);
                }
                if (noGrab) {
                    sink.log("取色不可用（未授权屏幕录制），「" + labelOf(n) + "」已按普通等待处理");
                    sleep(2500);
                } else {
                    sink.log((hit ? "条件达成 " : "条件超时 ") + labelOf(n)
                            + (lastPct >= 0 ? "（实测占比 " + lastPct + "%）" : ""));
                }
                break;
            }
            case Model.TAP_BURST: {
                int times = Math.max(1, n.times);
                float px = n.x * w;
                float py = n.y * h;
                sink.log("连点 " + times + " 次 · 间隔 " + n.ms + "ms");
                for (int i = 0; i < times && !stopped; i++) {
                    float jx = px + (jitter > 0 ? (rnd.nextInt(2 * jitter) - jitter) : 0);
                    float jy = py + (jitter > 0 ? (rnd.nextInt(2 * jitter) - jitter) : 0);
                    AutoService.tap(jx, jy, 40 + rnd.nextInt(40));
                    if (i == 0) {
                        sink.log("开始连点 " + labelOf(n));
                    }
                    pauseLoop(n.ms);
                }
                break;
            }
            case Model.WAIT_MATCH: {
                // 全屏搜索模板，位置无关：换主线、换地图、怪物挪位置都不影响。
                // v1.11：小怪和首领的刀形轮廓几乎一样（掩模灰度只差 0.02），
                // 单看灰度会把小怪当成首领，所以这里也要做颜色判定。
                int[][] tpl = Vision.tpl(n.tpl);
                int th = Math.max(1, n.pct);
                long deadline = System.currentTimeMillis() + Math.max(2000, n.ms);
                boolean hit = false;
                boolean noGrab = false;
                float top = -1f;
                int tries = 0;
                Scan scan = new Scan();
                while (System.currentTimeMillis() < deadline && !stopped) {
                    Vision.Frame fr = ScreenGrabber.grab(Vision.SCALE);
                    if (fr == null) {
                        noGrab = true;
                        break;
                    }
                    tries++;
                    Vision.Hit[] hits = Vision.searchMulti(fr, tpl, 2, th / 100f);
                    float best = -2f;
                    Vision.Hit unknown = null;
                    for (Vision.Hit cand : hits) {
                        if (cand.size <= 0 || cand.score < -1f) {
                            continue;
                        }
                        best = Math.max(best, cand.score);
                        int typ = Vision.classify(fr, cand);
                        if (typ == n.tpl) {
                            hit = true;
                            break;
                        }
                        if (typ < 0 && unknown == null) {
                            unknown = cand;
                        }
                    }
                    // v1.12：颜色判不出但轮廓过得去，也算出现——否则首领带着金光晕时
                    // 这一步会一直"看不到"，后面的「自己找首领打」根本没机会执行。
                    if (!hit && unknown != null) {
                        hit = true;
                    }
                    top = Math.max(top, best);
                    if (hit) {
                        break;
                    }
                    if (tries % 5 == 1) {
                        sink.log("找" + Vision.tplName(n.tpl) + "：本屏最高 "
                                + Math.round(Math.max(0, best) * 100) + "% / 需要 " + th + "%");
                    }
                    // v1.13：首领也可能站在屏幕外。光等永远等不到，得主动翻屏找。
                    if (tries % 4 == 0) {
                        int d = scan.next();
                        if (d != 0) {
                            boolean moved = dragMap(d, w, h);
                            if (moved) {
                                sink.log("本屏没" + Vision.tplName(n.tpl) + "，往"
                                        + (d > 0 ? "右" : "左") + "翻一屏 " + scan.text());
                            } else {
                                // 到边界了，本侧翻完，换另一边
                                scan.atEdge();
                            }
                        }
                    }
                    sleep(700);
                }
                if (noGrab) {
                    sink.log("取不到画面（未授权屏幕录制），这步按等待 3 秒处理");
                    sleep(3000);
                } else {
                    sink.log((hit ? "✓ 识别到" : "✗ 超时未识别到") + Vision.tplName(n.tpl)
                            + "（最高 " + Math.round(Math.max(0, top) * 100) + "% / 阈值 " + th + "%）");
                }
                break;
            }
            case Model.FIND_TAP: {
                // 自己找怪：全屏搜图标 → 判类型 → 找到就点它身上。
                //
                // v1.11 两处关键修复：
                //  1) 识别改成「圆内掩模 + 颜色判类型」。之前整块模板去比，
                //     遇到带金色光晕的图标分数只有 45%，画面里明明有怪也不打。
                //  2) 删掉了「找不到怪就补点出战」的老逻辑。它在庭院/地图界面会把
                //     右下角那个圆形按钮当成出战，实际点开的是「喂食小纸人」。
                //     现在只有真的认出目标标识才点，而且点之前再确认一次。
                int[][] tpls = Vision.tpl(n.tpl);
                String who = Vision.tplName(n.tpl);
                if (tpls == null || tpls.length == 0) {
                    sink.log("⚠ 模板没加载成功，无法识别" + who + "（重装一次应能修复）");
                    break;
                }
                int th = Math.max(1, n.pct);
                int maxTaps = Math.max(1, n.times);
                long deadline = System.currentTimeMillis() + Math.max(10000, n.ms);
                int taps = 0;
                int tries = 0;
                int misses = 0;
                float top = -1f;
                float chestTopScore = 0f;
                // 同一个宝箱连点几次都不动的计数，防止在一个点不开的图标上死磕
                int chestSame = 0;
                String lastCand = null;
                boolean noGrab = false;
                // v1.13：探索地图是横屏可拖动的大地图，一屏看不全。
                // 本屏找不到就翻屏，两侧都翻完才认为清场。
                Scan scan = new Scan();
                boolean everMoved = false;
                int stuck = 0;
                // 整张图翻遍后不立刻收工：可能只是刚打完还停在宝箱/结算页，
                // 收拾一下再从头扫一轮，最多扫 2 轮（防死循环）。
                int clearRounds = 0;
                // 【v1.22】在庭院里点章节的尝试次数（每打一场清零），防死循环
                int yardTries = 0;
                int[] fs = ScreenGrabber.frameSize();
                sink.log("自己找" + who + "：阈值 " + th + "% · 最多打 " + maxTaps
                        + " 场 · 画面 " + fs[0] + "x" + fs[1]);
                while (System.currentTimeMillis() < deadline && !stopped && taps < maxTaps) {
                    if (paused) {
                        waitIfPaused();
                    }
                    Vision.Frame fr = ScreenGrabber.grab(Vision.SCALE);
                    if (fr == null) {
                        noGrab = true;
                        break;
                    }
                    tries++;
                    // 每帧重新统计最高分：以前 top 只在动手/翻屏时才清，
                    // 日志里那个「本屏最高 100%」常常是上一屏（战斗画面）留下的旧值，
                    // 排查时严重误导。
                    top = -1f;
                    lastCand = null;
                    beat("扫描第 " + tries + " 次");
                    // 【v1.21】每轮先看有没有「确认退出」白框。用户上一版就是被它卡死的。
                    if (quitDialog(fr)) {
                        AutoService.tap(w * 0.586f, h * 0.677f, 60);
                        sink.log("  ⚠ 出现「确认退出」对话框，点「取消」继续跑");
                        pauseLoop(1300);
                        top = -1f;
                        lastCand = null;
                        continue;
                    }
                    // 【v1.18】每轮先认「这是不是地图」。
                    // 打完一场后游戏会自动退回章节选择界面（宝箱领完也一样），
                    // 不点右下那块「探索」木牌就根本没有怪可打。旧版只在连续 miss
                    // 到第 2 次时猜一次、还先猜宝箱，结果一直卡在"点一下画面"的死循环。
                    Vision.Hit chap = findChapter(fr);
                    if (chap != null) {
                        tapHit(chap);
                        sink.log("  回到章节选择界面，点「探索」重新进图（打同一关）");
                        pauseLoop(2600);
                        scan.reset();
                        everMoved = false;
                        stuck = 0;
                        clearRounds = 0;
                        misses = 0;
                        chestSame = 0;
                        top = -1f;
                        lastCand = null;
                        continue;
                    }
                    // 【v1.22】庭院主界面：右下是「主线/玩法」章节面板，底部一排金色
                    // 玩法按钮。打完首领有时会直接退回这里——一个怪都没有，
                    // 旧逻辑只会一路 miss + 拖屏空转（用户日志里「怪 0%」刷满屏）。
                    // 认出庭院就直接点开章节，再点「探索」进副本。
                    if (yardTries < 3 && yardScreen(fr)) {
                        yardTries++;
                        sink.log("  这里是庭院（章节目录 + 底部玩法按钮），先点开章节进副本");
                        if (enterFromYard(sink, w, h)) {
                            scan.reset();
                            everMoved = false;
                            stuck = 0;
                            clearRounds = 0;
                            misses = 0;
                            chestSame = 0;
                            top = -1f;
                            lastCand = null;
                            continue;
                        }
                    }
                    // 【v1.20】宝箱改成「每轮无条件先找」，不再等连续 miss。
                    // 上一版是 misses>=1 才找，而宝箱出现的瞬间画面往往还没被判定过，
                    // 于是它一路 miss 到 3 次、转去盲点章节，宝箱就被晾在那儿——
                    // 用户看到的正是「有宝箱不点，然后卡在这个界面」。
                    // 有宝箱必须先领：不领它就一直压在探索地图上，怪也不会刷新。
                    Vision.Hit chest = chestHit(fr);
                    if (chest != null && chest.score >= CHEST_TH) {
                        if (chestSame < 3) {
                            // 点之前记一帧，点完对一下：连着点三次画面都不动，
                            // 说明这个"宝箱"是点不开的（或上面压着对话框），
                            // 别在这儿死磕，交给下面的脱困逻辑去处理。
                            Vision.Frame before = ScreenGrabber.grabGray(Vision.SCALE);
                            tapHit(chest);
                            sink.log("  → 点开宝箱（" + Math.round(chest.score * 100) + "%）");
                            pauseLoop(1600);
                            Vision.Frame after = ScreenGrabber.grabGray(Vision.SCALE);
                            if (before != null && after != null
                                    && !frameChanged(before.gray, after.gray)) {
                                chestSame++;
                                if (chestSame >= 3) {
                                    sink.log("  ⚠ 宝箱点了 3 次画面都没反应，先绕过去收拾别的界面");
                                }
                            } else {
                                chestSame = 0;
                            }
                            misses = 0;
                            scan.reset();
                            everMoved = false;
                            stuck = 0;
                            top = -1f;
                            lastCand = null;
                            continue;
                        }
                    }
                    // 宝箱分数留到 miss 日志里一起打，下次卡住一眼能看出是"没认出宝箱"
                    // 还是"流程根本没走到找宝箱这一步"。
                    chestTopScore = chest == null ? 0f : chest.score;
                    Vision.Hit pick = null;
                    // v1.12：先按类型挑，挑不到再按分数兜底。
                    // 之前只要颜色判不出就直接跳过，结果「首领图标明晃晃在画面里，
                    // 分数 94% 也不打」——因为金光晕把中心颜色糊成一片，
                    // 红蓝都统计不出来，classify 返回 -1 就被当成野图 UI 丢掉了。
                    // 现在：轮廓分数够高（>= 阈值）就认，颜色只用来在两个候选之间分小怪/首领。
                    // any 模式一次要比 18 张模板（4 小怪 + 2 首领，各 3 个尺度），
                    // topN 给 2 就够：同屏最多同时判「一只小怪 + 一只首领」。
                    Vision.Hit[] hits = Vision.searchMulti(fr, tpls, n.tpl == 2 ? 2 : 3, th / 100f);
                    Vision.Hit other = null;
                    int otherTyp = -1;
                    Vision.Hit unknown = null;
                    // v1.14：小怪和首领都能打，看到哪个打哪个，首领优先。
                    // 以前必须先清完小怪才肯打首领，结果怪清了首领也常常够不着。
                    final boolean any = n.tpl == 2;
                    Vision.Hit bossPick = null;
                    Vision.Hit mobPick = null;
                    for (Vision.Hit cand : hits) {
                        if (cand.size <= 0 || cand.score < -1f) {
                            continue;
                        }
                        top = Math.max(top, cand.score);
                        int typ = Vision.classify(fr, cand);
                        lastCand = Math.round(cand.score * 100) + "% " + cand.colorText();
                        if (any) {
                            if (typ == 1) {
                                bossPick = cand;
                                break;
                            }
                            if (typ == 0) {
                                if (mobPick == null) {
                                    mobPick = cand;
                                }
                            } else if (cand.kind == 1) {
                                // 颜色判不出但模板是首领那张：也算首领
                                if (bossPick == null) {
                                    bossPick = cand;
                                }
                            } else if (unknown == null) {
                                unknown = cand;
                            }
                            continue;
                        }
                        if (typ == n.tpl) {
                            pick = cand;
                            break;
                        }
                        if (typ < 0) {
                            if (unknown == null) {
                                unknown = cand;
                            }
                        } else if (other == null) {
                            other = cand;
                            otherTyp = typ;
                        }
                    }
                    if (any) {
                        pick = bossPick != null ? bossPick : (mobPick != null ? mobPick : unknown);
                        if (pick != null) {
                            int t = Vision.classify(fr, pick);
                            if (t < 0) {
                                t = pick.kind;
                            }
                            sink.log("  选中" + (t == 1 ? "首领" : "小怪") + " " 
                                    + Math.round(pick.score * 100) + "%（" + pick.colorText() + "）");
                        }
                    }
                    if (pick == null && unknown != null) {
                        // 颜色判不出但轮廓像：直接用，这是「看到却不打」的主因
                        pick = unknown;
                        sink.log("  颜色判不出（" + unknown.colorText() + "），按轮廓 "
                                + Math.round(unknown.score * 100) + "% 直接打");
                    }
                    if (pick == null && other != null) {
                        // 判成相反类型：模板本身分类型，分数明显高于阈值就信模板
                        if (other.score * 100f >= th + 6f) {
                            pick = other;
                            sink.log("  颜色像" + Vision.tplName(otherTyp) + "（" + other.colorText()
                                    + "），但轮廓 " + Math.round(other.score * 100) + "% 更像" + who + "，照样打");
                        } else {
                            sink.log("  该位置更像" + Vision.tplName(otherTyp) + "，跳过");
                        }
                    }
                    if (pick != null) {
                        // 点下去之前的最后一道保险：章节选择界面上的「妖怪发现」图标
                        // 也能让怪物模板得到 99%，但那里没有地图、没有怪，点了只会
                        // 打开面板。只要「探索」木牌有 0.60 的相似度（地图最高 0.419），
                        // 就说明人在章节页，这时该点探索而不是点那个图标。
                        Vision.Hit guard = findChapter(fr, 0.60f);
                        if (guard != null) {
                            tapHit(guard);
                            sink.log("  这里其实是章节选择界面（图标是假怪），点「探索」进图");
                            pauseLoop(2600);
                            scan.reset();
                            everMoved = false;
                            stuck = 0;
                            clearRounds = 0;
                            misses = 0;
                            top = -1f;
                            lastCand = null;
                            continue;
                        }
                        misses = 0;
                        chestSame = 0;
                        yardTries = 0;
                        // 打完一场视角会变，下一轮从屏幕中间重新开始扫
                        scan.reset();
                        everMoved = false;
                        int pickedType = Vision.classify(fr, pick);
                        if (pickedType < 0) {
                            pickedType = pick.kind;
                        }
                        final boolean bossHit = pickedType == 1;
                        int half = pick.size / 2;
                        int px = (pick.x + half) * Vision.SCALE;
                        int py = (pick.y + half) * Vision.SCALE;
                        AutoService.tap(px, py, 55 + rnd.nextInt(40));
                        taps++;
                        sink.log("✓ 找到" + who + " 在 (" + px + "," + py + ") "
                                + Math.round(pick.score * 100) + "% → 第 " + taps + "/" + maxTaps + " 场");
                        if (n.x > 0.01f && n.y > 0.01f) {
                            pauseLoop(1500);
                            // 【v1.21】点完怪就直接点「出战」。
                            // 上一版要求"画面里还认得出怪物标识"才点，可点开阵容面板后
                            // 镜头会拉近、图标重新排布，分数掉到 78% 以下就被跳过——
                            // 用户日志里「画面已切走，这次不点出战」就是这么来的，
                            // 这一场根本没打，后面全是空转。
                            // 只保留一条硬拦截：明显在章节页时别点（防止在庭院里误点）。
                            Vision.Frame chk = ScreenGrabber.grabGray(Vision.SCALE);
                            if (chk == null || findChapter(chk) == null) {
                                AutoService.tap(n.x * w, n.y * h, 45 + rnd.nextInt(30));
                                pauseLoop(2200);
                                sink.log("  → 已点出战按钮");
                            } else {
                                sink.log("  → 这会儿像在章节页，先不点出战");
                            }
                            // 一场打完的完整收尾，分两段：
                            //  ① 前 6 下点中下方 —— 战斗中点技能、结算界面点「点击屏幕继续」，
                            //     点的是同一块地方，两边都不会出错。
                            //  ② 后 6 下点画面中央一带 —— 战后地上会掉一个小宝箱，要手动点开
                            //     才能领奖回到探索地图。宝箱位置不固定（掉在怪死的地方），
                            //     所以按小十字覆盖中央区域，比死点一个点靠谱。
                            // 战斗中 + 结算页：点中下方（点技能 / 点「点击屏幕继续」）
                            for (int i = 0; i < 6 && !stopped; i++) {
                                pauseLoop(2200);
                                if (stopped) {
                                    break;
                                }
                                AutoService.tap(w * 0.5f + (rnd.nextInt(40) - 20),
                                        h * 0.68f + (rnd.nextInt(30) - 15), 40 + rnd.nextInt(25));
                            }
                            // 收尾：真正确认回到探索地图。宝箱领完游戏会退回章节选择，
                            // 必须点「探索」才有怪——这里不再靠盲点猜。
                            ensureOnMap(sink, w, h);
                            // v1.15：打完首领不再收工。一局打完（点完宝箱）就回地图接着开下一局，
                            // 直到打满「最多 N 场」或超时——之前打一局就结束，用户看到的就是
                            // 「能打但不会重复打」。
                            sink.log(bossHit
                                    ? "  → 首领打完，重新进图继续下一局"
                                    : "  → 这一场收拾完，继续找下一只");
                        } else {
                            pauseLoop(700);
                        }
                    } else {
                        misses++;
                        if (tries % 3 == 1) {
                            // 三项一起报：怪 / 宝箱 / 章节。哪一项卡住，看日志就能分辨
                            // 是"没认出来"（分数差一点）还是"流程没走到"（分数根本没算）。
                            sink.log("找" + who + "中…怪 " + Math.round(Math.max(0, top) * 100)
                                    + "% / 宝箱 " + Math.round(chestTopScore * 100)
                                    + "% / 需要 " + th + "%" + (lastCand == null ? "" : "（" + lastCand + "）"));
                        }
                        // 刚没看到时，先点一下画面中下方脱困（结算页 / 剧情对话都在这一块）。
                        // 这个位置在地图上是空地，不像右下角，误触会开到喂食小纸人。
                        if (misses == 2) {
                            // 界面判定已经挪到每轮开头（章节/宝箱都先认过），
                            // 这里只负责在结算页 / 剧情对话上点一下脱困。
                            AutoService.tap(w * 0.5f + (rnd.nextInt(40) - 20),
                                    h * 0.68f + (rnd.nextInt(30) - 15), 40 + rnd.nextInt(25));
                        }
                        // 【v1.21】连 4 次什么都没认出来时的兜底：把"分数偏低"的宝箱也点一下。
                        // 实机里宝箱被人物挡住/刚掉落还在播动效时只有 0.68~0.79，
                        // 过不了 0.80 的线，旧版就一路 miss 到结束。宁可点一下（最多点 3 次），
                        // 也比干等着强——点错顶多是点了块空地。
                        if (misses == 3 && chest != null && chest.score >= 0.68f && chestSame < 3) {
                            chestSame++;
                            tapHit(chest);
                            sink.log("  → 宝箱分数偏低（" + Math.round(chest.score * 100)
                                    + "%），也点一下试试");
                            pauseLoop(1500);
                            scan.reset();
                            everMoved = false;
                            stuck = 0;
                            clearRounds = 0;
                            misses = 0;
                            top = -1f;
                            lastCand = null;
                            continue;
                        }
                        // 【v1.22】翻屏也找不到、又没有宝箱：可能压根不在探索地图上。
                        // 这里不依赖底部按钮的金色判定（悬浮窗挡住就失效），
                        // 无条件试一次「点开右侧章节」——点完必须看到「探索」木牌才算数，
                        // 点空了 enterFromYard 会自己换下一行，最多试 2 次。
                        if (misses >= 4 && yardTries < 2) {
                            yardTries++;
                            sink.log("  一直找不到怪，可能停在庭院/章节页，试试点开右侧章节");
                            if (enterFromYard(sink, w, h)) {
                                scan.reset();
                                everMoved = false;
                                stuck = 0;
                                clearRounds = 0;
                                misses = 0;
                                chestSame = 0;
                                top = -1f;
                                lastCand = null;
                                continue;
                            }
                        }
                        if (misses >= 3) {
                            int d = scan.next();
                            if (d == 0) {
                                if (clearRounds >= 2) {
                                    sink.log("左右共 " + (Scan.PER_SIDE * 2) + " 屏翻了两轮都没有" + who
                                            + "，这章确实清完了（换章或等刷新后重开脚本）");
                                    break;
                                }
                                clearRounds++;
                                sink.log("整张图翻遍了，先点一下画面收拾宝箱/结算，再从头扫一轮（"
                                        + clearRounds + "/2）");
                                AutoService.tap(w * 0.5f + (rnd.nextInt(30) - 15),
                                        h * 0.68f + (rnd.nextInt(20) - 10), 45 + rnd.nextInt(20));
                                pauseLoop(1500);
                                AutoService.tap(w * 0.5f + (rnd.nextInt(30) - 15),
                                        h * 0.50f + (rnd.nextInt(20) - 10), 45 + rnd.nextInt(20));
                                pauseLoop(1700);
                                scan.reset();
                                everMoved = false;
                                stuck = 0;
                                misses = 0;
                                top = -1f;
                                lastCand = null;
                                continue;
                            }
                            boolean moved = dragMap(d, w, h);
                            if (moved) {
                                sink.log("本屏没" + who + "，往" + (d > 0 ? "右" : "左")
                                        + "翻一屏 " + scan.text());
                            } else {
                                // 本侧拖到头了，换另一边再试一次
                                scan.atEdge();
                                int d2 = scan.next();
                                moved = d2 != 0 && dragMap(d2, w, h);
                                if (moved) {
                                    sink.log("往" + (d > 0 ? "右" : "左") + "拖到头了，改往"
                                            + (d2 > 0 ? "右" : "左") + "翻 " + scan.text());
                                }
                            }
                            if (moved) {
                                everMoved = true;
                                stuck = 0;
                                misses = 0;
                                // 换了画面，分数重新计，避免日志一直显示旧屏的最高分
                                top = -1f;
                                lastCand = null;
                                pauseLoop(400);
                            } else if (++stuck >= 2) {
                                // 连续两次换方向都拖不动。可能是地图就这么宽（真没有），
                                // 也可能还停在宝箱/结算页根本拖不动 —— 先收拾再重扫一轮。
                                if (clearRounds >= 2) {
                                    sink.log("画面怎么拖都不动了，左右都没有" + who + "，这章算清完了");
                                    break;
                                }
                                clearRounds++;
                                sink.log("画面拖不动，可能刚打完还没回地图，收拾一下重扫（"
                                        + clearRounds + "/2）");
                                AutoService.tap(w * 0.5f + (rnd.nextInt(30) - 15),
                                        h * 0.68f + (rnd.nextInt(20) - 10), 45 + rnd.nextInt(20));
                                pauseLoop(1500);
                                AutoService.tap(w * 0.5f + (rnd.nextInt(30) - 15),
                                        h * 0.50f + (rnd.nextInt(20) - 10), 45 + rnd.nextInt(20));
                                pauseLoop(1700);
                                scan.reset();
                                everMoved = false;
                                stuck = 0;
                                misses = 0;
                                top = -1f;
                                lastCand = null;
                            } else {
                                // 拖不动也可能根本没回到探索地图（还停在结算/阵容页）。
                                // 先按一下画面脱困再重新扫，别急着判清场——误判的代价是整轮空转。
                                AutoService.tap(w * 0.5f + (rnd.nextInt(40) - 20),
                                        h * 0.68f + (rnd.nextInt(30) - 15), 45 + rnd.nextInt(25));
                                pauseLoop(1800);
                                scan.reset();
                                sink.log("画面拖不动，按一下画面再试（" + stuck + "/2）");
                                misses = 1;
                            }
                        } else {
                            pauseLoop(900);
                        }
                    }
                }
                if (noGrab) {
                    sink.log("取不到画面（未授权屏幕录制），「找" + who + "」跳过");
                    sleep(2000);
                } else if (taps == 0) {
                    sink.log("✗ 没找到" + who + "（最高 " + Math.round(Math.max(0, top) * 100)
                            + "% / 阈值 " + th + "%）");
                } else {
                    sink.log("本轮找" + who + "结束：打了 " + taps + " 场");
                }
                break;
            }
            default:
                break;
        }
    }

    /**
     * 【v1.20】主循环心跳 + 看门狗。
     *
     * 用户这几次反馈的「卡着不动」，日志里都停在同一条上，之后几分钟一个字都没有。
     * 光看日志分不清是「识别没认出来」还是「线程真僵死了」——两者的处理完全不同。
     * 现在主循环每转一圈就打一次心跳，另开一条守护线程盯着：
     * 超过 90 秒没心跳就往日志里写一条，用户下次把日志发来就能直接定位。
     */
    private static volatile long lastBeat = 0L;
    private static volatile String beatNote = "";
    private static Thread watchdog;

    private static void beat(String note) {
        lastBeat = System.currentTimeMillis();
        beatNote = note;
    }

    private static void startWatchdog(final Sink sink) {
        if (watchdog != null && watchdog.isAlive()) {
            return;
        }
        watchdog = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean warned = false;
                while (true) {
                    try {
                        Thread.sleep(15000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!running) {
                        return;
                    }
                    if (paused) {
                        warned = false;
                        continue;
                    }
                    long idle = System.currentTimeMillis() - lastBeat;
                    if (idle > 90000L) {
                        if (!warned) {
                            warned = true;
                            sink.log("⚠ 已经 " + (idle / 1000) + " 秒没有进展（最后一步：" + beatNote
                                    + "）。若一直不动，点「停止」再点「开始」即可恢复");
                        }
                    } else if (idle < 25000L) {
                        warned = false;
                    }
                }
            }
        }, "yys-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (stopped) {
                return;
            }
            try {
                Thread.sleep(Math.min(120, end - System.currentTimeMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 面板上「试一下」：立刻点一次当前坐标。 */
    public static void tapOnce(Context c, float nx, float ny) {
        int[] sc = screen(c);
        AutoService.tap(nx * sc[0], ny * sc[1], 60);
    }
}
