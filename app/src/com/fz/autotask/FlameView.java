package com.fz.autotask;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.view.View;

/**
 * 活跃度火焰：直接用系统 emoji「🔥」画，按进度从底部往上「点亮」。
 *
 * 未点亮的部分是半透明的火苗（远看发灰），点亮的部分是原色火苗；
 * progress 越高，填得越高，整体越红。
 *
 * progress = 今日活跃分 / 150，超过 1 就按满算。
 */
public class FlameView extends View {

    private static final String GLYPH = "\uD83D\uDD25";   // 🔥

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 未点亮部分用去色滤镜：真·灰色火苗，而不是淡橙色的透明火苗。 */
    private final ColorMatrixColorFilter grey;
    private float progress = 0f;
    private float baseline;

    public FlameView(Context c) {
        super(c);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setStyle(Paint.Style.FILL);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0f);
        grey = new ColorMatrixColorFilter(cm);
    }

    /** 0..1；超出范围自动夹住。 */
    public void setProgress(float p) {
        float v = p < 0 ? 0 : (p > 1 ? 1 : p);
        if (Math.abs(v - progress) > 0.001f) {
            progress = v;
            invalidate();
        }
    }

    public float getProgress() {
        return progress;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = (int) dp(20);
        int h = (int) dp(22);
        setMeasuredDimension(resolveSize(w, wSpec), resolveSize(h, hSpec));
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        paint.setTextSize(h * 0.92f);
        Paint.FontMetrics fm = paint.getFontMetrics();
        baseline = h / 2f - (fm.ascent + fm.descent) / 2f;
        float cx = w / 2f;

        // 底：去色的火苗（灰） = 还没点亮的部分
        paint.setColorFilter(grey);
        paint.setAlpha(150);
        c.drawText(GLYPH, cx, baseline, paint);

        // 点亮的部分：裁出下半截，用原色再画一遍，形成「从底部往上填」的效果
        if (progress > 0.02f) {
            float top = h * (1f - progress);
            c.save();
            c.clipRect(0, top, w, h);
            paint.setColorFilter(null);
            paint.setAlpha(255);
            c.drawText(GLYPH, cx, baseline, paint);
            c.restore();
        }
        paint.setColorFilter(null);
        paint.setAlpha(255);
    }
}
