package com.raad.sauditv.screensaver;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.DisplayMetrics;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * The page's photos at the size this screen draws them (PhotoSize): a copy made once per photo, the first time it is
 * shown, and kept in files/photos-scaled (the newest KEEP). The page then decodes 1408 x 792 instead of 1920 x 1080 on a
 * TV that draws 1280 x 720, the same picture on screen. Any trouble: the photo as it is. Runs on the WebView's network
 * thread. The same file in both apps but for the package line (tests/check.py).
 */
final class PhotoScale {
    private PhotoScale() {}

    /** the screensaver's photo set and the bundled ones, with room to spare */
    private static final int KEEP = 20;
    private static final int QUALITY = 92;
    /** photos found not to need a copy on this screen (name), so their size is not read again */
    private static final Set<String> AS_IS = new HashSet<>();

    interface Source { InputStream open() throws IOException; }

    /** the kept copy of photo `name` (opened by src) for this screen, made now if needed; null: use it as it is */
    static synchronized File of(Context c, String name, Source src) {
        if (AS_IS.contains(name)) return null;
        try {
            DisplayMetrics m = c.getResources().getDisplayMetrics();
            File dir = new File(c.getFilesDir(), "photos-scaled");
            String prefix = Math.max(m.widthPixels, m.heightPixels) + "x" + Math.min(m.widthPixels, m.heightPixels) + "-";
            File f = new File(dir, prefix + name);
            if (f.isFile() && f.length() > 0) return f;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = src.open()) { BitmapFactory.decodeStream(in, null, bounds); }
            int[] t = PhotoSize.target(bounds.outWidth, bounds.outHeight, m.widthPixels, m.heightPixels);
            if (t == null) { AS_IS.add(name); return null; }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = PhotoSize.sample(bounds.outWidth, t[0]);
            Bitmap full;
            try (InputStream in = src.open()) { full = BitmapFactory.decodeStream(in, null, o); }
            if (full == null) return null;
            Bitmap small = Bitmap.createScaledBitmap(full, t[0], t[1], true);
            if (small != full) full.recycle();
            if (!dir.isDirectory() && !dir.mkdirs()) { small.recycle(); return null; }
            File tmp = new File(dir, prefix + name + ".tmp");
            boolean ok;
            try (FileOutputStream out = new FileOutputStream(tmp)) { ok = small.compress(Bitmap.CompressFormat.JPEG, QUALITY, out); }
            small.recycle();
            if (!ok || !tmp.renameTo(f)) { tmp.delete(); return null; }
            prune(dir);
            return f;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /** the newest KEEP copies stay */
    private static void prune(File dir) {
        File[] all = dir.listFiles((d, n) -> n.endsWith(".jpg"));
        if (all == null || all.length <= KEEP) return;
        Arrays.sort(all, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = KEEP; i < all.length; i++) all[i].delete();
    }
}
