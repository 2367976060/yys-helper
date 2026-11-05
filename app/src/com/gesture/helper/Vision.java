package com.gesture.helper;

/**
 * 灰度模板匹配（归一化互相关 NCC）+ 圆内掩模 + 颜色分类。
 *
 * 为什么不用固定坐标取色：怪物会动、镜头会晃、换个主线界面位置就变。
 * 模板匹配是**全屏搜索**，只要图标出现在画面任何位置都能命中，并把坐标返回。
 *
 * 【v1.11 关键修复：光晕把分数压到 45%】
 * 实测发现同一个图标在"庭院/阵容预览"里外围有一圈很亮的金色光晕，而章节选择图里没有。
 * 带光晕的画面用整块模板去比，分数只有 0.45~0.52（阈值 80% 判不过），
 * 表现就是"视野里明明有怪，脚本不打"。
 * 解法：只比对图标**圆内**（半径 42%），把外围光晕和背景全部排除。
 * 改完之后同一批截图：小怪 0.97~0.99，跨场景全过。
 *
 * 【v1.11 新增：颜色分类】
 * 掩模后小怪/首领的灰度轮廓几乎一样（都是刀形），灰度区分度掉到 0.98 vs 0.92，
 * 不足以免误判。但两者颜色差异极大，实测：
 *   小怪 = 蓝刀鞘 → 蓝占比 0.25~0.27，红占比 0.12~0.14
 *   首领 = 红刀柄 → 蓝占比 0.08~0.09，红占比 0.23~0.24
 * 所以改成「灰度定位 + 颜色判类型」：先用灰度找到图标在哪，再看中心是蓝还是红。
 */
public final class Vision {

    /** 降采样倍数。4 表示 2408x1080 → 602x270。 */
    public static final int SCALE = 4;

    /** 只比对圆心半径的这个比例，排除外围光晕。0.42 实测最稳。 */
    private static final float MASK_R = 0.42f;

    private Vision() {
    }

    /** 一帧画面：灰度 + 可选彩色。 */
    public static final class Frame {
        public final int w;
        public final int h;
        public final int[] gray;
        /** 0xRRGGBB，可能为 null（只做了灰度时）。 */
        public final int[] rgb;

        public Frame(int w, int h, int[] gray) {
            this(w, h, gray, null);
        }

        public Frame(int w, int h, int[] gray, int[] rgb) {
            this.w = w;
            this.h = h;
            this.gray = gray;
            this.rgb = rgb;
        }
    }

    /** 命中结果：分数 + 帧坐标。 */
    public static final class Hit {
        public float score = -2f;
        public int x;
        public int y;
        public int size;
        /** 命中来自哪一类模板：0=小怪 1=首领 -1=未知 */
        public int kind = -1;
        /** classify() 顺手记下的颜色占比，日志诊断用。 */
        public float blue = -1f;
        public float red = -1f;

        public String colorText() {
            if (blue < 0 || red < 0) {
                return "未取色";
            }
            return String.format(java.util.Locale.US, "红%.2f/蓝%.2f", red, blue);
        }
    }

    /** 找一张模板，返回最高相似度（0~1）。loc 传入长度≥2 的数组可回写命中位置。 */
    public static float match(Frame f, int[] tpl, int[] loc) {
        Hit h = search(f, new int[][]{tpl});
        if (loc != null && loc.length >= 2) {
            loc[0] = h.x;
            loc[1] = h.y;
        }
        return h.score;
    }

    /** 多模板取最高分。loc 回写最高分那张模板的命中位置。 */
    public static float match(Frame f, int[][] tpls, int[] loc) {
        Hit h = search(f, tpls);
        if (loc != null && loc.length >= 2) {
            loc[0] = h.x;
            loc[1] = h.y;
        }
        return h.score;
    }

    /** 带完整信息的搜索，供「找到就点」用。 */
    public static Hit search(Frame f, int[][] tpls) {
        return searchMulti(f, tpls, 1, -2f)[0];
    }

    /**
     * 一次找出前 topN 个不同位置的命中（非极大值抑制）。
     * 同屏可能既有小怪又有首领，需要挨个判类型再决定点哪个。
     */
    /**
     * 只在帧内一块矩形（比例坐标 0~1）里搜索，返回最像的一个。
     * 给 UI 按钮用：「探索」木牌永远在右下角，限定区域后地图上的庭院 UI
     * 就不会误命中——误点的代价是打开右下角那个「喂食小纸人」。
     */
    public static Hit searchInRect(Frame f, int[][] tpls, float fx0, float fy0,
                                   float fx1, float fy1, float minScore, boolean full) {
        if (f == null || tpls == null || tpls.length == 0) {
            return null;
        }
        int x0 = Math.max(0, Math.min(f.w - 1, Math.round(fx0 * f.w)));
        int y0 = Math.max(0, Math.min(f.h - 1, Math.round(fy0 * f.h)));
        int x1 = Math.max(x0 + 1, Math.min(f.w, Math.round(fx1 * f.w)));
        int y1 = Math.max(y0 + 1, Math.min(f.h, Math.round(fy1 * f.h)));
        int w = x1 - x0;
        int h = y1 - y0;
        int[] g = new int[w * h];
        for (int y = 0; y < h; y++) {
            System.arraycopy(f.gray, (y0 + y) * f.w + x0, g, y * w, w);
        }
        Hit[] hits = searchMulti(new Frame(w, h, g), tpls, 1, minScore, full);
        if (hits.length == 0 || hits[0].size <= 0) {
            return null;
        }
        Hit hit = hits[0];
        hit.x += x0;
        hit.y += y0;
        return hit;
    }

    public static Hit[] searchMulti(Frame f, int[][] tpls, int topN, float minScore) {
        return searchMulti(f, tpls, topN, minScore, false);
    }

    /**
     * @param full true 时用整块方形模板比对（UI 图标用），false 时只比圆内 42%
     *             （怪物图标用，避开外围金色光晕）。
     */
    public static Hit[] searchMulti(Frame f, int[][] tpls, int topN, float minScore, boolean full) {
        Hit[] out = new Hit[Math.max(1, topN)];
        for (int i = 0; i < out.length; i++) {
            out[i] = new Hit();
        }
        if (f == null || tpls == null || tpls.length == 0 || topN <= 0) {
            return out;
        }
        // 在副本上做抑制，避免改动原帧
        int[] work = f.gray.clone();
        Frame probe = new Frame(f.w, f.h, work);
        int done = 0;
        for (int round = 0; round < topN; round++) {
            Hit best = new Hit();
            best.score = -2f;
            for (int t = 0; t < tpls.length; t++) {
                int[] tpl = tpls[t];
                if (tpl == null || tpl.length == 0) {
                    continue;
                }
                Hit h = searchOne(probe, tpl, full);
                if (h.score > best.score) {
                    best = h;
                    best.kind = Templates.kindOf(tpl);
                }
            }
            if (best.score < minScore || best.size <= 0) {
                break;
            }
            out[done++] = best;
            // 抑制：把这个位置周围抹平，下一轮才能找到别处的目标
            int n = best.size;
            int cx = best.x + n / 2;
            int cy = best.y + n / 2;
            // 多尺度下同一个图标可能被不同边长各命中一次，抑制半径要给足
            int r = n / 2 + 6;
            for (int y = Math.max(0, cy - r); y < Math.min(f.h, cy + r); y++) {
                int base = y * f.w;
                for (int x = Math.max(0, cx - r); x < Math.min(f.w, cx + r); x++) {
                    work[base + x] = 0;
                }
            }
            if (done >= topN) {
                break;
            }
        }
        if (done == 0) {
            out[0].score = -2f;
        }
        return out;
    }

    private static Hit searchOne(Frame f, int[] tpl, boolean full) {
        Hit out = new Hit();
        int n = (int) Math.round(Math.sqrt(tpl.length));
        if (n <= 0 || f.w < n || f.h < n) {
            return out;
        }
        out.size = n;
        int[] idx = full ? allIndex(n) : maskIndex(n);
        int cnt = idx.length;

        // 模板只在掩模区（圆内）统计，光晕/背景不参与
        double tm = 0;
        for (int k : idx) {
            tm += tpl[k];
        }
        tm /= cnt;
        double tv = 0;
        for (int k : idx) {
            double d = tpl[k] - tm;
            tv += d * d;
        }
        if (tv <= 1e-6) {
            return out;
        }

        // 第一级：步长 2 粗搜。步长 4 会整格跳过峰值，别改。
        float best = -2f;
        int bx = 0;
        int by = 0;
        int step = 2;
        for (int y = 0; y + n <= f.h; y += step) {
            for (int x = 0; x + n <= f.w; x += step) {
                float s = score(f, tpl, idx, x, y, n, tm, tv);
                if (s > best) {
                    best = s;
                    bx = x;
                    by = y;
                }
            }
        }
        // 第二级：粗搜结果附近逐像素精修
        int pad = 4;
        for (int y = Math.max(0, by - pad); y <= Math.min(f.h - n, by + pad); y++) {
            for (int x = Math.max(0, bx - pad); x <= Math.min(f.w - n, bx + pad); x++) {
                float s = score(f, tpl, idx, x, y, n, tm, tv);
                if (s > best) {
                    best = s;
                    bx = x;
                    by = y;
                }
            }
        }
        out.score = best;
        out.x = bx;
        out.y = by;
        return out;
    }

    private static float score(Frame f, int[] tpl, int[] idx, int x, int y,
                               int n, double tm, double tv) {
        int base = y * f.w + x;
        double sum = 0;
        double sumSq = 0;
        double cross = 0;
        for (int k : idx) {
            int v = f.gray[base + (k / n) * f.w + (k % n)];
            sum += v;
            sumSq += (double) v * v;
            cross += (double) v * tpl[k];
        }
        int cnt = idx.length;
        double mean = sum / cnt;
        double var = sumSq - sum * mean;
        if (var <= 1e-6) {
            return -2f;
        }
        double num = cross - cnt * mean * tm;
        return (float) (num / Math.sqrt(var * tv));
    }

    /** 圆内像素下标表（相对模板左上角），按边长缓存。多尺度下会有 3 种边长。 */
    private static final java.util.HashMap<Integer, int[]> maskCache = new java.util.HashMap<>();

    /** 整块方形的下标表，给 UI 图标模板用（宝箱这种没有光晕干扰的）。 */
    private static int[] allCache;
    private static int allCacheN;

    private static synchronized int[] allIndex(int n) {
        if (allCache != null && allCacheN == n) {
            return allCache;
        }
        int[] a = new int[n * n];
        for (int i = 0; i < a.length; i++) {
            a[i] = i;
        }
        allCache = a;
        allCacheN = n;
        return a;
    }

    private static synchronized int[] maskIndex(int n) {
        int[] hit = maskCache.get(n);
        if (hit != null) {
            return hit;
        }
        double c = (n - 1) / 2.0;
        // 注意：MASK_R 是「相对半径」的比例，半径 = 边长的一半 * MASK_R
        double r = MASK_R * n / 2.0;
        double rr = r * r;
        int[] tmp = new int[n * n];
        int k = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double dx = x - c;
                double dy = y - c;
                if (dx * dx + dy * dy <= rr) {
                    tmp[k++] = y * n + x;
                }
            }
        }
        int[] out = new int[k];
        System.arraycopy(tmp, 0, out, 0, k);
        maskCache.put(n, out);
        return out;
    }

    /**
     * 颜色判类型：在 (x,y) 这个命中框里看中心是蓝还是红。
     * 返回 0=小怪（蓝） 1=首领（红） -1=判不出来。
     */
    public static int classify(Frame f, Hit h) {
        if (f == null || f.rgb == null || h == null || h.size <= 0) {
            return -1;
        }
        int n = h.size;
        double c = (n - 1) / 2.0;
        double r = 0.32 * n;
        double rr = r * r;
        int blue = 0;
        int red = 0;
        int cnt = 0;
        for (int y = 0; y < n; y++) {
            int fy = h.y + y;
            if (fy < 0 || fy >= f.h) {
                continue;
            }
            for (int x = 0; x < n; x++) {
                double dx = x - c;
                double dy = y - c;
                if (dx * dx + dy * dy > rr) {
                    continue;
                }
                int fx = h.x + x;
                if (fx < 0 || fx >= f.w) {
                    continue;
                }
                int p = f.rgb[fy * f.w + fx];
                int R = (p >> 16) & 0xFF;
                int G = (p >> 8) & 0xFF;
                int B = p & 0xFF;
                cnt++;
                if (B > R + 25 && B > 90) {
                    blue++;
                } else if (R > B + 55 && R > 130) {
                    red++;
                }
            }
        }
        if (cnt == 0) {
            return -1;
        }
        double b = blue / (double) cnt;
        double rd = red / (double) cnt;
        h.blue = (float) b;
        h.red = (float) rd;
        // 实测小怪 蓝-红 ≈ +0.13，首领 红-蓝 ≈ +0.15，留足余量
        if (b - rd > 0.04) {
            return 0;
        }
        if (rd - b > 0.04) {
            return 1;
        }
        return -1;
    }

    /** which: 0=小怪 1=首领 */
    public static int[][] tpl(int which) {
        return Templates.get(which);
    }

    public static String tplName(int which) {
        if (which == 2) {
            return "怪物标识";
        }
        return which == 1 ? "首领标识" : "小怪标识";
    }
}
