package com.raad.sauditv.screensaver;

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
}
