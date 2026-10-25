package com.raad.sauditv.screensaver;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;

/**
 * One live MP3 or AAC (ADTS) stream over HTTP or ICY (Shoutcast, Icecast, Radiojar), decoded with Android's own
 * MediaCodec and played with an AudioTrack, on a thread of its own. Not MediaPlayer: on these MediaTek TVs its MP3
 * extractor reads a live stream to its end to learn the duration («DLNA before read duration»), so prepare never
 * finishes. HLS (.m3u8) stays with MediaPlayer (RadioService), which plays it.
 *
 * Redirects are followed across http/https (Radiojar's https address sends on to an http:// node); a .pls or a plain
 * .m3u playlist is followed to its first address; ID3 tags and anything between frames are skipped. No ICY metadata
 * is asked for, so the stream is audio only. Events go to the main thread's Handler.
 */
final class StreamPlayer implements RadioService.Out {
    private static final String TAG = RadioService.TAG;

    interface Events {
        void ready(RadioService.Out o);                 // sound is playing
        void failed(RadioService.Out o, String why);
        void hls(RadioService.Out o, String url);      // the address is an HLS playlist: MediaPlayer's job
    }

    private final String url;
    private final Events events;
    private final Handler main;
    private final Thread thread;
    private volatile boolean stopped, paused, flush;
    private volatile float gain = 1f;
    private volatile long frames;   // PCM frames written (the stall check)
    private volatile HttpURLConnection con;

    StreamPlayer(String url, Events events, Handler main) {
        this.url = url;
        this.events = events;
        this.main = main;
        thread = new Thread(this::run, "radio-stream");
    }

    void start() { thread.start(); }

    @Override public void pause() { paused = true; }
    @Override public void resume() { if (paused) { flush = true; paused = false; } }
    @Override public void volume(float g) { gain = g; }
    @Override public long progress() { return frames; }

    @Override
    public void release() {
        stopped = true;
        HttpURLConnection c = con;
        if (c != null) new Thread(c::disconnect, "radio-close").start();   // unblocks a read
        thread.interrupt();
    }

    // ---------------------------------------------------------------- the thread

    private void fail(String why) {
        if (stopped) return;
        stopped = true;
        main.post(() -> events.failed(this, why));
    }

    private void run() {
        InputStream in = null;
        MediaCodec codec = null;
        AudioTrack track = null;
        try {
            in = open(url, 0);
            if (in == null) return;   // handed over (HLS) or failed: already said
            byte[] buf = new byte[256 * 1024];
            int len = 0, pos = 0;
            Frame f = null;
            long pts = 0;
            boolean said = false;
            long written0 = 0;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            float g = -1;
            while (!stopped) {
                // more bytes: keep at most what is not parsed yet
                if (pos > 0 && (len - pos < 4096 || pos > buf.length / 2)) {
                    System.arraycopy(buf, pos, buf, 0, len - pos);
                    len -= pos;
                    pos = 0;
                }
                if (len < buf.length) {
                    int n = in.read(buf, len, Math.min(16384, buf.length - len));
                    if (n < 0) { fail("the stream ended"); break; }
                    len += n;
                }
                if (paused) {   // the live stream goes on: what comes meanwhile is dropped
                    if (track != null && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) track.pause();
                    pos = len;
                    continue;
                }
                if (flush) {
                    flush = false;
                    if (codec != null) codec.flush();
                    if (track != null) { track.flush(); track.play(); }
                }
                // the frames now in the buffer, each to the decoder
                while (!stopped && !paused) {
                    int at = sync(buf, pos, len, f);
                    if (at < 0) { pos = Math.max(pos, len - 8); break; }   // no frame start yet: keep the tail
                    Frame h = Frame.at(buf, at, len);
                    if (h == null || at + h.length > len) { pos = at; break; }   // the frame's end not here yet
                    if (f == null) {   // the first: the decoder for this stream
                        f = h;
                        codec = MediaCodec.createDecoderByType(h.mime);
                        MediaFormat fmt = MediaFormat.createAudioFormat(h.mime, h.rate, h.channels);
                        if (h.adts) fmt.setByteBuffer("csd-0", ByteBuffer.wrap(h.config()));
                        codec.configure(fmt, null, null, 0);
                        codec.start();
                        Log.i(TAG, "stream: " + (h.adts ? "AAC" : "MP3") + " " + h.rate + " Hz, " + h.channels + " ch");
                    }
                    int ib = codec.dequeueInputBuffer(10000);
                    if (ib >= 0) {
                        ByteBuffer b = codec.getInputBuffer(ib);
                        int off = at + h.header, size = h.length - h.header;   // ADTS: the raw frame; MP3: all of it
                        if (!h.adts) { off = at; size = h.length; }
                        b.clear();
                        b.put(buf, off, size);
                        codec.queueInputBuffer(ib, 0, size, pts, 0);
                        pts += h.samples * 1000000L / h.rate;
                        pos = at + h.length;
                    }
                    // the decoder's sound to the track (created from the decoder's own output format: HE-AAC doubles
                    // the rate)
                    int ob;
                    while ((ob = codec.dequeueOutputBuffer(info, 0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            MediaFormat out = codec.getOutputFormat();
                            if (track != null) track.release();
                            track = track(out.getInteger(MediaFormat.KEY_SAMPLE_RATE), out.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                            written0 = frames;
                            g = -1;
                            continue;
                        }
                        if (ob < 0) continue;
                        if (track == null) {
                            track = track(f.rate, f.channels);
                            written0 = frames;
                        }
                        if (g != gain) { g = gain; track.setVolume(g); }
                        ByteBuffer pcm = codec.getOutputBuffer(ob);
                        byte[] chunk = new byte[info.size];
                        pcm.position(info.offset);
                        pcm.get(chunk, 0, info.size);
                        codec.releaseOutputBuffer(ob, false);
                        int w = 0;
                        while (w < chunk.length && !stopped && !paused) {
                            int k = track.write(chunk, w, chunk.length - w);   // blocks while the track is full: the pace
                            if (k < 0) throw new IOException("AudioTrack " + k);
                            w += k;
                        }
                        frames += chunk.length / (2L * track.getChannelCount());
                        if (!said && frames - written0 > track.getSampleRate() / 2) {   // half a second out: playing
                            said = true;
                            main.post(() -> { if (!stopped) events.ready(this); });
                        }
                    }
                }
            }
        } catch (InterruptedIOException ignored) {
            // released
        } catch (Exception e) {
            if (!stopped) Log.w(TAG, "stream: " + e);
            fail(e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            HttpURLConnection c = con;
            if (c != null) c.disconnect();
            if (codec != null) { try { codec.stop(); } catch (Exception ignored) { } codec.release(); }
            if (track != null) { try { track.stop(); } catch (Exception ignored) { } track.release(); }
        }
    }

    private static final class InterruptedIOException extends IOException { }

    private AudioTrack track(int rate, int channels) {
        int mask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
        int size = Math.max(min * 4, rate * channels * 2);   // about a second: a short network hiccup is not heard
        AudioTrack t = new AudioTrack(
            new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            new AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            size, AudioTrack.MODE_STREAM, android.media.AudioManager.AUDIO_SESSION_ID_GENERATE);
        t.setVolume(gain);
        t.play();
        return t;
    }

    /** the stream, after redirects (any scheme) and a playlist; null when it is HLS (handed over) */
    private InputStream open(String u, int depth) throws IOException {
        if (depth > 6) throw new IOException("too many redirects");
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        con = c;
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", Radio.userAgent());
        c.setRequestProperty("Accept", "*/*");
        if (stopped) throw new InterruptedIOException();
        int code = c.getResponseCode();
        if (code / 100 == 3) {
            String to = c.getHeaderField("Location");
            c.disconnect();
            if (to == null) throw new IOException("HTTP " + code + " without Location");
            return open(new URL(new URL(u), to).toString(), depth + 1);
        }
        if (code != 200) throw new IOException("HTTP " + code);
        String type = String.valueOf(c.getContentType()).toLowerCase(java.util.Locale.ROOT);
        InputStream in = c.getInputStream();
        if (type.contains("mpegurl") || type.contains("scpls") || type.contains("text/") || u.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(m3u8?|pls)(\\?.*)?")) {
            String text = small(in);
            in.close();
            if (text.contains("#EXT-X-")) {   // HLS
                main.post(() -> { if (!stopped) events.hls(this, u); });
                stopped = true;
                return null;
            }
            for (String line : text.split("[\\r\\n]+")) {   // .pls (File1=…) or a plain .m3u: its first address
                String s = line.trim().replaceFirst("^File\\d+=", "");
                if (s.matches("(?i)https?://\\S+")) return open(s, depth + 1);
            }
            throw new IOException("a playlist without an address");
        }
        return in;
    }

    private static String small(InputStream in) throws IOException {
        byte[] b = new byte[65536];
        int n = 0, k;
        while (n < b.length && (k = in.read(b, n, b.length - n)) > 0) n += k;
        return new String(b, 0, n, "UTF-8");
    }

    /** the next frame start from pos: an MP3 or ADTS header whose next frame (when there) has one too; the stream's
     *  first frame (known) must match it. An ID3 tag is skipped. -1 when none yet. */
    private static int sync(byte[] b, int pos, int len, Frame known) {
        for (int i = pos; i + 10 <= len; i++) {
            if (b[i] == 'I' && b[i + 1] == 'D' && b[i + 2] == '3' && i + 10 <= len) {   // an ID3v2 tag: its size, syncsafe
                int size = ((b[i + 6] & 0x7F) << 21) | ((b[i + 7] & 0x7F) << 14) | ((b[i + 8] & 0x7F) << 7) | (b[i + 9] & 0x7F);
                if (i + 10 + size > len) return -1;
                i += 9 + size;
                continue;
            }
            if ((b[i] & 0xFF) != 0xFF || (b[i + 1] & 0xE0) != 0xE0) continue;
            Frame h = Frame.at(b, i, len);
            if (h == null) continue;
            if (known != null && (h.adts != known.adts || h.rate != known.rate)) continue;
            if (known == null) {   // the first: the next one must follow it
                int next = i + h.length;
                if (next + 4 > len) return -1;   // wait for more
                Frame n = Frame.at(b, next, len);
                if (n == null || n.adts != h.adts || n.rate != h.rate) continue;
            }
            return i;
        }
        return -1;
    }

    /** an MPEG audio (MP3, layers I–III) or ADTS (AAC) frame header */
    static final class Frame {
        boolean adts;
        String mime;
        int rate, channels, length, header, samples;
        int profile, rateIndex, channelConfig;   // ADTS, for the decoder's AudioSpecificConfig

        private static final int[] MP3_RATES = {44100, 48000, 32000};
        private static final int[][] BITRATES = {   // kbit/s: MPEG-1 layers I, II, III; MPEG-2/2.5 layer I; II and III
            {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448},
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384},
            {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320},
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256},
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160},
        };
        private static final int[] AAC_RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};

        static Frame at(byte[] b, int i, int len) {
            if (i + 7 > len) return null;
            int b1 = b[i + 1] & 0xFF, b2 = b[i + 2] & 0xFF, b3 = b[i + 3] & 0xFF;
            if ((b[i] & 0xFF) != 0xFF || (b1 & 0xE0) != 0xE0) return null;
            Frame f = new Frame();
            if ((b1 & 0xF6) == 0xF0) {   // ADTS: 12 sync bits, layer 00
                f.adts = true;
                f.mime = "audio/mp4a-latm";
                f.profile = (b2 >> 6) & 3;
                f.rateIndex = (b2 >> 2) & 0xF;
                f.channelConfig = ((b2 & 1) << 2) | ((b3 >> 6) & 3);
                if (f.rateIndex >= AAC_RATES.length || f.channelConfig == 0 || f.profile == 3) return null;
                f.rate = AAC_RATES[f.rateIndex];
                f.channels = f.channelConfig == 7 ? 8 : f.channelConfig;
                f.header = (b1 & 1) == 0 ? 9 : 7;   // protection absent: 7 bytes
                f.length = ((b3 & 3) << 11) | ((b[i + 4] & 0xFF) << 3) | ((b[i + 5] & 0xFF) >> 5);
                f.samples = 1024 * ((b[i + 6] & 3) + 1);
                return f.length > f.header ? f : null;
            }
            int version = (b1 >> 3) & 3, layer = (b1 >> 1) & 3;   // version 3: MPEG-1, 2: MPEG-2, 0: MPEG-2.5
            int bi = (b2 >> 4) & 0xF, ri = (b2 >> 2) & 3, pad = (b2 >> 1) & 1, mode = (b3 >> 6) & 3;
            if (version == 1 || layer == 0 || bi == 0 || bi == 15 || ri == 3) return null;
            f.mime = "audio/mpeg";
            f.rate = MP3_RATES[ri] >> (version == 3 ? 0 : version == 2 ? 1 : 2);
            f.channels = mode == 3 ? 1 : 2;
            boolean mpeg1 = version == 3;
            int l = 4 - layer;   // 1, 2 or 3
            int kbps = BITRATES[mpeg1 ? l - 1 : l == 1 ? 3 : 4][bi];
            if (l == 1) { f.length = (12 * kbps * 1000 / f.rate + pad) * 4; f.samples = 384; }
            else if (l == 2 || mpeg1) { f.length = 144 * kbps * 1000 / f.rate + pad; f.samples = 1152; }
            else { f.length = 72 * kbps * 1000 / f.rate + pad; f.samples = 576; }
            f.header = 4;
            return f.length > 4 ? f : null;
        }

        /** AAC's AudioSpecificConfig for the decoder (csd-0): object type, sampling index, channels */
        byte[] config() {
            int object = profile + 1;
            return new byte[] {(byte) ((object << 3) | (rateIndex >> 1)), (byte) (((rateIndex & 1) << 7) | (channelConfig << 3))};
        }
    }
}
