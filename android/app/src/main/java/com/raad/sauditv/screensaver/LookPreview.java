package com.raad.sauditv.screensaver;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/**
 * The pictures in «شكل الواجهة» (SettingsActivity.pickLook), as TilePreview draws the apps' looks: a small home screen in
 * each look, drawn (no image file), 16:9. «الكلاسيكي»: the photo under its green-black veil, the dates and the weather
 * on top, the big clock in the middle beside the glass card, the info strip and the ticker tape at the bottom.
 * «الجديد» (design/concepts/1-ambient-editorial): the dusk photo, the pills and the dateline, the clock and its
 * standfirst on the right, the «اليوم» row with its focused card in paper, the apps row, the «الأسواق» line.
 * Laid out in the page's 960 x 540 and scaled to the picture.
 */
final class LookPreview extends View {
    static final int W_DP = 128, H_DP = 72;   // the picture; each row of the list at least TilePreview.ROW_MIN_DP

    private final boolean nw;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path hill = new Path(), clip = new Path();
    private final int w, h;
    private float k;

    LookPreview(Context c, String look) {
        super(c);
        nw = Look.NEW.equals(Look.of(look));
        float d = c.getResources().getDisplayMetrics().density;
        w = Math.round(W_DP * d);
        h = Math.round(H_DP * d);
    }

    @Override protected void onMeasure(int ws, int hs) { setMeasuredDimension(w, h); }

    /** a rounded box in the page's 960 x 540 */
    private void box(float x, float y, float bw, float bh, float rad, int color) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        r.set(x * k, y * k, (x + bw) * k, (y + bh) * k);
        c.drawRoundRect(r, rad * k, rad * k, p);
    }

    private Canvas c;

    @Override protected void onDraw(Canvas canvas) {
        c = canvas;
        k = w / 960f;
        float rad = w * 6f / W_DP;   // 6dp corners
        canvas.save();
        r.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(r, rad, rad, Path.Direction.CW);
        canvas.clipPath(clip);
        // the photo: a sky and a dark hill
        p.setShader(new LinearGradient(0, 0, 0, h, nw ? new int[] {0xFF2E2A5A, 0xFF6B4B6E, 0xFF0B0A12} : new int[] {0xFF1E3A44, 0xFF5E6E7A, 0xFF040E09},
            new float[] {0f, .45f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, p);
        hill.reset();
        hill.moveTo(0, 200 * k);
        hill.cubicTo(120 * k, 150 * k, 260 * k, 170 * k, 420 * k, 300 * k);
        hill.lineTo(960 * k, 420 * k);
        hill.lineTo(960 * k, 540 * k);
        hill.lineTo(0, 540 * k);
        hill.close();
        p.setShader(null);
        p.setColor(0xFF120F14);
        canvas.drawPath(hill, p);
        // the veil (the page's scrim): darker at the bottom
        p.setShader(new LinearGradient(0, 0, 0, h, new int[] {0x66000000, 0x00000000, 0xE6000000}, new float[] {0f, .4f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, p);
        if (nw) drawNew(); else drawClassic();
        canvas.restore();
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1, k * 3));
        p.setColor(0x33FFFFFF);
        r.set(0, 0, w, h);
        canvas.drawRoundRect(r, rad, rad, p);
    }

    private void clock(String t, float x, float y, float size, int color) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        p.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        p.setTextSize(size * k);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText(t, x * k, y * k, p);
    }

    private void drawClassic() {
        box(700, 34, 220, 26, 6, 0xE6F6EFE0);   // the Hijri date
        box(760, 70, 160, 14, 5, 0x99C3B8A2);   // the Gregorian
        box(40, 34, 150, 26, 6, 0xE6F6EFE0);    // the weather
        box(40, 70, 110, 14, 5, 0x99C3B8A2);
        clock("3:24", 900, 300, 190, 0xFFFFFDF6);
        box(40, 150, 360, 220, 18, 0x99040E09);  // the card, with its rows
        for (int i = 0; i < 5; i++) box(64, 182 + i * 38, i % 2 == 0 ? 300 : 260, 12, 5, 0x99F6EEDC);
        for (int i = 0; i < 3; i++) box(60 + i * 320, 430, 200, 14, 5, 0xB3C6BBA4);   // the info strip
        box(0, 488, 960, 52, 0, 0x99040E09);     // the ticker tape
        for (int i = 0; i < 6; i++) box(40 + i * 160, 508, 110, 12, 5, 0xB3D8CDB6);
    }

    private void drawNew() {
        for (int i = 0; i < 4; i++) box(40 + i * 92, 22, 82, 30, 15, 0xB3090810);   // the pills
        box(600, 28, 320, 16, 6, 0xD9FBF6EC);     // the dateline
        clock("3:24", 920, 158, 150, 0xFFFBF6EC);
        box(392, 64, 220, 12, 5, 0xFFEBCB8B);     // the standfirst: kicker, two lines, meta
        box(372, 88, 240, 20, 7, 0xF2FBF6EC);
        box(452, 116, 160, 20, 7, 0xF2FBF6EC);
        box(412, 146, 200, 12, 5, 0xB3FBF6EC);
        box(40, 186, 840, 3, 1, 0x73EBCB8B);      // «اليوم» and its rule
        box(890, 178, 30, 16, 5, 0xFFFBF6EC);
        box(632, 206, 288, 164, 14, 0xFFF6EFE1);  // the focused card, in paper
        box(656, 230, 120, 14, 5, 0xFF875A14);
        box(740, 262, 156, 30, 8, 0xFF1C160F);
        for (int i = 0; i < 5; i++) box(660 + i * 52, 320, 22, 22, 11, i < 3 ? 0xFF8E7F69 : 0xFFD3C6B0);
        box(280, 210, 340, 164, 14, 0xA80B0A12);  // the news, the weather, the match
        for (int i = 0; i < 3; i++) box(304, 250 + i * 38, 290, 14, 5, 0xCCFBF6EC);
        box(150, 210, 118, 164, 14, 0xA80B0A12);
        box(0, 210, 138, 164, 14, 0xA80B0A12);
        int[] apps = {0xFF4B2C9C, 0xFF1A1A1A, 0xFFFFFFFF, 0xFF050505, 0xFF0B78B4, 0xFF007F63, 0xFFFFFFFF};
        for (int i = 0; i < apps.length; i++) box(920 - 124 - i * 136, 410, 124, 70, 10, apps[i]);   // the apps row
        box(40, 500, 880, 2, 1, 0x40FFFFFF);      // the «الأسواق» line
        box(860, 512, 60, 12, 5, 0xFFEBCB8B);
        for (int i = 0; i < 4; i++) box(80 + i * 190, 512, 150, 12, 5, 0xB3FBF6EC);
    }
}
