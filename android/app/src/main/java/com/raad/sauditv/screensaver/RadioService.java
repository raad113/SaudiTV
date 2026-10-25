package com.raad.sauditv.screensaver;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * «إذاعة القرآن الكريم»: plays the picked station (Radio.current) natively, outside the page, so a page reload never
 * cuts it: an MP3 or AAC stream over HTTP or ICY with StreamPlayer (Android's MediaCodec and an AudioTrack, no library),
 * an HLS one (.m3u8) with MediaPlayer.
 *
 * Who is in front binds to this service, each with its own address (bindIntent): "home" (the launcher's home screen
 * and its full-screen card, com.raad.sauditv.home RadioLink, unbinding 3 s after it goes, so the screensaver can take
 * over without a gap), "saver" (ScreenDream, and the screensaver's preview, MainActivity) and "preview" (the settings'
 * «معاينة الإذاعة»: plays even while the radio is off). Bound by none, the service ends and the radio with it.
 *
 * It plays while: someone is in front, the screen is on (ACTION_SCREEN_OFF stops it), the radio is on (or a preview),
 * «متى تعمل الإذاعة» lets it (RadioWhen: both, only the screensaver, only the home screen; userPlay, the home screen's
 * «تشغيل القرآن», plays it there too until it is turned off, our screens all go, the focus is lost or the screen goes
 * off), no athan holds it (below), and it has the audio focus. Another app taking the focus (YouTube, Netflix, any player)
 * stops it: it plays again when our home screen or screensaver comes back in front (a new bind). A short loss (the
 * Assistant) pauses it, a ducking one lowers it; the remote's pause / play (our media session) pause and resume it.
 *
 * Errors: the station's next address after 1 s; every address having failed, ERROR («تعذّر التشغيل» on the chip and in
 * the settings) and a new try after 5, 15, 30, 60, 120, then every 300 s. A stream that never starts within 25 s, or
 * plays nothing for 40 s, counts as an error.
 *
 * The athan's hook (none is built yet): RadioService.hold(context, true) before the athan and hold(context, false)
 * after it, in this app; from the home app (same key) the broadcasts ACTION_PAUSE_FOR_ATHAN / ACTION_RESUME_AFTER_ATHAN
 * to RadioReceiver; from ADB the same actions to SaverReceiver (optimize/saver.sh radio-hold on|off). A hold ends by
 * itself after HOLD_MAX_MS, so a lost «resume» never leaves the radio silent.
 *
 * State for the chip («يُتلى الآن: …», the page's tickRadio, RadioBridge) and the settings: state(), stationName(),
 * error() (also ConfigProvider's /radio, for the launcher).
 */
public class RadioService extends Service {
    static final String TAG = "SaudiTV-Radio";
    static final String ACTION_BIND = "com.raad.sauditv.screensaver.RADIO";
    static final String ACTION_PAUSE_FOR_ATHAN = "com.raad.sauditv.screensaver.PAUSE_FOR_ATHAN";
    static final String ACTION_RESUME_AFTER_ATHAN = "com.raad.sauditv.screensaver.RESUME_AFTER_ATHAN";
    static final String HOME = "home", SAVER = "saver", PREVIEW = "preview";
    static final long HOLD_MAX_MS = 15 * 60 * 1000L;

    static final int IDLE = 0, LOADING = 1, PLAYING = 2, ERROR = 3, PAUSED = 4;
    private static final String[] STATES = {"idle", "loading", "playing", "error", "paused"};
    private static final long[] BACKOFF_MS = {5000, 15000, 30000, 60000, 120000, 300000};
    private static final long PREPARE_MS = 25000, STALL_CHECK_MS = 20000, SHORT_PAUSE_MS = 15000;

    private static volatile int state = IDLE;
    private static volatile String name = "", error = "", holders = "";
    private static volatile RadioService running;
    /** «تشغيل القرآن» on the home screen (ConfigProvider, the remote's play): plays there whatever RadioWhen says */
    private static volatile boolean override;

    /** what the chip and the settings show: "idle", "loading", "playing", "error" or "paused" */
    static String state() { return STATES[state]; }
    static String stationName() { return name; }
    static String error() { return error; }
    /** who holds it now ("home,saver"), for optimize/saver.sh show */
    static String holders() { return running == null ? "" : holders; }

    /** the binding of one of our screens in front (who: HOME, SAVER or PREVIEW) */
    static Intent bindIntent(Context c, String who) {
        return new Intent(ACTION_BIND).setClassName(c.getPackageName(), RadioService.class.getName())
            .setData(Uri.parse("sauditv-radio://" + who));
    }

    /** the athan's hook: on, the radio stops (up to HOLD_MAX_MS); off, it plays again if it should */
    static void hold(Context c, boolean on) {
        new Prefs(c).raw().edit().putLong("radioHoldUntil", on ? System.currentTimeMillis() + HOLD_MAX_MS : 0).apply();
        Log.i(TAG, on ? "held (athan)" : "hold released");
        final RadioService s = running;
        if (s != null) s.main.post(s::evaluate);
    }

    static boolean held(Context c) { return new Prefs(c).raw().getLong("radioHoldUntil", 0) > System.currentTimeMillis(); }

    /** the home screen's «تشغيل القرآن» (with the radio turned on): plays now, a pause by the remote resumed */
    static void userPlay() {
        override = true;
        final RadioService s = running;
        if (s != null) s.main.post(s::resumeFromUser);
    }

    /** the home screen's «إيقاف القرآن» (with the radio turned off) */
    static void userStop() { override = false; }

    /** a playing stream: StreamPlayer (MP3, AAC) or Hls (MediaPlayer) */
    interface Out {
        void pause();
        void resume();
        void volume(float gain);
        /** grows while sound comes out (the stall check) */
        long progress();
        /** off the main thread where it can block */
        void release();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> who = new HashSet<>();
    private Prefs prefs;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;   // API 26+
    private MediaSession session;
    private Out out;
    private RadioStations.Station station;
    private int mirror, failures;
    private boolean focus, focusLost, transientLoss, ducked, userPaused, interactive = true, ready, retryPending;
    private long pausedAt, lastProgress = -1;
    private int stalls;

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> main.post(() -> onFocus(change));

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            interactive = !Intent.ACTION_SCREEN_OFF.equals(i.getAction());
            Log.i(TAG, "screen " + (interactive ? "on" : "off"));
            evaluate();
        }
    };

    private final Runnable restart = this::restart;
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener = (sp, key) -> {
        if (key == null) return;
        switch (key) {
            case "radioOn": userPaused = false; if (!prefs.radioOn()) override = false; evaluate(); break;
            case "radioWhen": evaluate(); break;
            // a station is three keys in one save: one restart after them
            case "radioStation": case "radioUrl": case "radioName":
                userPaused = false;
                main.removeCallbacks(restart);
                main.postDelayed(restart, 150);
                break;
            case "radioVolume": applyVolume(); break;
            case "radioHoldUntil": evaluate(); break;
            default:
        }
    };

    private final Runnable retry = () -> { retryPending = false; evaluateRetry(); };
    private final Runnable holdEnd = this::evaluate;
    private final Runnable prepareTimeout = () -> { if (out != null && !ready) fail("no sound in " + PREPARE_MS / 1000 + " s"); };
    private final Runnable stallCheck = new Runnable() {
        @Override public void run() {
            if (out == null || !ready) return;
            long p = out.progress();
            if (state == PLAYING && p == lastProgress) {
                if (++stalls >= 2) { fail("stalled"); return; }
            } else stalls = 0;
            lastProgress = p;
            main.postDelayed(this, STALL_CHECK_MS);
        }
    };

    private final StreamPlayer.Events events = new StreamPlayer.Events() {
        @Override public void ready(Out o) { if (o == out) onReady(); }
        @Override public void failed(Out o, String why) { if (o == out) fail(why); }
        @Override public void hls(Out o, String url) { if (o == out) { out = null; play(new Hls(url)); } }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        prefs = new Prefs(this);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        interactive = pm == null || pm.isInteractive();
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screen, f);   // system broadcasts only (protected): no export flag needed
        prefs.raw().registerOnSharedPreferenceChangeListener(prefsListener);
        session = new MediaSession(this, "SaudiTV-Radio");
        session.setCallback(new MediaSession.Callback() {
            // the remote's play / pause while our session is the active one
            @Override public void onPlay() { override = true; resumeFromUser(); }
            @Override public void onPause() { pauseByUser(); }
            @Override public void onStop() { pauseByUser(); }
        });
        Log.i(TAG, "service up");
    }

    @Override
    public IBinder onBind(Intent i) {
        arrive(i);
        return new Binder();
    }

    @Override
    public void onRebind(Intent i) { arrive(i); }

    @Override
    public boolean onUnbind(Intent i) {
        who.remove(whoOf(i));
        holders = Prefs.join(new java.util.ArrayList<>(who), ",");
        Log.i(TAG, "left: " + whoOf(i) + " (in front: " + holders + ")");
        evaluate();
        return true;   // onRebind next time
    }

    /** one of our screens came to the front: a focus lost to another app may be taken back now */
    private void arrive(Intent i) {
        who.add(whoOf(i));
        holders = Prefs.join(new java.util.ArrayList<>(who), ",");
        focusLost = false;
        Log.i(TAG, "in front: " + holders);
        evaluate();
    }

    private static String whoOf(Intent i) {
        Uri d = i == null ? null : i.getData();
        String h = d == null ? null : d.getHost();
        return h == null ? HOME : h;
    }

    @Override
    public void onDestroy() {
        stop(IDLE, "");
        main.removeCallbacksAndMessages(null);
        try { unregisterReceiver(screen); } catch (RuntimeException ignored) { }
        prefs.raw().unregisterOnSharedPreferenceChangeListener(prefsListener);
        session.release();
        running = null;
        holders = "";
        Log.i(TAG, "service down");
        super.onDestroy();
    }

    // ---------------------------------------------------------------- what should happen now

    private boolean wanted() {
        // none of our screens, the screen off, another app's sound: the home screen's «تشغيل القرآن» is over too
        if (who.isEmpty() || !interactive || focusLost) { override = false; return false; }
        if (!RadioWhen.plays(prefs.radioWhen(), prefs.radioOn(), who.contains(HOME), who.contains(SAVER), who.contains(PREVIEW),
            override)) return false;
        long until = prefs.raw().getLong("radioHoldUntil", 0), now = System.currentTimeMillis();
        if (until > now) {
            main.removeCallbacks(holdEnd);
            main.postDelayed(holdEnd, Math.min(until - now + 500, HOLD_MAX_MS + 1000));
            return false;
        }
        return true;
    }

    private void evaluate() {
        if (!wanted()) {
            if (out != null || state != IDLE || retryPending) stop(IDLE, "");
            userPaused = false;   // gone from the front (or off): the next time it plays again
            return;
        }
        if (userPaused || transientLoss) return;   // paused: the remote's play, or the focus back, resumes it
        if (out == null && !retryPending) start();
    }

    /** a retry's turn: only if it should still play */
    private void evaluateRetry() {
        if (!wanted()) stop(IDLE, "");
        else if (out == null && !userPaused && !transientLoss) open();
    }

    /** the remote's pause: the stream waits (the session stays active, so its play resumes it) */
    private void pauseByUser() {
        if (state == IDLE) return;
        userPaused = true;
        pausedAt = SystemClock.elapsedRealtime();
        main.removeCallbacks(retry);
        retryPending = false;
        if (out != null && ready) out.pause(); else releaseOut();
        main.removeCallbacks(stallCheck);
        setState(PAUSED);
    }

    private void resumeFromUser() {
        if (!userPaused) { evaluate(); return; }
        userPaused = false;
        if (!wanted()) { evaluate(); return; }
        resumeOut();
    }

    /** after a pause: the same stream when the pause was short, else a fresh start (it is live) */
    private void resumeOut() {
        if (out != null && ready && SystemClock.elapsedRealtime() - pausedAt < SHORT_PAUSE_MS) {
            out.resume();
            setState(PLAYING);
            lastProgress = -1;
            main.removeCallbacks(stallCheck);
            main.postDelayed(stallCheck, STALL_CHECK_MS);
        } else if (station == null) start();
        else if (!requestFocus()) { focusLost = true; stop(IDLE, ""); }
        else open();
    }

    private void restart() {
        stop(IDLE, "");
        failures = 0;
        mirror = 0;
        evaluate();
    }

    private void start() {
        station = Radio.current(this);
        name = station.name;
        error = "";
        if (station.urls.length == 0) { error = "no address"; setState(ERROR); return; }
        if (!requestFocus()) { Log.i(TAG, "no audio focus: not playing"); focusLost = true; setState(IDLE); return; }
        mirror = 0;
        failures = 0;
        open();
    }

    private void open() {
        releaseOut();
        final String url = station.urls[mirror % station.urls.length];
        Log.i(TAG, "opening " + station.id + " " + url.replaceAll("\\?.*", "?…") + " (try " + (failures + 1) + ")");
        String path = Uri.parse(url).getPath();
        if (path != null && path.toLowerCase(java.util.Locale.ROOT).endsWith(".m3u8")) play(new Hls(url));
        else {
            StreamPlayer s = new StreamPlayer(url, events, main);
            play(s);
            s.start();
        }
    }

    private void play(Out o) {
        out = o;
        ready = false;
        lastProgress = -1;
        stalls = 0;
        setState(LOADING);
        applyVolume();
        main.removeCallbacks(prepareTimeout);
        main.postDelayed(prepareTimeout, PREPARE_MS);
    }

    private void onReady() {
        ready = true;
        main.removeCallbacks(prepareTimeout);
        failures = 0;
        error = "";
        setState(PLAYING);
        Log.i(TAG, "playing " + station.id);
        main.removeCallbacks(stallCheck);
        main.postDelayed(stallCheck, STALL_CHECK_MS);
    }

    /** this address failed: the next one at once (1 s), or, all tried, ERROR and a later try (BACKOFF_MS) */
    private void fail(String why) {
        Log.w(TAG, station == null ? why : station.id + " #" + (mirror % Math.max(1, station.urls.length)) + ": " + why);
        releaseOut();
        failures++;
        mirror++;
        long delay;
        int n = station == null ? 1 : station.urls.length;
        if (failures < n) delay = 1000;
        else {
            error = why;
            setState(ERROR);
            delay = BACKOFF_MS[Math.min(failures - n, BACKOFF_MS.length - 1)];
        }
        main.removeCallbacks(retry);
        main.postDelayed(retry, delay);
        retryPending = true;
    }

    private void stop(int to, String err) {
        main.removeCallbacks(retry);
        retryPending = false;
        releaseOut();
        abandonFocus();
        transientLoss = false;
        ducked = false;
        error = err;
        setState(to);
    }

    private void releaseOut() {
        Out o = out;
        out = null;
        ready = false;
        main.removeCallbacks(stallCheck);
        main.removeCallbacks(prepareTimeout);
        if (o != null) o.release();
    }

    private void applyVolume() {
        if (out != null) out.volume(RadioStations.gain(prefs.radioVolume()) * (ducked ? 0.25f : 1f));
    }

    /** an HLS stream with MediaPlayer (it plays those; its MP3 extractor never ends a live stream on these TVs) */
    private final class Hls implements Out {
        private final MediaPlayer mp = new MediaPlayer();

        Hls(String url) {
            try {
                mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
                mp.setOnPreparedListener(p -> {
                    if (out != this) return;
                    applyVolume();
                    p.start();
                    onReady();
                });
                mp.setOnErrorListener((p, what, extra) -> { if (out == this) fail("error " + what + "/" + extra); return true; });
                // a live stream that ends was cut: as an error, after which the same address again
                mp.setOnCompletionListener(p -> { if (out == this) fail("the stream ended"); });
                mp.setOnInfoListener((p, what, extra) -> {
                    if (out != this) return false;
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) setState(LOADING);
                    else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END && ready) setState(PLAYING);
                    return false;
                });
                Map<String, String> headers = new HashMap<>();
                headers.put("User-Agent", Radio.userAgent());
                mp.setDataSource(RadioService.this, Uri.parse(url), headers);
                mp.prepareAsync();
            } catch (Exception e) {
                main.post(() -> { if (out == this) fail(e.getClass().getSimpleName()); });
            }
        }

        @Override public void pause() { try { mp.pause(); } catch (IllegalStateException ignored) { } }
        @Override public void resume() { try { mp.start(); } catch (IllegalStateException e) { fail("resume"); } }
        @Override public void volume(float g) { try { mp.setVolume(g, g); } catch (IllegalStateException ignored) { } }
        @Override public long progress() { try { return mp.getCurrentPosition(); } catch (IllegalStateException e) { return -1; } }

        @Override public void release() {
            mp.setOnPreparedListener(null);
            mp.setOnErrorListener(null);
            mp.setOnCompletionListener(null);
            mp.setOnInfoListener(null);
            // off the main thread: a player still connecting can take seconds to let go
            new Thread(() -> { try { mp.reset(); } catch (Exception ignored) { } mp.release(); }, "radio-release").start();
        }
    }

    // ---------------------------------------------------------------- the audio focus

    private boolean requestFocus() {
        if (focus) return true;
        int r;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener(focusListener, main).build();
            r = audio.requestAudioFocus(focusRequest);
        } else {
            r = audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        focus = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return focus;
    }

    private void abandonFocus() {
        if (!focus) return;
        focus = false;
        if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) audio.abandonAudioFocusRequest(focusRequest);
        else audio.abandonAudioFocus(focusListener);
    }

    private void onFocus(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:   // another app plays: stop until our screens are back in front
                Log.i(TAG, "audio focus lost: stopped");
                focus = false;
                focusLost = true;
                stop(IDLE, "");
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (transientLoss) break;
                transientLoss = true;
                if (!userPaused) pausedAt = SystemClock.elapsedRealtime();
                if (out != null && ready) out.pause();
                main.removeCallbacks(stallCheck);
                setState(PAUSED);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                ducked = true;
                applyVolume();
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                ducked = false;
                focus = true;
                if (transientLoss) {
                    transientLoss = false;
                    if (!userPaused && wanted()) resumeOut();
                }
                applyVolume();
                break;
            default:
        }
    }

    // ---------------------------------------------------------------- state

    private void setState(int s) {
        state = s;
        if (session == null) return;
        int ps = s == PLAYING ? PlaybackState.STATE_PLAYING : s == LOADING ? PlaybackState.STATE_BUFFERING
            : s == ERROR ? PlaybackState.STATE_ERROR : s == PAUSED ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_STOPPED;
        PlaybackState.Builder b = new PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_STOP | PlaybackState.ACTION_PLAY_PAUSE)
            .setState(ps, PlaybackState.PLAYBACK_POSITION_UNKNOWN, s == PLAYING ? 1f : 0f);
        if (s == ERROR) b.setErrorMessage(error);
        session.setPlaybackState(b.build());
        if (s != IDLE && station != null) {
            session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, station.name)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "إذاعة القرآن الكريم").build());
        }
        // active only while it plays or tries to: the remote's media keys are ours then, never after
        boolean active = s == PLAYING || s == LOADING || s == PAUSED;
        if (session.isActive() != active) session.setActive(active);
    }
}
