package com.raad.sauditv.home;

/**
 * The size a photo is kept at for this screen (PhotoScale). The page draws it with background-size: cover over the
 * whole screen, at most MAX_ZOOM times larger (the detail page's blurred copy: scale(1.1); the apps grid's 1.08; the
 * screensaver's settle 1.055), so it never needs more than MAX_ZOOM times the screen's pixels on the side that covers.
 * The photos are 1920 x 1080; TV 1 draws 1280 x 720 (its display size is set lower than the panel's): each one shown
 * cost 8.3 MB decoded instead of the 4.5 MB this keeps. A screen that draws 1920 x 1080 gets them as they are.
 * Plain Java (tests/java_test.py runs it); the same file in both apps but for the package line (tests/check.py).
 */
final class PhotoSize {
    private PhotoSize() {}

    static final double MAX_ZOOM = 1.1;
    /** a smaller copy only when it keeps at most this share of the pixels (else it is not worth a copy) */
    static final double MAX_SHARE = 0.75;

    /** {width, height} to keep the photo at, the same shape; null: as it is */
    static int[] target(int srcW, int srcH, int screenW, int screenH) {
        if (srcW <= 0 || srcH <= 0 || screenW <= 0 || screenH <= 0) return null;
        int sw = Math.max(screenW, screenH), sh = Math.min(screenW, screenH);   // a TV: landscape
        double k = MAX_ZOOM * Math.max((double) sw / srcW, (double) sh / srcH);
        if (k * k > MAX_SHARE) return null;
        return new int[] {(int) Math.ceil(srcW * k - 1e-6), (int) Math.ceil(srcH * k - 1e-6)};   // 792.0000001 is 792
    }

    /** BitmapFactory's inSampleSize for a decode at least targetW wide: the largest power of two that leaves that */
    static int sample(int srcW, int targetW) {
        int s = 1;
        while (targetW > 0 && srcW / (s * 2) >= targetW) s *= 2;
        return s;
    }

    // ---- the online photos (photos/online.json): Wikimedia Commons thumbnails and Bing's photo of the day, downloaded
    // by the app at this screen's width and kept, the newest few (PhotoCache.online; HomePage.online in the launcher)
    private static final java.util.regex.Pattern ONLINE = java.util.regex.Pattern.compile(
        "^https://((upload|thumb)\\.wikimedia\\.org/wikipedia/commons/[^?#]+\\.(?i:jpe?g)|www\\.bing\\.com/th\\?id=OHR\\.[A-Za-z0-9_.-]+\\.jpg)$");
    private static final java.util.regex.Pattern COMMONS_THUMB = java.util.regex.Pattern.compile(
        "^(https://(?:upload|thumb)\\.wikimedia\\.org/wikipedia/commons/thumb/[0-9a-f]/[0-9a-f]{2}/[^/?#]+/)(\\d+)px-([^/?#]+)$");
    /** the thumbnail widths Commons keeps ready (another width may be refused) */
    static final int[] COMMONS_WIDTHS = {960, 1280, 1920};

    /** an online photo the app downloads and keeps */
    static boolean online(String url) { return url != null && ONLINE.matcher(url).matches(); }

    /** A Commons thumbnail (…/thumb/a/ab/Name.jpg/1920px-Name.jpg) at the width this screen draws: the smallest of
     *  COMMONS_WIDTHS that covers the screen (target's width without its MAX_ZOOM room: the settle's small zoom
     *  scales it up a little), if narrower than the one asked for; a screen that keeps photos as they are (target:
     *  null) and any other address: as it is. 1280 x 720 -> 1280, 960 x 540 -> 960, 1920 x 1080 -> 1920. */
    static String commonsWidth(String url, int screenW, int screenH) {
        java.util.regex.Matcher m = url == null ? null : COMMONS_THUMB.matcher(url);
        if (m == null || !m.matches()) return url;
        int[] t = target(1920, 1080, screenW, screenH);
        if (t == null) return url;
        int need = (int) Math.ceil(t[0] / MAX_ZOOM - 1e-6), asked = Integer.parseInt(m.group(2));
        for (int w : COMMONS_WIDTHS) if (w >= need) return w < asked ? m.group(1) + w + "px-" + m.group(3) : url;
        return url;
    }

    /** the kept file's name for an address: its SHA-1, so any address is a safe file name */
    static String cacheName(String url) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-1").digest(url.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x & 0xff));
            return b.append(".jpg").toString();
        } catch (Exception e) {
            return Integer.toHexString(url.hashCode()) + ".jpg";
        }
    }
}
