package com.raad.sauditv.screensaver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.util.Calendar;
import java.util.GregorianCalendar;

/**
 * Does one athan event (AthanReceiver, saver.sh athan-test, the settings' «تجربة الأذان»), as a foreground service so it
 * lasts the athan's minutes whatever is in front:
 *
 * «الأذان كاملاً» (full): «إذاعة القرآن الكريم» is held first (RadioService.hold, the athan's hook); if another app was
 * playing (YouTube, a TV app: AudioManager.isMusicActive) a media pause key goes to it; then the audio focus (GAIN), the
 * screen woken from any screensaver (only a TV already on: a screensaver is «on»), the full-screen AthanActivity (over
 * any app: SYSTEM_ALERT_WINDOW exempts it from Android 10+'s limit on starting activities from the background), and the
 * athan at its own volume. OK or Back on that screen, the remote's pause / stop (our media session) or the TV going off
 * stop it. At its end, or stopped: the optional dua (text) on that screen, then the focus given back, a play key to the
 * app we paused (only if we paused one), and the radio let go.
 *
 * «تنبيه قصير» (short): a banner over whatever is in front and the voice's opening «الله أكبر الله أكبر» for
 * AthanPlan.SHORT_PLAY_MS, fading out; the focus asked for a moment and to duck (MAY_DUCK), so nothing is paused.
 *
 * Reminder: the banner only. Without SYSTEM_ALERT_WINDOW the banner is a toast, and the full athan plays with no screen:
 * a toast «اضغط إيقاف مؤقت لإيقاف الأذان» then, again every NO_SCREEN_TOAST_MS while it plays; the remote's pause,
 * play/pause, play or stop reach our media session (the app playing sound last gets them) and stop it; starting any
 * other sound (OK on the paused video) takes the audio focus and stops it too. Back cannot: it goes to the app in front.
 */
public class AthanService extends Service {
    static final String TAG = AthanScheduler.TAG;
    private static final String CHANNEL = "athan";
    private static final int NOTIFY_ID = 7130;
    /** the reminder's banner; the short alert's stays BANNER_AFTER_MS after its clip */
    static final long DUA_MS = 25000, BANNER_MS = 12000, BANNER_AFTER_MS = 3000, MAX_MS = 9 * 60 * 1000L;
    static final String DUA = "اللهم رب هذه الدعوة التامة، والصلاة القائمة، آتِ محمدًا الوسيلة والفضيلة، وابعثه مقامًا محمودًا الذي وعدته";

    /** what the athan screen (AthanActivity) shows, and whether the athan is over */
    static final class Show {
        String name = "", label = "", time = "", city = "";
        int prayer;
        boolean dua, ended;
    }

    interface Ui { void changed(); }

    private static volatile AthanService running;
    static volatile Show show;
    private static volatile Ui ui;

    static void setUi(Ui u) { ui = u; }
    static void clearUi(Ui u) { if (ui == u) ui = null; }
    static boolean playing() { AthanService s = running; return s != null && s.event != null; }

    /** starts the service for an event; false when Android refused (a background start not allowed) */
    static boolean start(Context c, AthanPlan.Event e, boolean test) {
        Intent i = AthanScheduler.put(new Intent(c, AthanService.class), e).putExtra("test", test);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
            return true;
        } catch (RuntimeException x) {   // ForegroundServiceStartNotAllowedException, SecurityException
            Log.w(TAG, "start: " + x);
            return false;
        }
    }

    /** OK or Back on the athan screen, saver.sh: the athan stops (then the dua, if on, is skipped too) */
    static void stop() {
        final AthanService s = running;
        if (s != null) s.main.post(() -> s.end("stopped"));
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private boolean focus, pausedOther, held;
    private MediaPlayer player;
    private MediaSession session;
    private PowerManager.WakeLock wake;
    private View banner;
    private AthanPlan.Event event;
    private AthanPrefs prefs;
    private long playStarted;
    private boolean triedDefault;

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> main.post(() -> {
        // another app took the sound for good (the user started something): the athan gives way
        if (change == AudioManager.AUDIOFOCUS_LOSS && event != null) { focus = false; end("focus lost"); }
    });

    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { if (event != null) end("screen off"); }
    };

    private final Runnable fade = new Runnable() {
        @Override public void run() {
            if (player == null) return;
            long ms = SystemClock.elapsedRealtime() - playStarted;
            float g = AthanPlan.shortFade(ms);
            volume(g);
            if (g <= 0f) { stopPlayer(); abandonFocus(); bannerFor(BANNER_AFTER_MS); main.post(done); return; }
            main.postDelayed(this, 100);
        }
    };
    private final Runnable maxTime = () -> end("too long");
    private final Runnable bannerOff = this::removeBanner;
    /** a short alert or a reminder is over once its sound and its banner are */
    private final Runnable done = () -> {
        if (event != null && !AthanPlan.KIND_FULL.equals(event.kind) && banner == null && player == null) end("done");
    };
    private final Runnable quitLater = () -> { if (event == null) quit(); };

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));   // a protected broadcast: no export flag
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        foreground();
        AthanPlan.Event e = AthanScheduler.event(i);
        if (e == null) { if (event == null) quit(); return START_NOT_STICKY; }
        if (event != null) end(null);   // one at a time: a new one (a test) replaces the one playing
        begin(e);
        return START_NOT_STICKY;
    }

    private void foreground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "الأذان", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else b = new Notification.Builder(this);
        Notification n = b.setSmallIcon(R.drawable.ic_volume).setContentTitle("الأذان").setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTIFY_ID, n);
    }

    // ---------------------------------------------------------------- one event

    private void begin(AthanPlan.Event e) {
        event = e;
        prefs = new AthanPrefs(this);
        triedDefault = false;
        Show s = new Show();
        s.prayer = e.prayer;
        GregorianCalendar cal = new GregorianCalendar(java.util.TimeZone.getTimeZone(prefs.prefs().placeTz()));
        cal.setTimeInMillis(e.athanAt);
        boolean friday = cal.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY;
        s.name = AthanPlan.name(e.prayer, friday);
        s.time = Prefs.digits(this, AthanPlan.time12(cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)));
        s.city = prefs.cityName();
        s.label = "حان الآن موعد أذان";
        show = s;
        main.removeCallbacks(maxTime);
        main.postDelayed(maxTime, MAX_MS);
        Log.i(TAG, "begin " + e);
        if (AthanPlan.KIND_REMINDER.equals(e.kind)) {
            long left = Math.max(1, Math.round((e.athanAt - System.currentTimeMillis()) / 60000.0));
            String in = left == 1 ? "دقيقة" : left == 2 ? "دقيقتين"
                : Prefs.digits(this, left) + " " + (left <= 10 ? "دقائق" : "دقيقة");
            showBanner("تذكير: بعد " + in + " أذان " + s.name + " · " + s.time, BANNER_MS);
            return;
        }
        if (AthanPlan.KIND_SHORT.equals(e.kind)) {
            requestFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            // one line, as the screen's: «حان الآن موعد أذان العصر · 3:40 م»; it stays the clip's length + 3 s (bannerFor)
            showBanner(s.label + " " + s.name + " · " + s.time, AthanPlan.SHORT_PLAY_MS + BANNER_AFTER_MS + 2000);
            play(e.prayer == AthanPlan.FAJR);
            return;
        }
        // the full athan
        boolean radio = "playing".equals(RadioService.state()) || "loading".equals(RadioService.state());
        pausedOther = !radio && audio != null && audio.isMusicActive();   // the radio playing: no other app is
        RadioService.hold(this, true);
        held = true;
        if (pausedOther) mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE);
        wakeScreen();
        openScreen();
        // the radio lets go of the focus first (its stop is posted by hold): taken from it, it would wait for our screens
        // to come back in front instead of playing again after the athan
        final AthanPlan.Event mine = e;
        main.postDelayed(() -> {
            if (event != mine) return;
            requestFocus(AudioManager.AUDIOFOCUS_GAIN);
            startSession();
            play(mine.prayer == AthanPlan.FAJR);
        }, 350);
    }

    /** the voice of this prayer (Fajr its own), «صوت من عندي» if picked and there, else the bundled default */
    private void play(boolean fajr) {
        stopPlayer();
        String voice = triedDefault ? AthanVoices.of(null, fajr) : prefs.voice(fajr);
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            File custom = AthanPrefs.customFile(this, fajr);
            if (AthanVoices.CUSTOM.equals(voice) && custom.isFile() && custom.length() > 0) {
                try (FileInputStream in = new FileInputStream(custom)) { mp.setDataSource(in.getFD()); }
            } else {
                AthanVoices.Voice v = AthanVoices.find(AthanVoices.CUSTOM.equals(voice) ? AthanVoices.of(null, fajr) : voice);
                try (AssetFileDescriptor fd = getAssets().openFd(v.file)) {
                    mp.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
                }
            }
            mp.setOnPreparedListener(p -> {
                if (p != player) return;
                volume(1f);
                p.start();
                playStarted = SystemClock.elapsedRealtime();
                if (event != null && AthanPlan.KIND_SHORT.equals(event.kind)) main.postDelayed(fade, 100);
            });
            mp.setOnCompletionListener(p -> { if (p == player) finished(); });
            mp.setOnErrorListener((p, what, extra) -> { if (p == player) failed("error " + what + "/" + extra); return true; });
            mp.prepareAsync();
        } catch (Exception x) {
            failed(x.getClass().getSimpleName());
        }
    }

    /** this voice did not play: the bundled default once, else the end */
    private void failed(String why) {
        Log.w(TAG, "voice: " + why);
        stopPlayer();
        if (!triedDefault && event != null) { triedDefault = true; play(event.prayer == AthanPlan.FAJR); }
        else finished();
    }

    private void volume(float g) {
        float v = RadioStations.gain(prefs == null ? AthanPlan.VOLUME_DEFAULT : prefs.volume()) * g;
        try { if (player != null) player.setVolume(v, v); } catch (IllegalStateException ignored) { }
    }

    /** the voice ended: the full athan's dua (if on), else the end; the short alert's banner stays its time */
    private void finished() {
        stopPlayer();
        if (event == null) return;
        if (AthanPlan.KIND_FULL.equals(event.kind)) {
            if (prefs.dua() && show != null && !show.dua) {
                show.dua = true;
                notifyUi();
                releaseSession();
                abandonFocus();
                final AthanPlan.Event mine = event;
                main.postDelayed(() -> { if (event == mine) end("done"); }, DUA_MS);
            } else end("done");
        } else {
            abandonFocus();
            bannerFor(BANNER_AFTER_MS);   // a clip shorter than SHORT_PLAY_MS: 3 s after it, as after a whole one
            main.post(done);
        }
    }

    /**
     * The end of an event (why: logged; null = replaced by a new one): the sound stops, the focus goes back, the app we
     * paused gets a play key, the radio is let go, the screen and the banner close; then the service.
     */
    private void end(String why) {
        if (event == null) return;
        Log.i(TAG, "end " + event + (why == null ? " (replaced)" : ": " + why));
        main.removeCallbacks(fade);
        main.removeCallbacks(maxTime);
        main.removeCallbacks(stopHint);
        stopPlayer();
        releaseSession();
        abandonFocus();
        if (pausedOther) {   // after our focus and session are gone: the key reaches the app we paused
            pausedOther = false;
            main.postDelayed(() -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY), 400);
        }
        if (held) { held = false; RadioService.hold(this, false); }
        releaseWake();
        removeBanner();
        Show s = show;
        if (s != null) s.ended = true;
        event = null;
        notifyUi();
        main.removeCallbacks(quitLater);
        if (why != null) main.postDelayed(quitLater, 800);   // after the play key (400 ms)
    }

    private void quit() {
        event = null;
        show = null;
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        stopSelf();
    }

    private void notifyUi() { Ui u = ui; if (u != null) u.changed(); }

    private void stopPlayer() {
        main.removeCallbacks(fade);
        MediaPlayer p = player;
        player = null;
        if (p == null) return;
        try { p.stop(); } catch (IllegalStateException ignored) { }
        p.release();
    }

    @Override
    public void onDestroy() {
        if (event != null) end(null);
        main.removeCallbacksAndMessages(null);
        try { unregisterReceiver(screenOff); } catch (RuntimeException ignored) { }
        removeBanner();
        running = null;
        super.onDestroy();
    }

    // ---------------------------------------------------------------- the other app, the focus, the remote

    private void mediaKey(int code) {
        if (audio == null) return;
        Log.i(TAG, "media key " + KeyEvent.keyCodeToString(code));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    private void requestFocus(int gain) {
        if (audio == null || focus) return;
        AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        int r;
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest = new AudioFocusRequest.Builder(gain).setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusListener, main).build();
            r = audio.requestAudioFocus(focusRequest);
        } else r = audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, gain);
        focus = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (!focus) Log.w(TAG, "no audio focus: the athan plays anyway");
    }

    private void abandonFocus() {
        if (audio == null || !focus) return;
        focus = false;
        if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) audio.abandonAudioFocusRequest(focusRequest);
        else audio.abandonAudioFocus(focusListener);
    }

    /** the remote's keys that stop the athan through our media session */
    static final java.util.Set<Integer> STOP_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_HEADSETHOOK));

    /** the remote's pause / stop / play while the full athan plays (our session is the active one then) stop it */
    private void startSession() {
        releaseSession();
        session = new MediaSession(this, "SaudiTV-Athan");
        session.setCallback(new MediaSession.Callback() {
            /** any of the remote's play / pause / stop keys, whatever the session's state would make of it */
            @Override public boolean onMediaButtonEvent(Intent i) {
                @SuppressWarnings("deprecation")
                KeyEvent k = i == null ? null : (KeyEvent) i.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (k != null && STOP_KEYS.contains(k.getKeyCode())) {
                    if (k.getAction() == KeyEvent.ACTION_DOWN) end("remote " + KeyEvent.keyCodeToString(k.getKeyCode()));
                    return true;
                }
                return super.onMediaButtonEvent(i);
            }
            @Override public void onPause() { end("remote"); }
            @Override public void onStop() { end("remote"); }
            @Override public void onPlay() { end("remote"); }
        });
        session.setPlaybackState(new PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_STOP | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_PLAY)
            .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build());
        session.setActive(true);
    }

    private void releaseSession() {
        MediaSession s = session;
        session = null;
        if (s == null) return;
        s.setActive(false);
        s.release();
    }

    // ---------------------------------------------------------------- the screen

    /** a TV that is on (a screensaver counts) is brought out of it; one in standby is never woken (AthanPlan.decide) */
    private void wakeScreen() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null || !pm.isInteractive()) return;
        ScreenDream.endForAthan();
        try {
            @SuppressWarnings("deprecation")
            PowerManager.WakeLock w = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP
                | PowerManager.ON_AFTER_RELEASE, "SaudiTV:athan");
            w.acquire(MAX_MS);
            wake = w;
        } catch (RuntimeException x) { Log.w(TAG, "wake: " + x); }
    }

    private void releaseWake() {
        PowerManager.WakeLock w = wake;
        wake = null;
        if (w != null && w.isHeld()) try { w.release(); } catch (RuntimeException ignored) { }
    }

    static boolean overlayAllowed(Context c) { return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c); }

    /** without its screen: how to stop it, now and again while it plays */
    static final String STOP_HINT = "اضغط إيقاف مؤقت لإيقاف الأذان";
    static final long NO_SCREEN_TOAST_MS = 30000;
    private final Runnable stopHint = new Runnable() {
        @Override public void run() {
            Show s = show;
            if (event == null || !AthanPlan.KIND_FULL.equals(event.kind) || s == null || s.dua) return;
            Toast.makeText(AthanService.this, "أذان " + s.name + " · " + s.time + "\n" + STOP_HINT, Toast.LENGTH_LONG).show();
            main.postDelayed(this, NO_SCREEN_TOAST_MS);
        }
    };

    private void openScreen() {
        if (!overlayAllowed(this)) {
            Log.w(TAG, "no SYSTEM_ALERT_WINDOW: the athan plays without its screen");
            main.removeCallbacks(stopHint);
            main.post(stopHint);
        }
        try {
            startActivity(new Intent(this, AthanActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_USER_ACTION));
        } catch (RuntimeException x) { Log.w(TAG, "screen: " + x); }
    }

    /**
     * The banner over whatever is in front (SYSTEM_ALERT_WINDOW), else a toast; it goes after `ms`. One line, at least
     * 22 sp, opaque: the setup's dark panel colours with the gold border, top centre below the overscan margin (5 % of
     * the screen's height). Not FLAG_NOT_TOUCHABLE: Android 12+ caps an app overlay that lets touches through at 80 %
     * opacity (the home screen showed through); FLAG_NOT_FOCUSABLE keeps the remote's keys with the app in front, and a
     * mouse click on it closes it.
     */
    private void showBanner(String line, long ms) {
        removeBanner();
        if (!overlayAllowed(this)) {
            Toast.makeText(this, line, Toast.LENGTH_LONG).show();
            main.postDelayed(done, ms);
            return;
        }
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return;
        TextView box = new TextView(this);
        box.setText(line);
        box.setTextColor(AthanActivity.TEXT);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        box.setTypeface(AthanActivity.font(this, "ThmanyahDisplay-Bold"));
        box.setSingleLine(true);
        box.setTextDirection(View.TEXT_DIRECTION_RTL);
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        box.setGravity(Gravity.CENTER);
        box.setIncludeFontPadding(false);
        box.setPadding(dp(32), dp(16), dp(32), dp(16));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {PANEL_TOP, PANEL_BOTTOM});
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(2), AthanActivity.GOLD);
        box.setBackground(bg);
        box.setOnClickListener(v -> removeBanner());
        @SuppressWarnings("deprecation")
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = Math.round(getResources().getDisplayMetrics().heightPixels * SAFE_TOP);
        lp.alpha = 1f;
        try {
            wm.addView(box, lp);
            banner = box;
        } catch (RuntimeException x) {
            Log.w(TAG, "banner: " + x);
            Toast.makeText(this, line, Toast.LENGTH_LONG).show();
        }
        main.removeCallbacks(bannerOff);
        main.postDelayed(bannerOff, ms);
    }

    /** the setup's and the athan screen's dark panel (SetupActivity: 0xFF0A2016 to 0xFF04120C), opaque */
    static final int PANEL_TOP = 0xFF0A2016, PANEL_BOTTOM = 0xFF04120C;
    /** the overscan margin a TV may cut at the top: the banner starts below it */
    static final float SAFE_TOP = 0.05f;

    /** the banner (if up) goes `ms` from now */
    private void bannerFor(long ms) {
        if (banner == null) return;
        main.removeCallbacks(bannerOff);
        main.postDelayed(bannerOff, ms);
    }

    private void removeBanner() {
        main.removeCallbacks(bannerOff);
        View b = banner;
        banner = null;
        if (b != null) {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            try { if (wm != null) wm.removeView(b); } catch (RuntimeException ignored) { }
        }
        main.removeCallbacks(done);
        main.postDelayed(done, 200);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
