# 阴阳师探索挂机助手 · 源码工程（v1.22.0）

> ⚠️ **法律与合规提醒**：自动化脚本/无障碍模拟点击违反《阴阳师》用户协议，存在封号风险。本工程仅用于学习 Android 无障碍、MediaProjection、模板匹配等技术的用途，请使用小号、控制运行时长，风险自负。
>
> 本目录为**完整可编译源码工程**，配套 `使用说明.md`（面向使用者）与本文件（面向开发者）。

---

## 1. 工程结构

```
源码工程_v1.22.0/
├── README.md                 # 本文件：开发维护指南
├── 使用说明.md               # 面向使用者的说明
├── build.sh                  # 纯命令行构建脚本（aapt2→javac→d8→zipalign→签名）
├── yys.keystore              # 固定签名（覆盖安装不丢数据的关键，务必保留）
├── app/                      # 标准 Android 工程
│   ├── AndroidManifest.xml
│   ├── res/                  # 布局、Drawable、values、无障碍配置
│   ├── assets/               # 8 张识别模板 PNG（小怪/首领/宝箱/章节木牌）
│   └── src/com/gesture/helper/
│       ├── MainActivity.java     # 入口、权限申请、控制台启动
│       ├── AutoService.java      # 无障碍服务：注入点击/滑动手势、回退
│       ├── FloatService.java     # 悬浮控制台（日志、开始/暂停/停止、最小化）
│       ├── Engine.java           # ★核心：流程状态机、界面识别、翻屏、进图
│       ├── Vision.java           # 模板匹配引擎（NCC、多尺度、区域限定）
│       ├── Templates.java        # 模板加载、裁图、SCALES 多尺度
│       ├── ScreenGrabber.java    # MediaProjection 取帧、方向自愈、灰度化
│       ├── Scripts.java          # 动作方案（preset/load/save/migrate）
│       └── Model.java            # 方案节点数据模型
├── tools/                    # Python 仿真与验证工具（复算匹配分数，无需手机）
│   ├── sim.py                # 核心：frame_from / tpl_from / search_one / search_multi / SCALES
│   ├── verify_any.py         # 验证「小怪+首领」合并模板区分度
│   ├── verify_chapter.py     # 验证章节木牌阈值
│   ├── verify_chest.py       # 验证宝箱模板
│   ├── verify_chest2.py      # 宝箱多场景复算
│   ├── verify_rect.py        # 验证区域限定 searchInRect 效果
│   ├── verify_v20.py         # v1.20 宝箱判定顺序复算
│   ├── verify_v22.py         # v1.22 庭院判定阈值复算
│   └── mk_shot_v20~v22.py    # 生成「识别效果-*.png」示意图
├── docs/                     # 历版识别效果图（给使用者看，也用于理解判定）
└── samples/                  # 调试样本图（复跑 verify_*.py 用，含几张典型卡点截图）
```

---

## 2. 环境依赖

| 依赖 | 版本/说明 |
|---|---|
| JDK | 11（`javac --release 11`） |
| Android SDK | `build-tools/33.0.2` + `platforms/android-33` |
| Python | 3.x，需 `numpy`、`Pillow`（仅 `tools/` 用到，用于离线验证） |
| adb | 用于安装 |

SDK 路径默认 `/opt/android-sdk`，可用环境变量覆盖：
```bash
ANDROID_HOME=/你的/sdk/路径 bash build.sh
```

---

## 3. 编译与安装

```bash
# 在工程根目录
chmod +x build.sh
bash build.sh
# 产物：build/app.signed.apk

# 安装（覆盖安装，数据不丢）
adb install -r build/app.signed.apk
```

构建共 6 步：`aapt2 compile` → `aapt2 link`（含 assets 与版本号）→ `javac` → `d8`（dex）→ `zipalign` → `apksigner` 签名。
**升级版本时改 `build.sh` 末尾的 `VER` / `VNAME` 两行，并同步 `app/AndroidManifest.xml` 的 `versionCode` / `versionName`。**

---

## 4. 运行前准备（真机）

1. 系统设置 → 无障碍 → 找到「阴阳师助手」并开启（绑定 `AutoService`）。
2. 授权「显示在其他应用上层」（悬浮控制台需要）。
3. 授权「屏幕录制 / 媒体投影」（取帧用，绑定 `ScreenGrabber`）。
4. 打开阴阳师，进到**探索主线章节选择**界面，再点控制台「开始」。

---

## 5. 模块职责一览

| 文件 | 职责 |
|---|---|
| `MainActivity` | 申请权限、校验无障碍是否开启、启动悬浮窗 |
| `AutoService` | 无障碍入口；`tap()/swipe()/back()` 注入手势；前台检测可用 |
| `FloatService` | 悬浮控制台 UI：实时日志、开始/暂停/停止、最小化成「御」字小球 |
| **`Engine`** | **整个刷本流程**：界面识别（地图/章节/庭院/宝箱/退出框）→ 翻屏扫描 → 点怪 → 战后收尾 → 循环 |
| `Vision` | 模板匹配核心：`match()` 归一化互相关、`searchMulti()`、区域限定 `searchInRect()`、掩模/整块两种模式 |
| `Templates` | 按 `which` 取模板组；`CROP`/`CROP_BIG` 裁图尺寸；`SCALES` 多尺度 |
| `ScreenGrabber` | MediaProjection 抓帧、方向自愈（横屏）、`grabGray()` 出灰度帧 |
| `Scripts` | 动作方案模型与持久化（preset/load/save/`migrate`），`scriptVer=14` |
| `Model` | 方案节点（`Node`）数据类 |

---

## 6. 识别引擎原理（必读）

`Vision` 用**归一化互相关（NCC）** 比对模板与画面子区域，分数 0~1 越高越像。

- **灰度公式**：`gray = (r*77 + g*151 + b*28) >> 8`（`Vision` 内，与 `tools/sim.py` 完全一致）。
- **运行时帧**：抓到的屏幕按 `Vision.SCALE=4` 缩小（如 2408×1080 → 602×270），匹配在此小帧上做，速度快。
- **多尺度**：`Templates.SCALES = {0.88, 1.00, 1.14}`，同模板缩成 3 套再各搜一次，抗分辨率/缩放差异。
- **两种匹配模式**：
  - **圆形掩模**（`maskIndex`，给带光晕的怪物图标）——只比圆形主体。
  - **整块方形**（`allIndex`，给 UI 图标如宝箱、章节木牌）——比整张方形。
- **区域限定**：`searchInRect(fr, tpls, fx0, fy0, fx1, fy1, th, full)` 只在一块归一化矩形内搜，避免误点右下角喂食小纸人、误判地图为章节木牌。
- **坐标换算**：模板 `Hit` 存的是小帧上的左上角；点击时 `坐标*SCALE` 还原到真实像素并叠加随机抖动（防检测）。

> 改任何阈值/模板后，**务必用 `tools/` 里的脚本离线复算分数**，确认「真目标分够高、误检目标分够低、两条线之间有余量」再上机。

---

## 7. 关键参数速查表

> 行号以 v1.22.0 为准，升级后请用 `grep -n` 复核。

| 参数 | 值 | 位置 | 说明 |
|---|---|---|---|
| `CHEST_TH`（宝箱阈值） | `0.80f` | `Engine.java:152` | 真宝箱 0.86~0.92，非宝箱界面最高 0.76，留 0.05+ 余量 |
| 章节木牌阈值 | `0.62f` | `Engine.java:191` `findChapter(fr,0.62f)` | 限右下角 `0.58~1.0, 0.72~1.0`；地图仅 0.45，余量 0.17 |
| 退出框判定 | 中央 70% 纯白 | `Engine.java:217` `quitDialog()` | 正常画面最高 16.9%，很安全 |
| 庭院判定 | 底部金色按钮 ≥8% | `Engine.java:251` `yardScreen()` | 庭院 19~26%，其余界面 0%，余量 11% |
| 庭院章名落点 | `{0.833,0.652}/{0.833,0.506}/{0.833,0.359}` | `Engine.java:282` `YARD_ROWS` | 从最底行往上点；x 比例 0.833 |
| 翻屏强度 | 每侧 `PER_SIDE=3` 屏 | `Engine.java:48` `Scan` | 每屏约 0.6 屏宽，两侧共约 3.6 屏 |
| 拖屏/帧差阈值 | `>6` | `Engine.java:116,388` | 换视角均差 37~44，静止 <6；宁可多翻不误判清场 |
| 找怪方案 | `tpl=2, pct=90, times=20, ms=900000` | `Scripts.java:139` | 95% 置信；`GO_X=0.8698, GO_Y=0.9268` 为「出战」按钮 |
| 模板裁图尺寸 | `CROP=104, CROP_BIG=130` | `Templates.java:32,39` | 怪物 104、章节木牌 130 |
| 多尺度 | `{0.88,1.00,1.14}` | `Templates.java:48` | 抗缩放 |
| 缩小倍数 | `SCALE=4` | `Vision.java:26` | 运行时帧相对真实屏 |
| 包名 | `com.gesture.helper` | `AndroidManifest.xml` / `build.sh` | 改包名需同步两处 |
| 版本号 | `code 23 / name 1.22.0` | `build.sh` + `AndroidManifest.xml` | 升级改这两处 |
| 方案版本 | `scriptVer=14` | `Scripts.java:247` `migrate()` | 改方案结构时 +1，旧缓存自动清 |

**界面识别优先级（每轮主循环开头）**：退出框 → 庭院（点章节进图）→ 章节选择（点探索）→ 宝箱 → 找怪翻屏。

---

## 8. 调试/验证方法论（无需手机）

`tools/` 下脚本直接读取 `samples/` 或任意截图，复算模板匹配分数，思路与手机端 `Vision` **完全一致**（同灰度公式、同 SCALES）。

```bash
cd tools
# 复算某张图里宝箱/章节/怪的分数分布（先按脚本内路径改 samples）
python3 verify_chest2.py
python3 verify_chapter.py
python3 verify_v22.py        # 庭院判定阈值验证

# 生成带框的识别效果示意图
python3 mk_shot_v22.py
```

典型用法（`sim.py` 核心 API）：
```python
import sim
fr, rgb = sim.frame_from("samples/yard2.jpg")      # 读图→小帧(灰度)+rgb
tpls = [sim.tpl_from("app/assets/tpl_chest_a.png", 2408, s)[0] for s in sim.SCALES]
hits = sim.search_multi(fr, tpls, 4, 0.60)          # top4，阈值0.60
# hits: [(score, x, y, n), ...]  x,y 为小帧坐标，真实像素 = (x+n//2)*4
```

**坐标体系**：所有点击坐标按「**1920×861**」基准（游戏内容区域）量取，运行时乘真实屏宽高。模板按 `round(CROP * 2408/1920 * scale)` 缩放。换机型分辨率时，模板缩放已自动适配，**但固定落点坐标（如 YARD_ROWS、GO_X/Y）是按当前机型比例量取的，需重新量**。

---

## 9. 如何新增 / 替换识别模板

1. 在游戏内截一张目标图，裁出干净的目标小图（去背景干扰）。
2. 放到 `app/assets/`，命名 `tpl_xxx.png`。
3. 在 `Templates.java` 的 `get(which)` 里把新图编入对应组（`which`：0 小怪 / 1 首领 / 2 合并 / 3 宝箱 / 4 章节木牌）。
4. 若是 UI 图标用**整块**模式，怪物类用**圆形掩模**（`maskIndex`）。
5. 用 `tools/` 脚本复算分数，定阈值（参考第 7 节余量原则）。
6. 重新 `bash build.sh`。

---

## 10. 适配新机型 / 分辨率

- 模板匹配自带多尺度，分辨率差异基本不用动。
- **需重量的固定坐标**：`Engine.YARD_ROWS`（庭院章名）、`Scripts.GO_X/GO_Y`（出战）、`dragMap` 拖屏中点 y、`enterFromYard` 等——这些都是按 iQOO Z1x（横屏 2408×1080）比例量取的。
- 抓帧方向自愈在 `ScreenGrabber`，换横竖屏逻辑留意这里。

---

## 11. 版本演进（关键节点）

| 版本 | 关键修复 |
|---|---|
| v1.12 | 首领在眼前却跳过 → 颜色判不出时按轮廓直接打，多尺度模板 |
| v1.13 | 横屏可拖大地图一屏打完误判清场 → 加左右翻屏扫描 |
| v1.14 | 「搜到首领可直接打」→ 三步骤合并单步，首领优先 |
| v1.15 | 打完不重复 → 战后点中下方+点中央领箱，首领打完继续 |
| v1.16 | 宝箱点偏 → 裁专用模板 + 方形全块匹配 |
| v1.17 | 打完无宝箱退回章节页识别不到 → 裁章节木牌模板 |
| v1.18 | 界面判定时机错 → 每轮开头先判章节/宝箱，区域限定搜索 |
| v1.19 | 宝箱多个/卡章节列表 → 阈值 0.85、最多点 4 个；tryEnterStage 点坐标进图 |
| v1.20 | 宝箱不点就卡 → 每轮无条件先找宝箱（阈值 0.80、整屏）；删盲点返回键逻辑前身 |
| v1.21 | 上一版返回键误弹「确认退出」卡死 → **彻底删除返回键**；加退出框自救；点怪后必点出战 |
| v1.22 | 打完首领回庭院（非章节页）空转 → 认庭院后直接点章节进副本 |

---

## 12. 已知坑与注意事项

1. **`yys.keystore` 绝对不能丢**：丢了就无法同签名覆盖安装，用户已存的方案/设置会丢。重签必须用这个文件。
   - 签名信息：`alias=yys`，`storepass=yys123456`，`keypass=yys123456`。
2. **悬浮窗遮挡会让识别分数掉**：控制台默认压在左半屏，宝箱常刷在中间偏左，会被挡掉一半 → 分数上不去。**开跑后点「—」收成「御」字小球**再观察。
3. **返回键已彻底移除**：历史上一次误用返回键直接弹出游戏「确认退出」导致卡死，现在任何分支都不按返回。
4. **自动换章未做**：脚本只在当前章循环，不主动切章（各章解锁/难度按钮不同，怕点错）。使用者需手动选章。
5. **`scriptVer` 与 `migrate`**：改了方案结构（节点字段）时把 `Scripts.migrate()` 里的 `scriptVer` 从 14 再加 1，旧缓存会被自动清空，避免崩溃。
6. **模板分辨率陷阱**：`templates` 的 `2408` 是量坐标时用的参考屏宽，不是真机必须宽度；真机分辨率由 `Engine.screen()` 的 `DisplayMetrics` 决定。
7. **日志即调试入口**：控制台每行带「怪 x% / 宝箱 x%」，卡住时截**整屏图 + 最后 10 行日志**即可定位是「没认出来」还是「流程没走到」。

---

## 13. 二次开发 Checklist

- [ ] 改完 `Engine` 阈值/坐标 → 用 `tools/verify_*.py` 复算分数留余量
- [ ] 改完 `build.sh` 版本号 → 同步 `AndroidManifest.xml`
- [ ] 加模板 → 编入 `Templates.get(which)` + 离线验证
- [ ] 新机型 → 重测 `YARD_ROWS` / `GO_X/Y` / `dragMap` 坐标
- [ ] 改方案结构 → `Scripts.scriptVer` +1
- [ ] 编译 → `bash build.sh` → `adb install -r build/app.signed.apk`
