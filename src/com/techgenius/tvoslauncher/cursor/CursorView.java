package com.techgenius.tvoslauncher.cursor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

/** Full-screen, non-touchable overlay that draws the pre-rendered cursor picture. */
public class CursorView extends View {

    private final float u;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private CursorArt.Sprite sprite;
    private int style = -1;
    private float cx, cy;
    private long pulseUntil;

    public CursorView(Context c) {
        super(c);
        u = CursorArt.unit(c);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(1.6f * u);
        ring.setColor(0xCCFFFFFF);
        ringFill.setStyle(Paint.Style.FILL);
        ringFill.setColor(0x3366CCFF);
        setStyle(CursorArt.CHROME);
    }

    public void setStyle(int s) {
        if (s == style && sprite != null) return;
        style = s;
        sprite = CursorArt.render(getContext(), s);
        invalidate();
    }

    public CursorArt.Sprite sprite() {
        return sprite;
    }

    public void moveTo(float x, float y) {
        cx = x;
        cy = y;
        invalidate();
    }

    /** Short ring flash so you can see a click happened. */
    public void pulse() {
        pulseUntil = SystemClock.uptimeMillis() + 220;
        invalidate();
        postInvalidateDelayed(240);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (SystemClock.uptimeMillis() < pulseUntil) {
            canvas.drawCircle(cx, cy, 12 * u, ringFill);
            canvas.drawCircle(cx, cy, 12 * u, ring);
        }
        if (sprite != null) canvas.drawBitmap(sprite.bmp, cx - sprite.ax, cy - sprite.ay, null);
    }
}
