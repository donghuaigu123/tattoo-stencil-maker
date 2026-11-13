package com.donghuaigu.stencil;

import android.graphics.Bitmap;

/**
 * 线描稿（纹身转印底稿）生成器。
 *
 * 纯 Java 实现，不依赖 OpenCV / NDK / RenderScript，任何 Android 7.0+ 设备均可运行。
 *
 * 处理管线（针对「浅色载体上的深色勾线图案」设计，例如彩绘、烙画、插画照片）：
 *   1. 等比缩放，控制内存与耗时
 *   2. 灰度化 + 轻度高斯模糊，压掉载体表面的高频颗粒纹理
 *   3. 大尺度局部亮度估计 → 生成「背景漂白掩模」，把大片深色背景/桌面统一漂白成 255
 *      （漂白后背景不含任何纹理，且主体与白底形成强边界，轮廓能被完整提取）
 *   4. 局部均值差分 (bg - g2) 提取「比周围暗的细结构」= 深色勾线；
 *      大面积色块因其局部均值同样偏低而不会产生响应，故自动变成白底
 *   5. 原图亮度门控，进一步压制深色背景中的杂散响应
 *   6. 多通道 Sobel 边缘融合，补齐主体外形轮廓（含亮度接近但颜色不同的边界）
 *   7. 高分位归一化 + 固定阈值二值化 → 纯黑线
 *   8. 形态学膨胀（可选）+ 连通域面积过滤，去掉孤立噪点
 *   9. 输出纯白 (255,255,255) 底 + 纯黑 (0,0,0) 线的 ARGB_8888 位图，无任何灰阶
 *
 * 输出为无损 PNG 时即适合热敏纹身转印打印机直接打印。
 */
public final class StencilProcessor {

    private StencilProcessor() {}

    /** 处理后的最长边上限；兼顾清晰度、内存与手机端耗时。 */
    private static final int MAX_SIDE = 1440;
    private static final int DOWN_FACTOR = 8;

    /** 处理进度回调（在后台线程调用）。 */
    public interface Progress {
        void onProgress(int percent, String message);
    }

    /** 可调参数。默认值即「傻瓜模式」，无需用户干预即可得到可用结果。 */
    public static final class Options {
        /** 0 = 细线（线条少而干净），1 = 标准，2 = 浓线（线条更多更粗） */
        public int preset = 1;
        /** 0..100，线条敏感度；50 为标准 */
        public int strength = 50;
        /** 是否漂白深色背景。拍摄白纸线稿时可关闭。 */
        public boolean cleanBackground = true;
    }

    /** 预设对应的内部参数。 */
    private static final class Tuning {
        float threshold;      // 二值化阈值（归一化到 0..255 后）
        float edgeWeight;     // 边缘融合权重
        int minArea;          // 连通域保留的最小像素数
        int dilate;           // 膨胀核边长，1 表示不膨胀
    }

    private static Tuning tuning(Options o) {
        Tuning t = new Tuning();
        switch (o.preset) {
            case 0:  t.threshold = 70f; t.edgeWeight = 0.26f; t.minArea = 60; t.dilate = 1; break;
            case 2:  t.threshold = 48f; t.edgeWeight = 0.45f; t.minArea = 90; t.dilate = 3; break;
            default: t.threshold = 62f; t.edgeWeight = 0.32f; t.minArea = 70; t.dilate = 1; break;
        }
        // 强度滑块：越大 → 阈值越低 → 保留更多线条
        t.threshold -= (o.strength - 50) * 0.28f;
        if (t.threshold < 12f) t.threshold = 12f;
        if (t.threshold > 105f) t.threshold = 105f;
        return t;
    }

    /**
     * 主入口：把任意彩色/黑白图片转为白底黑线的线描稿。
     * 返回的 Bitmap 与输入（缩放后）尺寸一致，便于像素级前后对比。
     */
    public static Bitmap process(Bitmap source, Options options, Progress progress) {
        if (source == null) throw new IllegalArgumentException("source is null");
        Options opt = (options == null) ? new Options() : options;
        Tuning tun = tuning(opt);

        int srcW = source.getWidth(), srcH = source.getHeight();
        float ratio = Math.min(1f, MAX_SIDE / (float) Math.max(srcW, srcH));
        int w = Math.max(1, Math.round(srcW * ratio));
        int h = Math.max(1, Math.round(srcH * ratio));
        Bitmap scaled = (ratio < 1f) ? Bitmap.createScaledBitmap(source, w, h, true) : source;

        int n = w * h;
        report(progress, 5, "读取图片");
        int[] px = new int[n];
        scaled.getPixels(px, 0, w, 0, 0, w, h);

        // ---- 1. 灰度 + 轻度模糊，抑制载体表面颗粒纹理 ----
        float[] buf1 = new float[n];   // gray → bg2 → response
        float[] buf2 = new float[n];   // sm → g2
        float[] buf3 = new float[n];   // 卷积临时缓冲
        for (int i = 0; i < n; i++) {
            int c = px[i];
            buf1[i] = 0.299f * ((c >> 16) & 0xFF)
                    + 0.587f * ((c >> 8) & 0xFF)
                    + 0.114f * (c & 0xFF);
        }
        gaussInto(buf1, buf2, buf3, w, h, boxRadius(1.0f), 3);
        float[] sm = buf2;
        float[] tmp = buf3;
        report(progress, 18, "抑制材质纹理");

        // ---- 2. 背景漂白掩模 ----
        float[] white;
        if (opt.cleanBackground) {
            white = bigBlur(sm, w, h, DOWN_FACTOR);   // 大尺度局部亮度
            for (int i = 0; i < n; i++) {
                float v = (white[i] - 25f) / 60f;
                white[i] = clamp01(v);
            }
        } else {
            white = null;
        }
        report(progress, 34, "净化背景");

        // sm 原地变成 g2（漂白后的灰度图）
        float[] g2 = sm;
        if (white != null) {
            for (int i = 0; i < n; i++) g2[i] = g2[i] * white[i] + 255f * (1f - white[i]);
        }

        // ---- 3. 局部均值差分：提取比周围暗的细结构 ----
        gaussInto(g2, buf1, tmp, w, h, boxRadius(10f), 3);   // buf1 = 局部均值 bg2
        float[] response = buf1;
        for (int i = 0; i < n; i++) {
            float md = buf1[i] - g2[i];
            if (md < 0f) md = 0f;
            response[i] = md * gate(g2[i], white != null ? white[i] : 1f);
        }
        float p0 = percentile(response, 0.995f);
        report(progress, 52, "提取勾线");

        // ---- 4. 多通道 Sobel 边缘融合，补齐主体外形轮廓 ----
        if (tun.edgeWeight > 0f && p0 > 0f) {
            float[] edge = new float[n];
            float[] chan = new float[n];
            for (int c = 0; c < 3; c++) {
                int shift = (c == 0) ? 16 : (c == 1) ? 8 : 0;
                for (int i = 0; i < n; i++) chan[i] = (px[i] >> shift) & 0xFF;
                gaussInto(chan, chan, tmp, w, h, boxRadius(1.0f), 3);
                if (white != null) {
                    for (int i = 0; i < n; i++) chan[i] = chan[i] * white[i] + 255f * (1f - white[i]);
                }
                sobelAccum(chan, edge, w, h);
            }
            sobelAccum(g2, edge, w, h);
            float mp = percentile(edge, 0.99f);
            if (mp > 0f) {
                float inv = 1f / mp;
                for (int i = 0; i < n; i++) {
                    float e = edge[i] * inv;
                    if (e > 1f) e = 1f;
                    float m = e * p0 * tun.edgeWeight * gate(g2[i], white != null ? white[i] : 1f);
                    if (m > response[i]) response[i] = m;
                }
            }
        }
        report(progress, 72, "补齐轮廓");

        // ---- 5. 归一化 + 二值化 → 纯黑线 ----
        float p = percentile(response, 0.995f);
        byte[] ink = new byte[n];
        if (p > 0f) {
            float inv = 255f / p;
            for (int i = 0; i < n; i++) {
                if (response[i] * inv > tun.threshold) ink[i] = 1;
            }
        }
        report(progress, 84, "生成黑白线稿");

        // ---- 6. 后处理：可选加粗 + 去孤立噪点 ----
        if (tun.dilate > 1) ink = dilate(ink, w, h, tun.dilate);
        removeSmallComponents(ink, w, h, tun.minArea);
        report(progress, 94, "清理杂点");

        // ---- 7. 输出：纯白底 + 纯黑线，复用像素数组 ----
        for (int i = 0; i < n; i++) px[i] = (ink[i] != 0) ? 0xFF000000 : 0xFFFFFFFF;
        Bitmap result = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        if (scaled != source) scaled.recycle();
        report(progress, 100, "完成");
        return result;
    }

    private static void report(Progress p, int percent, String msg) {
        if (p != null) p.onProgress(percent, msg);
    }

    /** 亮度门控 + 背景掩模，二者共同把深色杂乱背景压成纯白底。 */
    private static float gate(float v, float bgMask) {
        return clamp01((190f - v) / 130f) * bgMask;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /**
     * 把高斯 σ 换算成 3 次方框模糊的半径。
     * 单次半径 r 的方框核方差为 r(r+1)/3，3 次叠加方差 = r(r+1)，
     * 令 r(r+1) = σ² 得 r = (sqrt(1+4σ²)-1)/2。
     */
    private static int boxRadius(float sigma) {
        int r = (int) Math.floor((Math.sqrt(1f + 4f * sigma * sigma) - 1f) / 2f);
        return r < 1 ? 1 : r;
    }

    // ------------------------------------------------------------------
    // 可分离方框模糊：O(n) 滑动窗口，3 次迭代近似高斯
    // ------------------------------------------------------------------
    private static void gaussInto(float[] in, float[] out, float[] tmp,
                                 int w, int h, int radius, int passes) {
        float[] a = in;
        for (int p = 0; p < passes; p++) {
            boxH(a, tmp, w, h, radius);
            boxV(tmp, out, w, h, radius);
            a = out;
        }
    }

    private static void boxH(float[] src, float[] dst, int w, int h, int r) {
        float inv = 1f / (2 * r + 1);
        for (int y = 0; y < h; y++) {
            int row = y * w;
            double sum = 0;
            for (int k = -r; k <= r; k++) {
                int xx = (k < 0) ? 0 : (k >= w ? w - 1 : k);
                sum += src[row + xx];
            }
            for (int x = 0; x < w; x++) {
                dst[row + x] = (float) (sum * inv);
                int add = x + r + 1; if (add >= w) add = w - 1;
                int rem = x - r;     if (rem < 0) rem = 0;
                sum += src[row + add] - src[row + rem];
            }
        }
    }

    private static void boxV(float[] src, float[] dst, int w, int h, int r) {
        float inv = 1f / (2 * r + 1);
        for (int x = 0; x < w; x++) {
            double sum = 0;
            for (int k = -r; k <= r; k++) {
                int yy = (k < 0) ? 0 : (k >= h ? h - 1 : k);
                sum += src[yy * w + x];
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = (float) (sum * inv);
                int add = y + r + 1; if (add >= h) add = h - 1;
                int rem = y - r;     if (rem < 0) rem = 0;
                sum += src[add * w + x] - src[rem * w + x];
            }
        }
    }

    /** 大尺度模糊：先 1/8 降采样，模糊后再双线性上采样，避免大半径卷积的耗时。 */
    private static float[] bigBlur(float[] src, int w, int h, int factor) {
        int sw = (w + factor - 1) / factor;
        int sh = (h + factor - 1) / factor;
        float[] small = new float[sw * sh];
        for (int sy = 0; sy < sh; sy++) {
            int y0 = sy * factor, y1 = Math.min(y0 + factor, h);
            for (int sx = 0; sx < sw; sx++) {
                int x0 = sx * factor, x1 = Math.min(x0 + factor, w);
                double s = 0; int cnt = 0;
                for (int y = y0; y < y1; y++) {
                    int row = y * w;
                    for (int x = x0; x < x1; x++) { s += src[row + x]; cnt++; }
                }
                small[sy * sw + sx] = (float) (s / cnt);
            }
        }
        float[] blurred = new float[sw * sh];
        float[] tmp = new float[sw * sh];
        gaussInto(small, blurred, tmp, sw, sh, boxRadius(6.25f), 3);  // 降采样图 σ≈6 → 原图 σ≈48

        float[] up = new float[w * h];
        for (int y = 0; y < h; y++) {
            float fy = (y + 0.5f) / factor - 0.5f;
            if (fy < 0f) fy = 0f; if (fy > sh - 1) fy = sh - 1;
            int y0 = (int) Math.floor(fy), y1 = Math.min(y0 + 1, sh - 1);
            float ty = fy - y0;
            for (int x = 0; x < w; x++) {
                float fx = (x + 0.5f) / factor - 0.5f;
                if (fx < 0f) fx = 0f; if (fx > sw - 1) fx = sw - 1;
                int x0 = (int) Math.floor(fx), x1 = Math.min(x0 + 1, sw - 1);
                float tx = fx - x0;
                float a = blurred[y0 * sw + x0], b = blurred[y0 * sw + x1];
                float c = blurred[y1 * sw + x0], d = blurred[y1 * sw + x1];
                float top = a + (b - a) * tx;
                float bot = c + (d - c) * tx;
                up[y * w + x] = top + (bot - top) * ty;
            }
        }
        return up;
    }

    /** Sobel 梯度幅值，逐像素取与已有结果的最大值。 */
    private static void sobelAccum(float[] src, float[] acc, int w, int h) {
        for (int y = 1; y < h - 1; y++) {
            int row = y * w;
            int up = row - w, down = row + w;
            for (int x = 1; x < w - 1; x++) {
                float a00 = src[up + x - 1], a01 = src[up + x], a02 = src[up + x + 1];
                float a10 = src[row + x - 1],                    a12 = src[row + x + 1];
                float a20 = src[down + x - 1], a21 = src[down + x], a22 = src[down + x + 1];
                float gx = -a00 + a02 - 2f * a10 + 2f * a12 - a20 + a22;
                float gy = -a00 - 2f * a01 - a02 + a20 + 2f * a21 + a22;
                float m = (float) Math.sqrt(gx * gx + gy * gy);
                int i = row + x;
                if (m > acc[i]) acc[i] = m;
            }
        }
    }

    /** 用 2048 桶直方图近似分位数，避免全数组排序。 */
    private static float percentile(float[] a, float q) {
        float max = 0f;
        for (float v : a) if (v > max) max = v;
        if (max <= 0f) return 0f;
        int[] hist = new int[2048];
        float k = 2047f / max;
        for (float v : a) {
            int b = (int) (v * k);
            if (b < 0) b = 0; else if (b > 2047) b = 2047;
            hist[b]++;
        }
        long target = (long) (a.length * q), count = 0;
        for (int b = 0; b < 2048; b++) {
            count += hist[b];
            if (count >= target) return ((b + 0.5f) / 2048f) * max;
        }
        return max;
    }

    /** 3x3 方形核膨胀，让线条更饱满、连接细小断口。size=1 时不处理。 */
    private static byte[] dilate(byte[] src, int w, int h, int size) {
        if (size <= 1) return src;
        int r = size / 2;
        byte[] dst = new byte[w * h];
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - r), y1 = Math.min(h - 1, y + r);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - r), x1 = Math.min(w - 1, x + r);
                byte v = 0;
                for (int yy = y0; yy <= y1 && v == 0; yy++) {
                    int row = yy * w;
                    for (int xx = x0; xx <= x1; xx++) {
                        if (src[row + xx] != 0) { v = 1; break; }
                    }
                }
                dst[y * w + x] = v;
            }
        }
        return dst;
    }

    /**
     * 8 连通域清理，两步去掉杂点：
     *  1) 面积小于 minArea 的孤立碎点全删除；
     *  2) 「贴着画面边缘 + 尺寸较小」的孤立组件判为背景杂物（水印、底座、虚化背景残影）删除。
     *     中心区域的细节与长线条（外轮廓弧、葫芦柄）一律保留。
     * BFS 队列同时充当组件像素表，避免额外数组。
     */
    private static void removeSmallComponents(byte[] mask, int w, int h, int minArea) {
        int n = w * h;
        int[] stack = new int[n];
        int margin = (int) (Math.min(w, h) * 0.055f);
        for (int i = 0; i < n; i++) {
            if (mask[i] != 1) continue;
            int head = 0, tail = 0;
            int minX = w, maxX = 0, minY = h, maxY = 0;
            stack[tail++] = i;
            mask[i] = 2;
            while (head < tail) {
                int p = stack[head++];
                int py = p / w, px = p - py * w;
                if (px < minX) minX = px;
                if (px > maxX) maxX = px;
                if (py < minY) minY = py;
                if (py > maxY) maxY = py;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = py + dy;
                    if (yy < 0 || yy >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = px + dx;
                        if (xx < 0 || xx >= w) continue;
                        int q = yy * w + xx;
                        if (mask[q] == 1) { mask[q] = 2; stack[tail++] = q; }
                    }
                }
            }
            int span = Math.max(maxX - minX + 1, maxY - minY + 1);
            boolean nearEdge = minX <= margin || minY <= margin
                    || maxX >= w - margin || maxY >= h - margin;
            boolean drop = (tail < minArea) || (nearEdge && tail < 1500 && span < 220);
            if (drop) {
                for (int k = 0; k < tail; k++) mask[stack[k]] = 0;
            }
        }
    }
}
