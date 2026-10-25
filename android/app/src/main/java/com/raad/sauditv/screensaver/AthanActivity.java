package com.raad.sauditv.screensaver;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;

/**
 * The full athan's screen (AthanService opens it over whatever is in front): the prayer, its time and the city in large
 * Arabic text over one of the bundled photos, dimmed under the settings' dark green to black gradient, gold accents,
 * the Thmanyah fonts of the settings and the setup. OK or Back (or a mouse click, the remote's pause / stop) stop the
 * athan; Home too. After the athan, the optional dua shows here, then the screen closes by itself.
 */
public class AthanActivity extends Activity implements AthanService.Ui {
    static final int GOLD = 0xFFD6AF6B, TEXT = 0xFFF6EFE0, DIM = 0xFFC6BBA4;
    /** a photo for each prayer (assets/backgrounds, the screensaver's own): dawn, day, afternoon, sunset, dusk */
    private static final String[] PHOTOS = {"9hWJs7iblh8.jpg", "t3dlLOhdEzs.jpg", "XDtzu1D2P8c.jpg", "9hWJs7iblh8.jpg", "f5ZB0LHwi0s.jpg"};

    private ImageView photo;
    private LinearLayout column;
    private TextView label, name, time, city, dua, hint;
    private int photoFor = -1;
    private boolean stopping;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        android.content.res.Configuration c = new android.content.res.Configuration(base.getResources().getConfiguration());
        c.setLocale(new java.util.Locale("ar"));
        c.setLayoutDirection(new java.util.Locale("ar"));
        super.attachBaseContext(base.createConfigurationContext(c));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF020806);
        photo = new ImageView(this);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        photo.setAlpha(0.42f);
        root.addView(photo, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {0xB30A2016, 0xD904120C, 0xF2020806}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        label = text(26, DIM, "Thmanyah-Medium");
        name = text(110, TEXT, "ThmanyahDisplay-Bold");
        View line = new View(this);
        line.setBackgroundColor(GOLD);
        time = text(46, GOLD, "Thmanyah-Medium");
        city = text(28, DIM, "Thmanyah-Regular");
        dua = text(28, TEXT, "Thmanyah-Regular");
        dua.setLineSpacing(0, 1.35f);
        dua.setVisibility(View.GONE);
        column.addView(label);
        column.addView(name);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(96), dp(3));
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(18);
        column.addView(line, lp);
        column.addView(time);
        column.addView(city);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Math.round(getResources().getDisplayMetrics().widthPixels * 0.7f), -2);
        dl.topMargin = dp(30);
        column.addView(dua, dl);
        root.addView(column, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        hint = text(16, 0x99C6BBA4, "Thmanyah-Regular");
        hint.setText("OK أو رجوع لإيقاف الأذان");
        hint.setTextDirection(View.TEXT_DIRECTION_RTL);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hp.bottomMargin = dp(36);
        root.addView(hint, hp);

        setContentView(root);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        column.setAlpha(0f);
        column.animate().alpha(1f).setDuration(700).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        AthanService.setUi(this);
        changed();
    }

    @Override
    protected void onPause() {
        AthanService.clearUi(this);
        super.onPause();
    }

    /** the service's state changed (the dua, the end): shown here, on the main thread */
    @Override
    public void changed() {
        runOnUiThread(() -> {
            AthanService.Show s = AthanService.show;
            if (s == null || s.ended) { finish(); return; }
            label.setText(s.dua ? "دعاء بعد الأذان" : s.label);
            name.setText(s.name);
            time.setText(s.time);
            city.setText(s.city);
            if (s.dua && dua.getVisibility() != View.VISIBLE) {
                dua.setText(AthanService.DUA);
                dua.setAlpha(0f);
                dua.setVisibility(View.VISIBLE);
                dua.animate().alpha(1f).setDuration(600).start();
                hint.setText("OK أو رجوع للإغلاق");
            }
            if (photoFor != s.prayer) loadPhoto(s.prayer);
        });
    }

    /** the prayer's photo, decoded at half size off the main thread (it is dimmed: no detail is lost) */
    private void loadPhoto(final int prayer) {
        photoFor = prayer;
        final String file = "backgrounds/" + PHOTOS[Math.max(0, Math.min(PHOTOS.length - 1, prayer))];
        new Thread(() -> {
            Bitmap bm = null;
            try (InputStream in = getAssets().open(file)) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 2;
                bm = BitmapFactory.decodeStream(in, null, o);
            } catch (Exception ignored) { }
            final Bitmap got = bm;
            if (got != null) runOnUiThread(() -> { if (!isFinishing()) photo.setImageBitmap(got); });
        }, "athan-photo").start();
    }

    private void stopAthan() {
        if (stopping) return;
        stopping = true;
        AthanService.stop();
        finish();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN: case KeyEvent.KEYCODE_VOLUME_MUTE:
                return super.dispatchKeyEvent(e);   // the TV's own volume
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: case KeyEvent.KEYCODE_ESCAPE: case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_PAUSE: case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (e.getAction() == KeyEvent.ACTION_UP) stopAthan();
                return true;
            default:
                return true;   // the arrows and the rest: nothing to move here
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP) stopAthan();   // a mouse's click, a touch
        return true;
    }

    /** Home (the user leaving): the athan stops with its screen */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        stopAthan();
    }

    private TextView text(int sp, int color, String font) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(font(this, font));
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private static final java.util.Map<String, Typeface> FONTS = new java.util.HashMap<>();

    /** Thmanyah (the launcher's assets, in this APK too), as SettingsActivity and SetupActivity load it */
    static synchronized Typeface font(android.content.Context c, String name) {
        Typeface tf = FONTS.get(name);
        if (tf == null) {
            try { tf = Typeface.createFromAsset(c.getAssets(), "fonts/" + name + ".otf"); }
            catch (RuntimeException e) { tf = Typeface.DEFAULT; }
            FONTS.put(name, tf);
        }
        return tf;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
