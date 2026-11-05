package com.gesture.helper;

import android.graphics.Color;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 脚本数据模型。所有坐标均为归一化值 (0~1)，运行时按当前屏幕实际宽高还原，
 * 这样在 2408x1080 / 2340x1080 等不同分辨率的真机上都能复用同一份脚本。
 */
public final class Model {

    public static final int TAP = 0;
    public static final int SLEEP = 1;
    public static final int SWIPE = 2;
    public static final int BACK = 3;
    public static final int WAIT_COLOR = 4;
    public static final int WAIT_NOT = 5;
    /** 连点：在同一个位置点 times 次，每次间隔 ms。战斗中持续普攻用这个。 */
    public static final int TAP_BURST = 6;
    /** 等某个图标出现在画面上（全屏模板匹配，与位置无关）。tpl: 0=小怪 1=首领。 */
    public static final int WAIT_MATCH = 7;
    /**
     * 自己找怪并点它：全屏搜索图标，找到就点命中位置的中心。
     * tpl: 0=小怪 1=首领；pct: 相似度阈值；times: 最多点几次；ms: 最长搜索时间。
     * 位置无关，怪走到哪点到哪 —— 这是「自己寻找怪打」的核心。
     */
    public static final int FIND_TAP = 8;

    public static final int[] SLEEP_PRESETS = {
            300, 500, 800, 1000, 1500, 2000, 3000, 5000, 8000, 15000, 30000, 45000, 60000
    };

    public static final class Node {
        public int type = TAP;
        public float x = 0.5f;
        public float y = 0.5f;
        public float x2 = 0.5f;
        public float y2 = 0.5f;
        public int ms = 1000;
        public int color = 0xFFFFFFFF;
        public int tol = 28;
        /** 判定半径（像素）。0 表示只看单点；大于 0 是在这个半径内统计相似像素占比。 */
        public int rad = 24;
        /** 占比阈值（百分比）：区域内相似像素达到这个比例才算命中。 */
        public int pct = 18;
        /** 连点次数。 */
        public int times = 20;
        /** 模板编号：0=小怪标识，1=首领标识。 */
        public int tpl = 1;
        /** 人话说明，方便校准时知道这一步点的是哪个按钮。 */
        public String note = "";

        public Node() {
        }

        public Node(int type) {
            this.type = type;
        }

        public static Node tap(float x, float y) {
            Node n = new Node(TAP);
            n.x = x;
            n.y = y;
            return n;
        }

        public static Node sleep(int ms) {
            Node n = new Node(SLEEP);
            n.ms = ms;
            return n;
        }

        public static Node swipe(float x, float y, float x2, float y2, int ms) {
            Node n = new Node(SWIPE);
            n.x = x;
            n.y = y;
            n.x2 = x2;
            n.y2 = y2;
            n.ms = ms;
            return n;
        }

        public static Node back() {
            return new Node(BACK);
        }

        public String label() {
            String what;
            switch (type) {
                case TAP:
                    what = "点击 (" + fmt(x) + ", " + fmt(y) + ")";
                    break;
                case SLEEP:
                    what = "等待 " + (ms >= 1000 ? (ms / 1000f) + "s" : ms + "ms");
                    break;
                case SWIPE:
                    what = "滑动 (" + fmt(x) + "," + fmt(y) + ")→(" + fmt(x2) + "," + fmt(y2) + ")";
                    break;
                case BACK:
                    what = "返回键";
                    break;
                case WAIT_COLOR:
                    what = "等到出现 #" + Integer.toHexString(color & 0xFFFFFF).toUpperCase()
                            + " @" + fmt(x) + "," + fmt(y);
                    break;
                case WAIT_NOT:
                    what = "等到消失 #" + Integer.toHexString(color & 0xFFFFFF).toUpperCase()
                            + " @" + fmt(x) + "," + fmt(y);
                    break;
                case TAP_BURST:
                    what = "连点 " + times + " 次 / 间隔 " + ms + "ms";
                    break;
                case WAIT_MATCH:
                    what = "等到" + (tpl == 1 ? "首领" : "小怪") + "标识出现（阈值 " + pct + "%）";
                    break;
                case FIND_TAP:
                    what = "找" + (tpl == 1 ? "首领" : "小怪") + "并点它 ×" + times
                            + "（阈值 " + pct + "%）";
                    break;
                default:
                    what = "未知";
                    break;
            }
            if (note == null || note.isEmpty()) {
                return what;
            }
            return note + "  " + what;
        }

        private static String fmt(float v) {
            return String.format(java.util.Locale.US, "%.3f", v);
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("t", type);
                o.put("x", (double) x);
                o.put("y", (double) y);
                o.put("x2", (double) x2);
                o.put("y2", (double) y2);
                o.put("ms", ms);
                o.put("c", color);
                o.put("tol", tol);
                o.put("rad", rad);
                o.put("pct", pct);
                o.put("times", times);
                o.put("tpl", tpl);
                o.put("n", note == null ? "" : note);
            } catch (JSONException ignored) {
            }
            return o;
        }

        public static Node fromJson(JSONObject o) {
            Node n = new Node();
            n.type = o.optInt("t", TAP);
            n.x = (float) o.optDouble("x", 0.5);
            n.y = (float) o.optDouble("y", 0.5);
            n.x2 = (float) o.optDouble("x2", 0.5);
            n.y2 = (float) o.optDouble("y2", 0.5);
            n.ms = o.optInt("ms", 1000);
            n.color = o.optInt("c", 0xFFFFFFFF);
            n.tol = o.optInt("tol", 28);
            n.rad = o.optInt("rad", 24);
            n.pct = o.optInt("pct", 18);
            n.times = o.optInt("times", 20);
            n.tpl = o.optInt("tpl", 1);
            n.note = o.optString("n", "");
            return n;
        }

        public int rgb() {
            return color & 0xFFFFFF;
        }
    }

    /** 两个颜色是否在容差内近似。 */
    public static boolean near(int a, int b, int tol) {
        int dr = Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF));
        int dg = Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF));
        int db = Math.abs((a & 0xFF) - (b & 0xFF));
        return dr <= tol && dg <= tol && db <= tol;
    }

    public static String hex(int c) {
        return "#" + Integer.toHexString(c & 0xFFFFFF).toUpperCase();
    }

    public static int parseColor(String s) {
        try {
            return Color.parseColor(s);
        } catch (Exception e) {
            return 0xFFFFFF;
        }
    }
}
