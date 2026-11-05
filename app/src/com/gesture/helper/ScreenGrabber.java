package com.gesture.helper;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * 基于 MediaProjection 的取色 / 截图器。
 *
 * 【v1.9 关键修复：横屏方向】
 * 之前直接拿 getRealMetrics() 的宽高建 VirtualDisplay。用户在竖屏（App 主界面）点授权时，
 * 拿到的是 1080x2408，VirtualDisplay 就按竖屏建好了；进游戏转横屏后，系统把 2408x1080 的
 * 画面等比缩进这个竖屏帧（上下留黑边），图标被缩小 2.2 倍，模板匹配从 95% 掉到 32~36%，
 * 表现就是"一直找小怪标识 / 一直重复这一步"。
 *
 * 现在的策略：
 *   1) 建 VirtualDisplay 一律用横屏尺寸（宽取大值、高取小值）——这是横屏游戏；
 *   2) 每次抓图前比对当前显示尺寸，不一致就重建 VirtualDisplay + ImageReader（自愈）；
 *   3) 把真实帧尺寸打出来，出问题一眼能看出来。
 */
public class ScreenGrabber {

    private static final String TAG = "ScreenGrabber";

    private static MediaProjection projection;
    private static ImageReader reader;
    private static VirtualDisplay virtualDisplay;
    private static int width;
    private static int height;
    private static int dpi = 320;
    private static volatile boolean alive;
    private static Intent pendingData;
    private static Context appCtx;
    private static boolean logged;

    /**
     * 【v1.12 关键修复：红蓝互换】
     *
     * ImageReader 用 PixelFormat.RGBA_8888，缓冲区里每个像素的字节顺序是 R,G,B,A。
     * 用 ByteBuffer.getInt() 读出来（小端）得到的是  A<<24 | B<<16 | G<<8 | R，
     * 也就是说 (p>>16)&0xFF 拿到的是**蓝**，(p&0xFF) 才是**红**。
     * 老代码按 (p>>16)=红 / (p&0xFF)=蓝 去拆，红蓝整体反了：
     *   · 灰度按 (B*0.30+G*0.59+R*0.11) 算，而模板是按 (R*0.30+...) 算的 ——
     *     两边权重对不上，同一张图标只能匹配到 94%（对的算法是 98%）；
     *   · 颜色判类型彻底反过来：红包的首领被当成"蓝包小怪"，
     *     于是"找到首领 → 判定像小怪 → 跳过"，表现就是「看到首领图标但不去打」。
     *
     * 这里做一次开机自检：RGBA_8888 的 alpha 恒为 255，看它在高位还是低位就能定字节序。
     */
    private static int order = 0; // 0=未探测 1=RGBA_8888 2=BGRA_8888

    private static void detectOrder(ByteBuffer buf, int rowStride, int pixelStride, int w, int h) {
        if (order != 0 || buf == null) {
            return;
        }
        int hi = 0;
        int lo = 0;
        int n = 0;
        int cx = Math.min(w, 48);
        int cy = Math.min(h, 8);
        for (int y = 0; y < cy; y++) {
            int base = y * rowStride;
            for (int x = 0; x < cx; x++) {
                int off = base + x * pixelStride;
                if (off + 4 > buf.capacity()) {
                    continue;
                }
                int p = buf.getInt(off);
                if (((p >>> 24) & 0xFF) == 0xFF) {
                    hi++;
                }
                if ((p & 0xFF) == 0xFF) {
                    lo++;
                }
                n++;
            }
        }
        if (n == 0) {
            return;
        }
        if (hi >= n * 8 / 10) {
            order = 1;   // 标准 RGBA_8888：R 在最低字节
        } else if (lo >= n * 8 / 10) {
            order = 2;   // 少数设备走 BGRA：B 在最低字节
        } else {
            order = 1;   // 都不是（RGBX / 半透明画面）就按 Android 官方约定
        }
        Log.i(TAG, "pixel order=" + (order == 2 ? "BGRA" : "RGBA") + " (hi=" + hi + " lo=" + lo + " n=" + n + ")");
    }

    private static int redOf(int p) {
        return order == 2 ? ((p >> 16) & 0xFF) : (p & 0xFF);
    }

    private static int greenOf(int p) {
        return (p >> 8) & 0xFF;
    }

    private static int blueOf(int p) {
        return order == 2 ? (p & 0xFF) : ((p >> 16) & 0xFF);
    }

    /** 和 Templates.read 完全一致的灰度公式，两边必须同权重。 */
    private static int grayOf(int r, int g, int b) {
        return (r * 77 + g * 151 + b * 28) >> 8;
    }

    public static void keep(Intent data) {
        pendingData = data;
    }

    public static Intent pending() {
        return pendingData;
    }

    public static boolean isAlive() {
        return alive && projection != null && reader != null;
    }

    /** 当前抓屏帧的真实宽高，用于日志诊断。 */
    public static int[] frameSize() {
        return new int[]{width, height};
    }

    public static synchronized boolean start(Context context, Intent data, int w, int h, int dpiIn) {
        release();
        if (data == null || context == null) {
            return false;
        }
        appCtx = context.getApplicationContext();
        dpi = dpiIn > 0 ? dpiIn : 320;
        // 横屏游戏：统一按横屏建帧，宽高取大小值，不跟随授权时的竖屏方向
        int W = Math.max(w, h);
        int H = Math.min(w, h);
        if (W <= 0 || H <= 0) {
            return false;
        }
        try {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(android.app.Activity.RESULT_OK, data);
            if (projection == null) {
                return false;
            }
            try {
                projection.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        alive = false;
                    }
                }, null);
            } catch (Throwable ignored) {
                // 老系统没有这个 API，忽略
            }
            reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 3);
            virtualDisplay = projection.createVirtualDisplay(
                    "yys-capture", W, H, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, null);
            width = W;
            height = H;
            alive = true;
            logged = false;
            Log.i(TAG, "capture started " + W + "x" + H);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "start failed", t);
            release();
            return false;
        }
    }

    public static synchronized void release() {
        alive = false;
        try {
            if (virtualDisplay != null) {
                virtualDisplay.release();
            }
        } catch (Throwable ignored) {
        }
        virtualDisplay = null;
        try {
            if (projection != null) {
                projection.stop();
            }
        } catch (Throwable ignored) {
        }
        projection = null;
        if (reader != null) {
            try {
                reader.close();
            } catch (Throwable ignored) {
            }
        }
        reader = null;
        width = 0;
        height = 0;
        appCtx = null;
    }

    /**
     * 显示尺寸和当前帧不一致时重建投屏（比如授权在竖屏、游戏转成横屏之后）。
     * 重建失败就继续用旧帧，绝不把可用状态弄坏。
     */
    private static synchronized void ensureSize() {
        if (!alive || projection == null || reader == null || appCtx == null) {
            return;
        }
        int[] sc;
        try {
            sc = Engine.screen(appCtx);
        } catch (Throwable t) {
            return;
        }
        int W = Math.max(sc[0], sc[1]);
        int H = Math.min(sc[0], sc[1]);
        if (W <= 0 || H <= 0 || (W == width && H == height)) {
            return;
        }
        try {
            ImageReader nr = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 3);
            VirtualDisplay nv = projection.createVirtualDisplay(
                    "yys-capture", W, H, sc[2],
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    nr.getSurface(), null, null);
            ImageReader oldReader = reader;
            VirtualDisplay oldDisplay = virtualDisplay;
            reader = nr;
            virtualDisplay = nv;
            width = W;
            height = H;
            try {
                if (oldDisplay != null) {
                    oldDisplay.release();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (oldReader != null) {
                    oldReader.close();
                }
            } catch (Throwable ignored) {
            }
            Log.i(TAG, "capture resized to " + W + "x" + H);
        } catch (Throwable t) {
            Log.w(TAG, "resize failed", t);
        }
    }

    /**
     * 返回 0xRRGGBB，取不到时返回 -1。
     */
    public static int colorAt(int x, int y) {
        ensureSize();
        if (!isAlive() || reader == null) {
            return -1;
        }
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return -1;
        }
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null || image.getPlanes().length == 0) {
                return -1;
            }
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            int offset = y * rowStride + x * pixelStride;
            if (offset + 4 > buffer.capacity()) {
                return -1;
            }
            int pixel = buffer.getInt(offset);
            detectOrder(buffer, rowStride, pixelStride, width, height);
            int r = redOf(pixel);
            int g = greenOf(pixel);
            int b = blueOf(pixel);
            return (r << 16) | (g << 8) | b;
        } catch (Throwable t) {
            return -1;
        } finally {
            if (image != null) {
                try {
                    image.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 抓一帧，同时给出灰度（模板匹配用）和彩色（判类型用）。
     *
     * 为什么一次抓两套：acquireLatestImage 每次拿到的都是不同时刻的帧，
     * 分两次抓的话灰度定位和颜色取样的坐标会对不上（怪在动）。
     */
    public static Vision.Frame grab(int scale) {
        ensureSize();
        if (!isAlive() || reader == null) {
            return null;
        }
        if (scale < 1) {
            scale = 1;
        }
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null || image.getPlanes().length == 0) {
                return null;
            }
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            int w = Math.max(1, width / scale);
            int h = Math.max(1, height / scale);
            int[] g = new int[w * h];
            int[] rgb = new int[w * h];
            int cap = buffer.capacity();
            detectOrder(buffer, rowStride, pixelStride, w, h);
            for (int y = 0; y < h; y++) {
                int rowBase = (y * scale) * rowStride;
                int out = y * w;
                for (int x = 0; x < w; x++) {
                    int off = rowBase + (x * scale) * pixelStride;
                    if (off + 4 > cap) {
                        out++;
                        continue;
                    }
                    int p = buffer.getInt(off);
                    int r = redOf(p);
                    int gg = greenOf(p);
                    int b = blueOf(p);
                    g[out] = grayOf(r, gg, b);
                    rgb[out] = (r << 16) | (gg << 8) | b;
                    out++;
                }
            }
            logOnce(w, h);
            return new Vision.Frame(w, h, g, rgb);
        } catch (Throwable t) {
            return null;
        } finally {
            if (image != null) {
                try {
                    image.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 抓一帧并降采样成灰度图，供模板匹配用。
     * scale=4 时 2408x1080 → 602x270，读取的像素量降到 1/16。
     */
    public static Vision.Frame grabGray(int scale) {
        ensureSize();
        if (!isAlive() || reader == null) {
            return null;
        }
        if (scale < 1) {
            scale = 1;
        }
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null || image.getPlanes().length == 0) {
                return null;
            }
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            int w = Math.max(1, width / scale);
            int h = Math.max(1, height / scale);
            int[] g = new int[w * h];
            detectOrder(buffer, rowStride, pixelStride, w, h);
            for (int y = 0; y < h; y++) {
                int rowBase = (y * scale) * rowStride;
                for (int x = 0; x < w; x++) {
                    int off = rowBase + (x * scale) * pixelStride;
                    if (off + 4 > buffer.capacity()) {
                        g[y * w + x] = 0;
                        continue;
                    }
                    int p = buffer.getInt(off);
                    g[y * w + x] = grayOf(redOf(p), greenOf(p), blueOf(p));
                }
            }
            logOnce(w, h);
            return new Vision.Frame(w, h, g);
        } catch (Throwable t) {
            return null;
        } finally {
            if (image != null) {
                try {
                    image.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void logOnce(int w, int h) {
        if (!logged) {
            logged = true;
            Log.i(TAG, "frame " + w + "x" + h + (h > w ? " (竖屏帧，画面会被压扁)" : " (横屏帧 OK)"));
        }
    }

    /**
     * 在 (cx,cy) 周围 rad 像素的方形区域内，统计与 color 相近（±tol）的像素占比（0~100）。
     * 比单点取色稳得多：怪物、图标会轻微位移，单点很容易采样到背景。
     * 取不到截图时返回 -1。
     */
    public static int ratio(int cx, int cy, int rad, int color, int tol) {
        ensureSize();
        if (!isAlive() || reader == null) {
            return -1;
        }
        if (rad < 0) {
            rad = 0;
        }
        int x0 = Math.max(0, cx - rad);
        int x1 = Math.min(width - 1, cx + rad);
        int y0 = Math.max(0, cy - rad);
        int y1 = Math.min(height - 1, cy + rad);
        if (x1 < x0 || y1 < y0) {
            return -1;
        }
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null || image.getPlanes().length == 0) {
                return -1;
            }
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            int step = rad <= 14 ? 1 : (rad <= 34 ? 2 : 4);
            int tr = (color >> 16) & 0xFF;
            int tg = (color >> 8) & 0xFF;
            int tb = color & 0xFF;
            detectOrder(buffer, rowStride, pixelStride, width, height);
            int hit = 0;
            int total = 0;
            for (int y = y0; y <= y1; y += step) {
                int base = y * rowStride;
                for (int x = x0; x <= x1; x += step) {
                    int off = base + x * pixelStride;
                    if (off + 4 > buffer.capacity()) {
                        continue;
                    }
                    int p = buffer.getInt(off);
                    int r = redOf(p);
                    int g = greenOf(p);
                    int b = blueOf(p);
                    total++;
                    if (Math.abs(r - tr) <= tol && Math.abs(g - tg) <= tol
                            && Math.abs(b - tb) <= tol) {
                        hit++;
                    }
                }
            }
            if (total == 0) {
                return -1;
            }
            return hit * 100 / total;
        } catch (Throwable t) {
            return -1;
        } finally {
            if (image != null) {
                try {
                    image.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
