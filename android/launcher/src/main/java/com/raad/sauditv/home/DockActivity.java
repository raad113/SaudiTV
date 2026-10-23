package com.raad.sauditv.home;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * «ترتيب المفضلة»: the favourites row as a list, arranged with the remote. OK on an app picks it up, ▲▼ move
 * it, OK drops it. «إزالة» beside each one, every other app below to add. No dialogs: the launcher is laid
 * out left to right, so rows are built right-aligned by hand. Opened from the long-press menu, from the
 * screensaver's settings, or `am start -n com.raad.sauditv.home/.DockActivity`.
 */
public class DockActivity extends Activity {
    private static final int GOLD = 0xFFD6AF6B, INK = 0xFF04120C, TEXT = 0xFFF6EFE0, DIM = 0xFFC6BBA4;

    private Map<String, String> apps;
    private final List<String> row = new ArrayList<>();
    private LinearLayout list;
    private TextView count;
    private final List<View> cards = new ArrayList<>(), removes = new ArrayList<>();
    private int moving = -1;
    private int swallowUp = -1;   // the OK that drops an app must not also click it

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        apps = Dock.launchable(this);
        row.addAll(Dock.shown(this, apps));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xFF0A2016, 0xFF04120C, 0xFF020806}));
        scroll.setVerticalScrollBarEnabled(false);
        FrameLayout frame = new FrameLayout(this);
        scroll.addView(frame);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(36), 0, dp(48));
        frame.addView(col, new FrameLayout.LayoutParams(dp(600), -2, Gravity.CENTER_HORIZONTAL));

        col.addView(text("ترتيب المفضلة", 26, "Medium", TEXT), right(0));
        col.addView(text("OK على تطبيق لتحريكه بالأسهم ▲▼ ثم OK لتثبيته. من " + ar(Dock.MIN) + " إلى "
            + ar(Dock.MAX) + " تطبيقات.", 14, "Regular", DIM), right(dp(6)));
        count = text("", 13, "Medium", GOLD);
        col.addView(count, right(dp(24)));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list, new LinearLayout.LayoutParams(-1, -2));
        setContentView(scroll);
        render(0);
    }

    // ---------------------------------------------------------------- list

    private void render(int focus) {
        list.removeAllViews();
        cards.clear();
        removes.clear();
        count.setText("المفضلة · " + ar(row.size()) + " من " + ar(Dock.MAX));
        for (int i = 0; i < row.size(); i++) list.addView(favouriteRow(i), rowParams());

        List<String> others = new ArrayList<>(apps.keySet());
        others.removeAll(row);
        Collections.sort(others, (x, y) -> apps.get(x).compareToIgnoreCase(apps.get(y)));
        if (!others.isEmpty()) {
            TextView h = text("إضافة تطبيق", 13, "Medium", GOLD);
            LinearLayout.LayoutParams hp = right(0);
            hp.topMargin = dp(22);
            hp.bottomMargin = dp(8);
            list.addView(h, hp);
            for (String p : others) list.addView(card(p, "+", false, v -> add(p)), rowParams());
        }
        View reset = card(null, "الترتيب الافتراضي", false, v -> {
            Dock.reset(this);
            row.clear();
            row.addAll(Dock.shown(this, apps));
            render(0);
            toast("رجعت المفضلة للترتيب الافتراضي");
        });
        LinearLayout.LayoutParams rp = rowParams();
        rp.topMargin = dp(22);
        list.addView(reset, rp);

        for (int i = 0; i < cards.size(); i++) {   // ▲▼ stay in the column: card to card, «إزالة» to «إزالة»
            cards.get(i).setNextFocusUpId(cards.get(Math.max(0, i - 1)).getId());
            removes.get(i).setNextFocusUpId(removes.get(Math.max(0, i - 1)).getId());
            if (i < cards.size() - 1) {
                cards.get(i).setNextFocusDownId(cards.get(i + 1).getId());
                removes.get(i).setNextFocusDownId(removes.get(i + 1).getId());
            }
        }
        View f = cards.isEmpty() ? reset : cards.get(Math.max(0, Math.min(focus, cards.size() - 1)));
        f.post(f::requestFocus);
    }

    /** [إزالة] [  label  icon  n ] — right-aligned by hand. */
    private View favouriteRow(final int i) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        TextView remove = (TextView) pill("إزالة", v -> remove(i));
        r.addView(remove, new LinearLayout.LayoutParams(dp(84), -1));
        View c = card(row.get(i), ar(i + 1), i == moving, v -> {
            if (moving == i) drop(); else { moving = i; render(i); }
        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1f);
        cp.leftMargin = dp(10);
        r.addView(c, cp);
        c.setNextFocusLeftId(remove.getId());
        remove.setNextFocusRightId(c.getId());
        cards.add(c);
        removes.add(remove);
        return r;
    }

    /** A row card: badge (number or +) on the right, then the icon and name; pkg null = a text button. */
    private View card(String pkg, String badge, boolean lifted, View.OnClickListener click) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        c.setPadding(dp(16), dp(10), dp(16), dp(10));
        c.setFocusable(true);
        c.setClickable(true);
        c.setId(View.generateViewId());
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        c.setBackground(bg);

        final TextView arrows = text(lifted ? "▲▼" : "", 14, "Medium", INK);
        c.addView(arrows, new LinearLayout.LayoutParams(-2, -2));
        c.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        final TextView name = text(pkg == null ? badge : apps.get(pkg), 17, "Medium", TEXT);
        name.setSingleLine(true);
        c.addView(name, new LinearLayout.LayoutParams(-2, -2));
        if (pkg != null) {
            ImageView ic = new ImageView(this);
            try {
                Drawable d = getPackageManager().getApplicationIcon(pkg);
                ic.setImageDrawable(d);
            } catch (Exception ignored) { }
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(30), dp(30));
            ip.leftMargin = dp(12);
            ip.rightMargin = dp(12);
            c.addView(ic, ip);
            TextView n = text(badge, 15, "Medium", GOLD);
            n.setMinWidth(dp(22));
            n.setGravity(Gravity.CENTER);
            c.addView(n, new LinearLayout.LayoutParams(-2, -2));
        }
        final boolean isLifted = lifted;
        View.OnFocusChangeListener paint = (v, has) -> {
            bg.setColor(isLifted ? GOLD : has ? 0x33D6AF6B : 0x14FFFFFF);
            bg.setStroke(dp(2), isLifted || has ? GOLD : 0x1FD6AF6B);
            name.setTextColor(isLifted ? INK : TEXT);
        };
        c.setOnFocusChangeListener(paint);
        paint.onFocusChange(c, false);
        c.setOnClickListener(click);
        return c;
    }

    private View pill(String label, View.OnClickListener click) {
        final TextView p = text(label, 13, "Medium", DIM);
        p.setGravity(Gravity.CENTER);
        p.setFocusable(true);
        p.setClickable(true);
        p.setId(View.generateViewId());
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        p.setBackground(bg);
        View.OnFocusChangeListener paint = (v, has) -> {
            bg.setColor(has ? 0x33EE8B70 : 0x0DFFFFFF);
            bg.setStroke(dp(2), has ? 0xFFEE8B70 : 0x14FFFFFF);
            p.setTextColor(has ? 0xFFFFD9CF : DIM);
        };
        p.setOnFocusChangeListener(paint);
        paint.onFocusChange(p, false);
        p.setOnClickListener(click);
        return p;
    }

    // ---------------------------------------------------------------- actions

    private void remove(int i) {
        if (row.size() <= Dock.MIN) { toast("المفضلة لا تقل عن " + ar(Dock.MIN) + " تطبيقات"); return; }
        row.remove(i);
        Dock.save(this, row);
        render(Math.min(i, row.size() - 1));
    }

    private void add(String pkg) {
        if (row.size() >= Dock.MAX) { toast("المفضلة ممتلئة (" + ar(Dock.MAX) + ")"); return; }
        row.add(pkg);
        Dock.save(this, row);
        render(row.size() - 1);
        toast("أُضيف " + apps.get(pkg));
    }

    private void drop() {
        int at = moving;
        moving = -1;
        Dock.save(this, row);
        render(at);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (swallowUp == k && e.getAction() == KeyEvent.ACTION_UP) { swallowUp = -1; return true; }
        if (moving < 0) return super.dispatchKeyEvent(e);
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;   // while moving, every key is ours
        if (k == KeyEvent.KEYCODE_DPAD_UP && moving > 0) {
            Collections.swap(row, moving, moving - 1);
            moving--;
            render(moving);
        } else if (k == KeyEvent.KEYCODE_DPAD_DOWN && moving < row.size() - 1) {
            Collections.swap(row, moving, moving + 1);
            moving++;
            render(moving);
        } else if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_BACK) {
            swallowUp = k;
            drop();
        }
        return true;
    }

    // ---------------------------------------------------------------- helpers

    private TextView text(String s, float sp, String weight, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.RIGHT);
        try { t.setTypeface(Typeface.createFromAsset(getAssets(), "fonts/Thmanyah-" + weight + ".otf")); }
        catch (RuntimeException ignored) { }
        return t;
    }

    private LinearLayout.LayoutParams right(int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = bottom;
        return p;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(8);
        return p;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static String ar(int n) {
        StringBuilder b = new StringBuilder();
        for (char ch : String.valueOf(n).toCharArray()) b.append((char) ('٠' + (ch - '0')));
        return b.toString();
    }
}
