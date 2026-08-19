package com.levy.jarvis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class JarvisOrbView extends View {
    public static final int OFFLINE = 0;
    public static final int READY = 1;
    public static final int LISTENING = 2;
    public static final int THINKING = 3;
    public static final int SPEAKING = 4;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int mode = OFFLINE;
    private float phase = 0f;
    private long last = System.nanoTime();

    public JarvisOrbView(Context context) { super(context); init(); }
    public JarvisOrbView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        glow.setStyle(Paint.Style.STROKE);
        paint.setStyle(Paint.Style.STROKE);
        postInvalidateOnAnimation();
    }

    public void setMode(int value) {
        if (mode != value) {
            mode = value;
            invalidate();
        }
    }

    private int accent() {
        switch (mode) {
            case LISTENING: return Color.rgb(54, 226, 255);
            case THINKING: return Color.rgb(123, 148, 255);
            case SPEAKING: return Color.rgb(69, 255, 187);
            case READY: return Color.rgb(70, 207, 255);
            default: return Color.rgb(78, 102, 118);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = System.nanoTime();
        float dt = Math.min(0.05f, (now - last) / 1_000_000_000f);
        last = now;
        float speed = mode == SPEAKING ? 2.2f : mode == THINKING ? 1.7f : mode == LISTENING ? 1.3f : 0.65f;
        phase += dt * speed;

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) * 0.29f;
        int color = accent();
        float pulse = (float)(0.5 + 0.5 * Math.sin(phase * Math.PI * 2));

        glow.setColor(color);
        glow.setAlpha(mode == OFFLINE ? 55 : (int)(85 + 70 * pulse));
        glow.setStrokeWidth(18f + 8f * pulse);
        glow.setShadowLayer(26f + 18f * pulse, 0, 0, color);
        canvas.drawCircle(cx, cy, r, glow);

        paint.clearShadowLayer();
        paint.setColor(color);
        paint.setAlpha(245);
        paint.setStrokeWidth(5.5f);
        canvas.drawCircle(cx, cy, r, paint);

        paint.setStrokeWidth(2.5f);
        paint.setAlpha(145);
        RectF outer = new RectF(cx-r*1.28f, cy-r*1.28f, cx+r*1.28f, cy+r*1.28f);
        canvas.drawArc(outer, phase * 95f, 78f, false, paint);
        canvas.drawArc(outer, phase * 95f + 180f, 52f, false, paint);

        RectF inner = new RectF(cx-r*0.72f, cy-r*0.72f, cx+r*0.72f, cy+r*0.72f);
        paint.setStrokeWidth(3.2f);
        paint.setAlpha(200);
        canvas.drawArc(inner, -phase * 135f, 118f, false, paint);
        canvas.drawArc(inner, -phase * 135f + 205f, 88f, false, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(mode == OFFLINE ? 85 : 220);
        canvas.drawCircle(cx, cy, 8f + 5f * pulse, paint);
        paint.setStyle(Paint.Style.STROKE);

        postInvalidateOnAnimation();
    }
}
