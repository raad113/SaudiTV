package com.raad.sauditv.screensaver;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Checkable;
import android.widget.LinearLayout;
import android.widget.ListAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The pictures in «شكل التطبيقات» and «شكل التحديد» (SettingsActivity.pickTileLook): small app tiles drawn as the home
 * screen draws them (the launcher's MainActivity: its banners with rounded corners or its circles, Google TV's ring or
 * Apple TV's lift, shadow and sheen), at a smaller size, with real apps' icons. Static pictures, no image files.
 */
final class TilePreview {
    private TilePreview() {}

    // the home screen's values (the launcher's MainActivity): a dock banner at most 58dp tall (16:9) with 15dp corners,
    // a dock circle 63dp across; the focused tile 1.10 times (Google TV, a 2.25dp dark gap then a 2.25dp white ring, the
    // others dimmed by 30% black) or 1.12 times and 2.25dp up (Apple TV, a soft shadow under it and a sheen over it)
    static final float BANNER_H_DP = 58f, TILE_RADIUS_DP = 15f, CIRCLE_DP = 63f, DESIGN_DP = 0.75f;
    static final float GOOGLE_SCALE = 1.10f, APPLE_SCALE = 1.12f, APPLE_LIFT_DP = 3 * DESIGN_DP, DIM = 0.3f;
    static final float RING_GAP_DP = 3 * DESIGN_DP, RING_DP = 3 * DESIGN_DP;
    static final int RING_GAP_COLOR = 0xFF08090B, RING_COLOR = Color.WHITE, ICON_DISC = 0xFF2C2E33, BANNER_BG = 0xFF1C1C1E;
    static final float[][] BANNER_SHADOW = {{18, 28, -6, .75f}, {6, 12, 0, .4f}}, CIRCLE_SHADOW = {{16, 24, -6, .8f}, {6, 12, 0, .45f}};

    // the pictures: «شكل التطبيقات» 3 tiles 44dp tall (a banner 16:9, a circle 44dp across), «شكل التحديد» 2 tiles 52dp
    // tall in the current shape; 8dp apart; each row of the list at least 72dp tall
    static final int SHAPE_DP = 44, FOCUS_DP = 52, GAP_DP = 8, ROW_MIN_DP = 72, SHAPE_TILES = 3, FOCUS_TILES = 2;
    /** Apple TV's shadow in the picture, smaller than the home screen's (scaled with the tile, times this): it stays
     *  inside its row, which is as tall as Google TV's */
    static final float SHADOW_FIT = 0.4f;

    static final String HOME_PKG = "com.raad.sauditv.home";

    /** an app as a tile shows it: its icon (a circle), its banner or else its icon on its colour (a banner) */
    static final class App {
        Drawable icon, banner;
        int color = 0xFF3A3A3A;
        boolean fullBleed;
    }

    /**
     * The apps in the pictures: the favourites row can't be read from here (the launcher keeps it to itself), so the TV
     * apps (LEANBACK_LAUNCHER) by name, then the other apps by name, without ours and the home screens, the first 3;
     * fewer: ours (the launcher, this app), repeated to 3. Loads icons: not on the main thread.
     */
    static List<App> apps(Context c) {
        PackageManager pm = c.getPackageManager();
        Set<String> skip = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0))
            skip.add(ri.activityInfo.packageName);
        List<ResolveInfo> picked = new ArrayList<>();
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            List<ResolveInfo> kind = new ArrayList<>();
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                String p = ri.activityInfo.packageName;
                if (p.startsWith("com.raad.sauditv.") || skip.contains(p)) continue;
                skip.add(p);
                kind.add(ri);
            }
            final java.util.Map<ResolveInfo, String> name = new java.util.HashMap<>();
            for (ResolveInfo ri : kind) name.put(ri, String.valueOf(ri.loadLabel(pm)));
            Collections.sort(kind, (x, y) -> name.get(x).compareToIgnoreCase(name.get(y)));
            for (ResolveInfo ri : kind) if (picked.size() < SHAPE_TILES) picked.add(ri);
        }
        List<App> out = new ArrayList<>();
        for (ResolveInfo ri : picked) { App a = app(pm, ri.activityInfo); if (a != null) out.add(a); }
        if (out.size() < SHAPE_TILES) {   // ours: the launcher's icon, this app's
            List<App> own = new ArrayList<>();
            for (String p : new String[] {HOME_PKG, c.getPackageName()}) {
                Intent i = pm.getLaunchIntentForPackage(p);
                if (i == null) i = pm.getLeanbackLaunchIntentForPackage(p);
                ResolveInfo ri = i == null ? null : pm.resolveActivity(i, 0);
                App a = ri == null ? null : app(pm, ri.activityInfo);
                if (a != null) own.add(a);
            }
            for (int k = 0; out.size() < SHAPE_TILES && !own.isEmpty(); k++) out.add(own.get(k % own.size()));
        }
        return out;
    }

    private static App app(PackageManager pm, ActivityInfo ai) {
        try {
            App a = new App();
            Drawable icon;
            try { icon = pm.getApplicationIcon(ai.applicationInfo); } catch (RuntimeException e) { icon = ai.loadIcon(pm); }
            a.icon = icon;
            a.fullBleed = !adaptive(icon) && fullBleed(icon);
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            a.banner = banner;
            if (banner == null) a.color = sampleColor(ai.loadIcon(pm));
            return a;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean adaptive(Drawable d) {
        return Build.VERSION.SDK_INT >= 26 && d instanceof android.graphics.drawable.AdaptiveIconDrawable;
    }

    /** «شكل التطبيقات»'s picture: 3 tiles of that shape */
    static View shapes(Context c, boolean circle, List<App> apps) {
        LinearLayout row = row(c);
        int h = dp(c, SHAPE_DP);
        for (int i = 0; i < Math.min(SHAPE_TILES, apps.size()); i++)
            row.addView(new MiniTile(c, apps.get(i), circle, h, false, false, false), gap(c, i));
        return row;
    }

    /** «شكل التحديد»'s picture: 2 tiles of the current shape, the second in focus in that look (Google TV: the other
     *  dimmed) */
    static View focus(Context c, boolean circle, boolean apple, List<App> apps) {
        LinearLayout row = row(c);
        int h = dp(c, FOCUS_DP);
        for (int i = 0; i < Math.min(FOCUS_TILES, apps.size()); i++) {
            boolean focused = i == FOCUS_TILES - 1;
            row.addView(new MiniTile(c, apps.get(i), circle, h, focused, apple, !focused && !apple), gap(c, i));
        }
        return row;
    }

    private static LinearLayout row(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        return row;
    }

    private static LinearLayout.LayoutParams gap(Context c, int i) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        if (i > 0) p.setMarginStart(dp(c, GAP_DP));
        return p;
    }

    interface Pictures { View of(int position); }

    /**
     * The dialog's own list (setSingleChoiceItems: its theme, radio, focus and mouse as before), each row with its
     * picture beside the label and at least ROW_MIN_DP tall. Set on the dialog's ListView once it is shown.
     */
    static ListAdapter withPictures(final Context c, final ListAdapter base, final Pictures pictures) {
        return new BaseAdapter() {
            @Override public int getCount() { return base.getCount(); }
            @Override public Object getItem(int i) { return base.getItem(i); }
            @Override public long getItemId(int i) { return base.getItemId(i); }
            @Override public boolean hasStableIds() { return base.hasStableIds(); }
            @Override public boolean areAllItemsEnabled() { return base.areAllItemsEnabled(); }
            @Override public boolean isEnabled(int i) { return base.isEnabled(i); }
            @Override public View getView(int i, View convert, ViewGroup parent) {
                View label = base.getView(i, null, parent);
                CheckRow r = new CheckRow(c, label instanceof Checkable ? (Checkable) label : null);
                r.setOrientation(LinearLayout.HORIZONTAL);
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setMinimumHeight(dp(c, ROW_MIN_DP));
                r.setClipChildren(false);
                r.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
                View pic = pictures.of(i);
                if (pic != null) {
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
                    p.setMarginEnd(dp(c, 24));   // the dialog's own side padding
                    p.topMargin = p.bottomMargin = dp(c, 6);
                    r.addView(pic, p);
                }
                return r;
            }
        };
    }

    /** a list row whose radio is its label's (the ListView ticks the row) */
    static final class CheckRow extends LinearLayout implements Checkable {
        private final Checkable inner;
        CheckRow(Context c, Checkable inner) { super(c); this.inner = inner; }
        @Override public void setChecked(boolean on) { if (inner != null) inner.setChecked(on); }
        @Override public boolean isChecked() { return inner != null && inner.isChecked(); }
        @Override public void toggle() { if (inner != null) inner.toggle(); }
    }

    /**
     * One tile: the home screen's card at height h (a 16:9 banner with 15/58 of its height for its corners, or a circle
     * h across), plain, dimmed (Google TV: another tile has the focus) or focused in Google TV's or Apple TV's look,
     * everything of the focus scaled down with it. Drawn once into bitmaps (a blurred shadow needs software drawing).
     */
    static final class MiniTile extends View {
        private final App app;
        private final boolean circle, focused, apple, dimmed;
        private final int w, h, pad;
        private final float k, radius, density;
        private final Paint art = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint plain = new Paint(Paint.ANTI_ALIAS_FLAG), bmp = new Paint(Paint.FILTER_BITMAP_FLAG);
        private Bitmap shadow;
        private int shadowPad;
        private final RectF r = new RectF();

        MiniTile(Context c, App app, boolean circle, int h, boolean focused, boolean apple, boolean dimmed) {
            super(c);
            this.app = app;
            this.circle = circle;
            this.h = h;
            this.focused = focused;
            this.apple = apple;
            this.dimmed = dimmed;
            density = c.getResources().getDisplayMetrics().density;
            w = circle ? h : Math.round(h * 16f / 9f);
            k = h / ((circle ? CIRCLE_DP : BANNER_H_DP) * density);   // this tile's size to the home screen's
            radius = h * TILE_RADIUS_DP / BANNER_H_DP;
            art.setShader(new BitmapShader(artBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            int out = 0;
            if (focused) {
                // the room around a focused tile: Google TV's (its scale and its ring) in both looks, so the two rows of
                // «شكل التحديد» are as tall and their tiles as far apart; Apple TV's scale and lift fit in it, its
                // (smaller: SHADOW_FIT) shadow reaches past the tile into the row (its parents don't clip)
                float ring = (RING_GAP_DP + RING_DP) * density * k * GOOGLE_SCALE;
                out = (int) Math.ceil(Math.max(w, h) * (GOOGLE_SCALE - 1) / 2f + ring) + 1;
                if (apple) {
                    shadow = shadowBitmap();
                    out = Math.max(out, (int) Math.ceil(Math.max(w, h) * (APPLE_SCALE - 1) / 2f + APPLE_LIFT_DP * density * k) + 1);
                }
            }
            pad = out;
        }

        @Override protected void onMeasure(int ws, int hs) { setMeasuredDimension(w + 2 * pad, h + 2 * pad); }

        @Override protected void onDraw(Canvas c) {
            float s = focused ? (apple ? APPLE_SCALE : GOOGLE_SCALE) : 1f;
            float lift = focused && apple ? APPLE_LIFT_DP * density * k : 0f;
            c.save();
            c.translate(getWidth() / 2f, getHeight() / 2f - lift);
            c.scale(s, s);
            c.translate(-w / 2f, -h / 2f);
            if (shadow != null) c.drawBitmap(shadow, -shadowPad, -shadowPad, bmp);
            shape(c, 0, art);
            if (focused && apple) {   // the sheen: linear-gradient(125deg, white .28 0%, .08 38%, 0 55%)
                double a = Math.toRadians(125);
                float dx = (float) Math.sin(a), dy = (float) -Math.cos(a);
                float half = (Math.abs(w * dx) + Math.abs(h * dy)) / 2f, cx = w / 2f, cy = h / 2f;
                plain.setShader(new LinearGradient(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half,
                    new int[] {0x47FFFFFF, 0x14FFFFFF, 0x00FFFFFF}, new float[] {0f, .38f, .55f}, Shader.TileMode.CLAMP));
                shape(c, 0, plain);
                plain.setShader(null);
            }
            if (dimmed) {
                plain.setStyle(Paint.Style.FILL);
                plain.setColor(Color.argb(Math.round(DIM * 255), 0, 0, 0));
                shape(c, 0, plain);
            }
            if (focused && !apple) {   // the ring: a dark gap, then the white ring, following the shape
                float gap = RING_GAP_DP * density * k, width = RING_DP * density * k;
                plain.setStyle(Paint.Style.STROKE);
                plain.setStrokeWidth(gap + 1);
                plain.setColor(RING_GAP_COLOR);
                shape(c, (gap - 1) / 2f, plain);
                plain.setStrokeWidth(width);
                plain.setColor(RING_COLOR);
                shape(c, gap + width / 2f, plain);
                plain.setStyle(Paint.Style.FILL);
            }
            c.restore();
        }

        /** the tile's shape, grown by `out` px on each side */
        private void shape(Canvas c, float out, Paint p) {
            r.set(-out, -out, w + out, h + out);
            if (circle) c.drawOval(r, p);
            else c.drawRoundRect(r, radius + out, radius + out, p);
        }

        /** the tile's picture, unclipped (the shape clips it when drawn) */
        private Bitmap artBitmap() {
            Bitmap bm = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            if (circle) {   // the launcher's CircleIcon
                c.drawColor(ICON_DISC);
                Drawable icon = app.icon;
                if (icon == null) return bm;
                if (app.fullBleed) { icon.setBounds(0, 0, w, h); icon.draw(c); }
                else if (adaptive(icon)) {
                    android.graphics.drawable.AdaptiveIconDrawable a = (android.graphics.drawable.AdaptiveIconDrawable) icon;
                    int in = w / 4;
                    for (Drawable l : new Drawable[] {a.getBackground(), a.getForeground()}) {
                        if (l == null) continue;
                        l.setBounds(-in, -in, w + in, h + in);
                        l.draw(c);
                    }
                } else {
                    int s = Math.round(w * 0.6f), l = (w - s) / 2, t = (h - s) / 2;
                    icon.setBounds(l, t, l + s, t + s);
                    icon.draw(c);
                }
            } else if (app.banner != null) {   // the banner, filling the card (centre crop)
                c.drawColor(BANNER_BG);
                Drawable b = app.banner;
                int iw = b.getIntrinsicWidth(), ih = b.getIntrinsicHeight();
                if (iw <= 0 || ih <= 0) b.setBounds(0, 0, w, h);
                else {
                    float s = Math.max(w / (float) iw, h / (float) ih);
                    int dw = Math.round(iw * s), dh = Math.round(ih * s), l = (w - dw) / 2, t = (h - dh) / 2;
                    b.setBounds(l, t, l + dw, t + dh);
                }
                b.draw(c);
            } else {   // no banner: the icon in the middle (22dp and 14dp in at the home screen's size) on its colour
                c.drawColor(darken(app.color, 0.45f));
                Drawable icon = app.icon;
                if (icon == null) return bm;
                int px = Math.round(22 * density * k), py = Math.round(14 * density * k);
                int bw = w - 2 * px, bh = h - 2 * py;
                int iw = icon.getIntrinsicWidth() > 0 ? icon.getIntrinsicWidth() : bw, ih = icon.getIntrinsicHeight() > 0 ? icon.getIntrinsicHeight() : bh;
                float s = Math.min(bw / (float) iw, bh / (float) ih);
                int dw = Math.round(iw * s), dh = Math.round(ih * s), l = (w - dw) / 2, t = (h - dh) / 2;
                icon.setBounds(l, t, l + dw, t + dh);
                icon.draw(c);
            }
            return bm;
        }

        /** Apple TV's shadow (the launcher's Shadow: the mock-up's box-shadow layers, scaled with the tile and SHADOW_FIT) */
        private Bitmap shadowBitmap() {
            float[][] layers = circle ? CIRCLE_SHADOW : BANNER_SHADOW;
            float px = DESIGN_DP * density * k * SHADOW_FIT;
            float most = 0;
            for (float[] l : layers) most = Math.max(most, (Math.abs(l[0]) + 1.5f * l[1]) * px);
            shadowPad = (int) Math.ceil(most);
            Bitmap out = Bitmap.createBitmap(w + 2 * shadowPad, h + 2 * shadowPad, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            RectF box = new RectF();
            for (float[] l : layers) {
                float dy = l[0] * px, sigma = l[1] * px / 2f, in = -l[2] * px;
                p.setColor(Color.argb(Math.round(l[3] * 255), 0, 0, 0));
                p.setMaskFilter(sigma > 0.5f ? new BlurMaskFilter((sigma - 0.5f) / 0.57735f, BlurMaskFilter.Blur.NORMAL) : null);
                box.set(shadowPad + in, shadowPad + in + dy, shadowPad + w - in, shadowPad + h - in + dy);
                if (circle) c.drawOval(box, p);
                else c.drawRoundRect(box, Math.max(0, radius - in), Math.max(0, radius - in), p);
            }
            return out;
        }
    }

    /** the launcher's fullBleed: an older icon whose four corners are opaque fills its circle */
    private static boolean fullBleed(Drawable d) {
        try {
            Bitmap bm = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, 32, 32);
            d.draw(new Canvas(bm));
            boolean full = true;
            for (int[] p : new int[][] {{1, 1}, {30, 1}, {1, 30}, {30, 30}}) full &= Color.alpha(bm.getPixel(p[0], p[1])) > 240;
            bm.recycle();
            return full;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** the launcher's sampleColor: the icon's vivid pixels averaged, saturation pushed */
    private static int sampleColor(Drawable d) {
        int color = 0xFF3A3A3A;
        try {
            Bitmap bm = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, 24, 14);
            d.draw(new Canvas(bm));
            float[] hsv = new float[3];
            double r = 0, g = 0, b = 0, w = 0;
            for (int y = 0; y < 14; y++) for (int x = 0; x < 24; x++) {
                int p = bm.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                double weight = 0.05 + hsv[1] * hsv[2];
                r += Color.red(p) * weight; g += Color.green(p) * weight; b += Color.blue(p) * weight; w += weight;
            }
            bm.recycle();
            if (w > 0) {
                Color.colorToHSV(Color.rgb((int) (r / w), (int) (g / w), (int) (b / w)), hsv);
                if (hsv[1] > 0.12f) { hsv[1] = Math.max(hsv[1], 0.75f); hsv[2] = 0.85f; }
                else { hsv[1] = 0f; hsv[2] = 0.45f; }
                color = Color.HSVToColor(hsv);
            }
        } catch (RuntimeException ignored) { }
        return color;
    }

    private static int darken(int c, float f) {
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }

    private static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }
}
