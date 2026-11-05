package com.gesture.helper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置方案库 + 本地存储。
 * 坐标默认按 2408x1080 横屏推算，用户在手机上用「定位」改过之后会覆盖保存，
 * 下次打开直接用改好的版本。
 */
public final class Scripts {

    public static final String[] NAMES = {
            "御魂循环", "觉醒材料", "探索 · 个人", "结界突破",
            "百鬼夜行", "妖气封印", "单点连击", "自定义序列"
    };

    private static final String PREF = "yys";
    private static final String KEY_PREFIX = "script_";

    /**
     * 探索/准备界面右下角的「出战（挑战）」圆钮 —— 晴明头像那个。
     * 三张实机截图（林地 / 雪地 / 金光）位置完全一致：(1670, 798) / 1920x861。
     * 只点怪不点它，怪只会被选中，战斗永远不开始。
     */
    public static final float GO_X = 0.8698f;
    public static final float GO_Y = 0.9268f;

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static Model.Node tap(String note, float x, float y) {
        Model.Node n = Model.Node.tap(x, y);
        n.note = note;
        return n;
    }

    private static Model.Node wait(String note, int ms) {
        Model.Node n = Model.Node.sleep(ms);
        n.note = note;
        return n;
    }

    /** 等某个图标出现在画面上（全屏模板匹配，与位置无关）。 */
    private static Model.Node match(String note, int tpl, int pct, int ms) {
        Model.Node n = new Model.Node(Model.WAIT_MATCH);
        n.tpl = tpl;
        n.pct = pct;
        n.ms = ms;
        n.note = note;
        return n;
    }

    /** 自己找怪并点它：全屏搜图标，找到就点命中位置。位置无关。 */
    private static Model.Node find(String note, int tpl, int pct, int times, int ms) {
        return find(note, tpl, pct, times, ms, 0f, 0f);
    }

    /**
     * 找怪并开打：点完怪再点 (goX, goY) 的出战按钮，战斗才会真的开始。
     * 只点怪不点出战，怪只会被选中，永远打不起来 —— 这是「找不到怪 / 打不动」的另一半原因。
     */
    private static Model.Node find(String note, int tpl, int pct, int times, int ms,
                                   float goX, float goY) {
        Model.Node n = new Model.Node(Model.FIND_TAP);
        n.tpl = tpl;
        n.pct = pct;
        n.times = times;
        n.ms = ms;
        n.x = goX;
        n.y = goY;
        n.note = note;
        return n;
    }

    /** 连点：战斗中由脚本自己按技能/普攻，不依赖游戏内「自动战斗」。 */
    private static Model.Node burst(String note, float x, float y, int times, int ms) {
        Model.Node n = new Model.Node(Model.TAP_BURST);
        n.x = x;
        n.y = y;
        n.times = times;
        n.ms = ms;
        n.note = note;
        return n;
    }

    public static List<Model.Node> preset(int index) {
        List<Model.Node> l = new ArrayList<>();
        switch (index) {
            case 0: // 御魂循环
                l.add(tap("庭院 · 探索", 0.250f, 0.385f));
                l.add(wait("等界面切换", 1500));
                l.add(tap("探索地图 · 御魂", 0.102f, 0.946f));
                l.add(wait("等界面切换", 1800));
                l.add(tap("副本内 · 挑战", 0.741f, 0.868f));
                l.add(wait("等队伍界面", 2500));
                l.add(burst("战斗 · 连点技能（不用游戏自动）", 0.735f, 0.862f, 26, 1100));
                l.add(wait("战斗收尾", 1800));
                l.add(tap("胜利 · 点击继续", 0.508f, 0.840f));
                l.add(wait("等结算动画", 3500));
                l.add(tap("再来一次", 0.741f, 0.868f));
                l.add(wait("回到挑战界面", 2500));
                break;
            case 1: // 觉醒材料
                l.add(tap("庭院 · 探索", 0.250f, 0.385f));
                l.add(wait("等界面切换", 1500));
                l.add(tap("探索地图 · 觉醒材料", 0.206f, 0.946f));
                l.add(wait("等界面切换", 1800));
                l.add(tap("副本内 · 挑战", 0.741f, 0.868f));
                l.add(wait("等队伍界面", 2500));
                l.add(burst("战斗 · 连点技能（不用游戏自动）", 0.735f, 0.862f, 26, 1100));
                l.add(wait("战斗收尾", 1800));
                l.add(tap("胜利 · 点击继续", 0.508f, 0.840f));
                l.add(wait("等结算动画", 3500));
                l.add(tap("再来一次", 0.741f, 0.868f));
                l.add(wait("回到挑战界面", 2500));
                break;
            case 2: // 探索 · 个人
                l.add(tap("庭院 · 探索", 0.250f, 0.385f));
                l.add(wait("等界面切换", 1500));
                l.add(tap("选择章节", 0.500f, 0.450f));
                l.add(wait("等界面切换", 2000));
                l.add(tap("进入章节 / 挑战", 0.800f, 0.880f));
                l.add(wait("等进入战斗", 2500));
                // 【v1.15】小怪和首领都能打，看到哪个打哪个，首领优先——不用先清完小怪。
                // 一个步骤循环打到底：
                //   找到怪 → 点它 → 点出战 → 打完 → 点开战后掉的小宝箱领奖
                //   → 回到探索地图 → 接着找下一只（首领打完也继续，不中途收工）
                // 本屏没有就左右翻屏；整张图翻遍先收拾一下再重扫一轮，两轮都没有才算清完。
                // 上限 20 场 / 15 分钟，悬浮窗里可调。
                // v1.18：打完一场 → 点宝箱 → 回到章节界面点「探索」重进同一关 → 继续打。
                // 这一步会一直循环到打满场次或超时，不再打一场就停。
                l.add(find("① 循环刷本（自动进图·打怪·领宝箱）", 2, 90, 20, 900000, GO_X, GO_Y));
                l.add(wait("战斗收尾", 1800));
                l.add(tap("结算 · 点击继续", 0.500f, 0.680f));
                l.add(wait("等结算", 2500));
                l.add(tap("结算 · 再点一次继续", 0.500f, 0.680f));
                l.add(wait("回到章节", 2000));
                // v1.12：一轮打完人还留在副本里，直接进下一轮 = 在空场景里空转
                // （用户看到的就是「第二轮全是 0%」）。连按两次左上角返回：
                // 第一次退出副本回章节选择，第二次回庭院，下一轮的「庭院·探索」才点得准。
                // 左上角那个「←」不压任何功能按钮，多点一次最多是没反应。
                l.add(tap("收尾 · 左上返回（退出副本）", 0.025f, 0.072f));
                l.add(wait("等退出副本", 1600));
                l.add(tap("收尾 · 再返回一次（回庭院）", 0.025f, 0.072f));
                l.add(wait("等回庭院", 1800));
                break;
            case 3: // 结界突破
                l.add(tap("庭院 · 探索", 0.250f, 0.385f));
                l.add(wait("等界面切换", 1500));
                l.add(tap("探索地图 · 结界突破", 0.413f, 0.946f));
                l.add(wait("等界面切换", 1800));
                l.add(tap("第一个对手", 0.300f, 0.350f));
                l.add(wait("等弹窗", 1200));
                l.add(tap("挑战", 0.741f, 0.868f));
                l.add(wait("等队伍界面", 2500));
                l.add(burst("战斗 · 连点技能（不用游戏自动）", 0.735f, 0.862f, 32, 1100));
                l.add(wait("战斗收尾", 1800));
                l.add(tap("胜利 · 点击继续", 0.508f, 0.840f));
                l.add(wait("等结算", 3000));
                break;
            case 4: // 百鬼夜行
                l.add(tap("庭院 · 町中", 0.593f, 0.489f));
                l.add(wait("等界面切换", 1500));
                l.add(tap("百鬼夜行入口", 0.350f, 0.450f));
                l.add(wait("等加载", 2500));
                l.add(tap("撒豆", 0.500f, 0.850f));
                l.add(wait("冷却", 1200));
                l.add(tap("撒豆", 0.500f, 0.850f));
                l.add(wait("冷却", 1200));
                break;
            case 5: // 妖气封印
                l.add(tap("庭院 · 探索", 0.250f, 0.385f));
                l.add(wait("等界面切换", 1500));
                l.add(tap("探索地图 · 妖气探索", 0.309f, 0.946f));
                l.add(wait("等界面切换", 1800));
                l.add(tap("副本内 · 挑战", 0.741f, 0.868f));
                l.add(wait("等队伍界面", 2500));
                l.add(burst("战斗 · 连点技能（不用游戏自动）", 0.735f, 0.862f, 26, 1100));
                l.add(wait("战斗收尾", 1800));
                l.add(tap("胜利 · 点击继续", 0.508f, 0.840f));
                l.add(wait("等结算", 3000));
                break;
            case 6: // 单点连击
                l.add(tap("连击位置(改这里)", 0.500f, 0.500f));
                l.add(wait("连击间隔", 1000));
                break;
            default: // 自定义
                l.add(tap("自定义点 1", 0.500f, 0.500f));
                l.add(wait("等待", 1000));
                break;
        }
        return l;
    }

    public static List<Model.Node> load(Context c, int index) {
        String raw = prefs(c).getString(KEY_PREFIX + index, null);
        if (raw == null) {
            return preset(index);
        }
        try {
            JSONArray arr = new JSONArray(raw);
            List<Model.Node> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                list.add(Model.Node.fromJson(arr.getJSONObject(i)));
            }
            if (!list.isEmpty()) {
                return list;
            }
        } catch (Exception ignored) {
        }
        return preset(index);
    }

    public static void save(Context c, int index, List<Model.Node> list) {
        JSONArray arr = new JSONArray();
        for (Model.Node n : list) {
            arr.put(n.toJson());
        }
        prefs(c).edit().putString(KEY_PREFIX + index, arr.toString()).apply();
    }

    public static void reset(Context c, int index) {
        prefs(c).edit().remove(KEY_PREFIX + index).apply();
    }

    /**
     * 方案结构升级：老版本内置方案里有「自动挑战」（点游戏自带自动战斗），
     * v1.5 起改成脚本自己连点技能；v1.8 起识别改用真图标 + 「自己找怪点它」。
     * 老缓存不清掉的话，升级后还是旧流程。
     */
    public static void migrate(Context c) {
        int ver = prefs(c).getInt("scriptVer", 0);
        if (ver >= 14) {
            return;
        }
        SharedPreferences.Editor e = prefs(c).edit();
        for (int i = 0; i < NAMES.length; i++) {
            e.remove(KEY_PREFIX + i);
        }
        e.putInt("scriptVer", 14).apply();
    }

    // ---------- 全局参数 ----------

    public static int getJitter(Context c) {
        return prefs(c).getInt("jitter", 10);
    }

    public static void setJitter(Context c, int v) {
        prefs(c).edit().putInt("jitter", v).apply();
    }

    public static int getGap(Context c) {
        return prefs(c).getInt("gap", 400);
    }

    public static void setGap(Context c, int v) {
        prefs(c).edit().putInt("gap", v).apply();
    }

    public static int getLoops(Context c) {
        return prefs(c).getInt("loops", 0);
    }

    public static void setLoops(Context c, int v) {
        prefs(c).edit().putInt("loops", v).apply();
    }

    public static int getPlan(Context c) {
        return prefs(c).getInt("plan", 0);
    }

    public static void setPlan(Context c, int v) {
        prefs(c).edit().putInt("plan", v).apply();
    }

    /**
     * 用户点「开始」那一刻记录下来的游戏包名。渠道服包名五花八门，
     * 靠它做自适应匹配，比硬编码 com.netease.onmyoji 靠谱。
     */
    public static String getGamePkg(Context c) {
        return prefs(c).getString("gamePkg", "");
    }

    public static void setGamePkg(Context c, String v) {
        prefs(c).edit().putString("gamePkg", v == null ? "" : v).apply();
    }

    /** 是否只在游戏处于前台时执行动作。默认开启，避免切出去时乱点。 */
    public static boolean getFgOnly(Context c) {
        return prefs(c).getBoolean("fgOnly", true);
    }

    public static void setFgOnly(Context c, boolean v) {
        prefs(c).edit().putBoolean("fgOnly", v).apply();
    }
}
