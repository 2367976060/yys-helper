package com.gesture.helper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.InputStream;

/**
 * 识别模板：直接从用户游戏截图上裁下来的真实图标，打包在 assets 里。
 *
 * 为什么不再用写死的数组：之前那版是按「想象中的图标」画出来的假数据，
 * 跟真实画面根本对不上，所以永远识别不到。
 *
 * 尺度对齐（关键）：截图是 1920 宽，手机实际是 2408 宽。运行时先把模板
 * 按 screenW/1920 放大，再跟抓屏一样「每 4 像素取 1 点」降采样，这样模板
 * 和画面在同一套采样网格上，NCC 才不会因为尺度差而掉分。
 *
 * 每类放两个模板（不同光照下裁的），匹配时取最高分：
 *   小怪：ref3(林地) + ref5(雪地)
 *   首领：ref4(金光) + ref5(雪地)
 * 实测（2408 手机尺度仿真，NCC）：
 *   小怪模板 → 小怪画面 0.930 / 0.961 / 0.955
 *   首领模板 → 首领画面 0.912 / 0.944，小怪画面仅 0.526
 */
public final class Templates {

    /** 截图基准宽度，所有裁剪坐标都按这个宽度算。 */
    public static final int REF_W = 1920;

    /** 截图上裁剪的边长（正方形）。 */
    public static final int CROP = 104;

    /**
     * 【v1.17】「探索」按钮模板的裁剪边长。章节选择界面右下角那块木牌比怪物图标大一圈
     * （1920 基准下直径约 125px），必须按它自己的尺寸裁、按自己的尺寸加载，
     * 否则缩放对不上，NCC 直接掉到 0.4。
     */
    public static final int CROP_BIG = 130;

    /**
     * 【v1.12：多尺度】
     * 怪物离镜头远近不同，同一个图标在画面上的直径会差 ±15%（实测首领图标
     * 在一张图里 100px、换张图就 115px / 1920 基准）。单一尺度下，尺寸对不上的
     * 那张分数会从 95% 掉到 61%，于是"画面上明明有首领，脚本就是不认"。
     * 现在每张模板生成 3 个尺度，匹配取最高分，代价是识别耗时 ×3（约 200ms，可接受）。
     */
    public static final float[] SCALES = {0.88f, 1.00f, 1.14f};

    private static int size = 26;
    private static int[][] mobs = new int[0][];
    private static int[][] bosses = new int[0][];
    /** v1.16：战后地上的小宝箱（圆形深底 + 橙金色箱体），打完要手动点开才回地图。 */
    private static int[][] chests = new int[0][];
    /** v1.17：章节选择界面右下角的「探索」木牌，打完首领游戏会自己退回这里。 */
    private static int[][] chapters = new int[0][];
    private static float loadedK = -1f;

    private Templates() {
    }

    /** 当前模板在降采样帧里的边长。 */
    public static int size() {
        return size;
    }

    private static boolean ready() {
        return mobs.length > 0 && mobs[0] != null;
    }

    /** 保证已按当前屏幕宽度加载。重复调用无副作用。 */
    public static synchronized void ensure(Context c) {
        if (c == null) {
            return;
        }
        int[] sc = Engine.screen(c);
        // 长边才是横屏画面的宽度，竖屏下 sc[0] 会是 1080，模板会缩错尺度
        load(c, Math.max(sc[0], sc[1]));
    }

    public static synchronized void load(Context c, int screenW) {
        if (c == null || screenW <= 0) {
            return;
        }
        float k = screenW / (float) REF_W;
        if (ready() && Math.abs(k - loadedK) < 0.01f) {
            return;
        }
        int base = Math.max(40, Math.round(CROP * k));
        int baseBig = Math.max(40, Math.round(CROP_BIG * k));

        String[] mobNames = {"tpl_mob_a.png", "tpl_mob_b.png", "tpl_mob_c.png", "tpl_mob_d.png"};
        String[] bossNames = {"tpl_boss_a.png", "tpl_boss_b.png"};
        String[] chestNames = {"tpl_chest_a.png"};

        java.util.List<int[]> mm = new java.util.ArrayList<>();
        java.util.List<int[]> bm = new java.util.ArrayList<>();
        java.util.List<int[]> cm = new java.util.ArrayList<>();
        java.util.List<int[]> chm = new java.util.ArrayList<>();
        int midN = (base + Vision.SCALE - 1) / Vision.SCALE;
        for (float s : SCALES) {
            int target = Math.max(40, Math.round(base * s));
            int n = (target + Vision.SCALE - 1) / Vision.SCALE;
            if (Math.abs(s - 1f) < 0.001f) {
                midN = n;
            }
            for (String name : mobNames) {
                int[] g = read(c, name, target, n);
                if (g != null) {
                    mm.add(g);
                }
            }
            for (String name : bossNames) {
                int[] g = read(c, name, target, n);
                if (g != null) {
                    bm.add(g);
                }
            }
            for (String name : chestNames) {
                int[] g = read(c, name, target, n);
                if (g != null) {
                    cm.add(g);
                }
            }
            // 「探索」按钮尺寸不同，单独算 target/n
            int tBig = Math.max(40, Math.round(baseBig * s));
            int nBig = (tBig + Vision.SCALE - 1) / Vision.SCALE;
            int[] gc = read(c, "tpl_chapter_a.png", tBig, nBig);
            if (gc != null) {
                chm.add(gc);
            }
        }

        // 读失败就保留旧模板，绝不让 size 变成 0 导致除零
        if (!mm.isEmpty()) {
            mobs = mm.toArray(new int[0][]);
        }
        if (!bm.isEmpty()) {
            bosses = bm.toArray(new int[0][]);
        }
        if (!cm.isEmpty()) {
            chests = cm.toArray(new int[0][]);
        }
        if (!chm.isEmpty()) {
            chapters = chm.toArray(new int[0][]);
        }
        if (ready()) {
            size = midN;
            loadedK = k;
        }
    }

    /**
     * 读一张模板图 → 灰度 → 按 Vision.SCALE 采样成 n×n。
     * 灰度公式必须和 ScreenGrabber.grabGray 完全一致。
     */
    private static int[] read(Context c, String name, int target, int n) {
        InputStream in = null;
        try {
            in = c.getAssets().open(name);
            Bitmap src = BitmapFactory.decodeStream(in);
            if (src == null) {
                return null;
            }
            Bitmap sc = Bitmap.createScaledBitmap(src, target, target, true);
            int[] px = new int[target * target];
            sc.getPixels(px, 0, target, 0, 0, target, target);
            if (sc != src) {
                sc.recycle();
            }
            src.recycle();

            int[] g = new int[n * n];
            for (int j = 0; j < n; j++) {
                int sy = Math.min(target - 1, j * Vision.SCALE);
                for (int i = 0; i < n; i++) {
                    int sx = Math.min(target - 1, i * Vision.SCALE);
                    int p = px[sy * target + sx];
                    int r = (p >> 16) & 0xFF;
                    int gg = (p >> 8) & 0xFF;
                    int b = p & 0xFF;
                    g[j * n + i] = (r * 77 + gg * 151 + b * 28) >> 8;
                }
            }
            return g;
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** which: 0=小怪 1=首领 2=两类合并（随便哪种怪都打） 3=战后宝箱 4=章节「探索」按钮 */
    public static int[][] get(int which) {
        if (which == 4) {
            return chapters;
        }
        if (which == 3) {
            return chests;
        }
        if (which == 2) {
            int[][] all = new int[mobs.length + bosses.length][];
            System.arraycopy(mobs, 0, all, 0, mobs.length);
            System.arraycopy(bosses, 0, all, mobs.length, bosses.length);
            return all;
        }
        return which == 1 ? bosses : mobs;
    }

    /** 反查某张模板属于哪一类：0=小怪 1=首领 -1=未知。 */
    public static int kindOf(int[] tpl) {
        if (tpl == null) {
            return -1;
        }
        for (int[] a : mobs) {
            if (a == tpl) {
                return 0;
            }
        }
        for (int[] a : bosses) {
            if (a == tpl) {
                return 1;
            }
        }
        return -1;
    }

    private static int[][] compact(int[]... arr) {
        int cnt = 0;
        for (int[] a : arr) {
            if (a != null) {
                cnt++;
            }
        }
        int[][] out = new int[cnt][];
        int k = 0;
        for (int[] a : arr) {
            if (a != null) {
                out[k++] = a;
            }
        }
        return out;
    }
}
