package com.donghuaigu.stencil;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * 原图 / 线稿对比视图。
 *  - 三种模式：只看原图、只看线稿、左右分割对比
 *  - 分割线可直接用手指拖动
 *  - 双指缩放、单指平移
 */
public class CompareView extends View {

    public static final int MODE_ORIGINAL = 0;
    public static final int MODE_RESULT = 1;
    public static final int MODE_COMPARE = 2;

    private Bitmap original, result;
    private int mode = MODE_COMPARE;
    private float splitRatio = 0.5f;
    private float userScale = 1f, panX = 0f, panY = 0f;

    private final Matrix base = new Matrix();
    private final Matrix draw = new Matrix();
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int viewW, viewH, bmW, bmH;
    private float density;
    private ScaleGestureDetector scaleDetector;
    private int dragMode = 0;      // 0 无 / 1 平移 / 2 拖分割线
    private float lastX, lastY;

    public CompareView(Context c, AttributeSet a) {
        super(c, a);
        density = getResources().getDisplayMetrics().density;
        linePaint.setColor(Color.WHITE);
        linePaint.setStrokeWidth(3f * density);
        linePaint.setShadowLayer(4f * density, 0, 0, 0x66000000);
        handlePaint.setColor(0xFF2C6BED);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(13f * density);
        textPaint.setFakeBoldText(true);
        hintPaint.setColor(0xFF8892A0);
        hintPaint.setTextSize(15f * density);
        hintPaint.setTextAlign(Paint.Align.CENTER);
        chipPaint.setColor(0x99000000);
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                userScale *= d.getScaleFactor();
                if (userScale < 1f) userScale = 1f;
                if (userScale > 8f) userScale = 8f;
                invalidate();
                return true;
            }
        });
    }

    public void setImages(Bitmap before, Bitmap after) {
        original = before;
        result = after;
        bmW = (before != null) ? before.getWidth() : 0;
        bmH = (before != null) ? before.getHeight() : 0;
        resetView();
        invalidate();
    }

    public void setMode(int m) {
        mode = m;
        invalidate();
    }

    public int getMode() { return mode; }

    public void resetView() {
        userScale = 1f;
        panX = panY = 0f;
        splitRatio = 0.5f;
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        viewW = w; viewH = h;
    }

    private float dp(float v) { return v * density; }

    private void buildMatrix() {
        if (bmW <= 0 || bmH <= 0 || viewW <= 0 || viewH <= 0) return;
        float s = Math.min((float) viewW / bmW, (float) viewH / bmH);
        base.setScale(s, s);
        base.postTranslate((viewW - bmW * s) / 2f, (viewH - bmH * s) / 2f);
        draw.set(base);
        draw.postScale(userScale, userScale, viewW / 2f, viewH / 2f);
        draw.postTranslate(panX, panY);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xFFEFF1F4);

        if (original == null) {
            canvas.drawText("点击下方按钮选择一张图片", viewW / 2f, viewH / 2f - dp(12), hintPaint);
            canvas.drawText("导入后会自动生成线描稿", viewW / 2f, viewH / 2f + dp(14), hintPaint);
            return;
        }

        buildMatrix();
        Bitmap right = (result != null) ? result : original;

        if (mode == MODE_ORIGINAL) {
            canvas.drawBitmap(original, draw, bitmapPaint);
        } else if (mode == MODE_RESULT) {
            canvas.drawBitmap(right, draw, bitmapPaint);
        } else {
            float sx = viewW * splitRatio;
            canvas.save();
            canvas.clipRect(0, 0, sx, viewH);
            canvas.drawBitmap(original, draw, bitmapPaint);
            canvas.restore();

            canvas.save();
            canvas.clipRect(sx, 0, viewW, viewH);
            canvas.drawBitmap(right, draw, bitmapPaint);
            canvas.restore();

            canvas.drawLine(sx, 0, sx, viewH, linePaint);
            float cy = viewH / 2f;
            canvas.drawCircle(sx, cy, dp(21), handlePaint);
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("‹ ›", sx, cy + dp(5), textPaint);
            drawChip(canvas, "原图", dp(14), dp(30), true);
            drawChip(canvas, "线稿", viewW - dp(14), dp(30), false);
            return;
        }
        drawChip(canvas, (mode == MODE_ORIGINAL) ? "原图" : "线稿", dp(14), dp(30), true);
    }

    private void drawChip(Canvas c, String text, float x, float y, boolean left) {
        float w = textPaint.measureText(text) + dp(20);
        float h = dp(26);
        float l = left ? x : x - w;
        c.drawRoundRect(l, y - h + dp(6), l + w, y + dp(6), dp(6), dp(6), chipPaint);
        textPaint.setTextAlign(Paint.Align.CENTER);
        c.drawText(text, l + w / 2f, y - dp(2), textPaint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (original == null) return true;
        scaleDetector.onTouchEvent(event);
        int action = event.getActionMasked();
        float x = event.getX(), y = event.getY();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (mode == MODE_COMPARE && Math.abs(x - viewW * splitRatio) < dp(44)) {
                    dragMode = 2;
                } else {
                    dragMode = 1;
                }
                lastX = x; lastY = y;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() == 1) {
                    if (dragMode == 2) {
                        splitRatio = clamp(x / viewW, 0.06f, 0.94f);
                    } else if (dragMode == 1) {
                        panX += x - lastX;
                        panY += y - lastY;
                    }
                    invalidate();
                }
                lastX = x; lastY = y;
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                dragMode = 1;
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragMode = 0;
                return true;
        }
        return super.onTouchEvent(event);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    @Override public boolean performClick() {
        return super.performClick();
    }
}
