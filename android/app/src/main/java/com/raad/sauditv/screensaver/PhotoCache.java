package com.raad.sauditv.screensaver;

import android.content.Context;
import android.webkit.WebResourceResponse;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Answers the page's font and photo requests from the device instead of the network, so the screensaver
 * opens at once. Fonts and bundled photos come from the APK; hosted photos are downloaded once and kept. Photos are
 * served at the size this screen draws them (PhotoScale).
 */
final class PhotoCache {
    static final String REPO = "https://raw.githubusercontent.com/raad113/SaudiTV/main/";
    private static final String BUNDLED = REPO + "assets/backgrounds/", HOSTED = REPO + "photos/",
        FONTS = REPO + "assets/fonts/";
    private static final int KEEP = 12;   // the 5-photo set plus room for the next one

    private PhotoCache() {}

    /** null lets the WebView load the URL itself */
    static WebResourceResponse serve(Context c, String url) {
        try {
            if (url.startsWith(FONTS)) {
                String name = url.substring(FONTS.length());
                if (!name.matches("[A-Za-z0-9_-]+\\.woff2")) return null;
                // fonts are fetched with CORS, and the page's origin is file://
                java.util.Map<String, String> h = new java.util.HashMap<>();
                h.put("Access-Control-Allow-Origin", "*");
                return new WebResourceResponse("font/woff2", null, 200, "OK", h, c.getAssets().open("fonts/" + name));
            }
            // both kinds at the size this screen draws them (PhotoScale), else as they are
            if (url.startsWith(BUNDLED)) {
                final String name = safeName(url.substring(BUNDLED.length()));
                if (name == null) return null;
                File small = PhotoScale.of(c, "b-" + name, () -> c.getAssets().open("backgrounds/" + name));
                return jpeg(small != null ? new FileInputStream(small) : c.getAssets().open("backgrounds/" + name));
            }
            if (url.startsWith(HOSTED)) {
                String name = safeName(url.substring(HOSTED.length()));
                if (name == null) return null;   // index.json and anything else go to the network
                final File f = hosted(c, url, name);
                if (f == null) return null;
                File small = PhotoScale.of(c, name, () -> new FileInputStream(f));
                return jpeg(new FileInputStream(small != null ? small : f));
            }
            // the online photos (photos/online.json): at this screen's width, downloaded once, the newest KEEP_ONLINE
            if (PhotoSize.online(url)) {
                File f = online(c, url);
                return f == null ? null : jpeg(new FileInputStream(f));
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** the page asks for the next few ahead of their turn (photoAhead), so a rolling few are kept */
    private static final int KEEP_ONLINE = 30;
    static final String UA = "SaudiTV/1.0 (https://github.com/raad113/SaudiTV) Android";

    /** An online photo (PhotoSize.online) as a file: a Commons thumbnail at this screen's width (PhotoSize.commonsWidth),
     *  Bing's as it is; downloaded once into files/photos-online. null: not now (offline): the WebView tries, then the
     *  page shows a bundled photo. */
    private static File online(Context c, String url) throws java.io.IOException {
        android.util.DisplayMetrics m = c.getResources().getDisplayMetrics();
        String src = PhotoSize.commonsWidth(url, m.widthPixels, m.heightPixels);
        return download(c, "photos-online", src, PhotoSize.cacheName(src), KEEP_ONLINE);
    }

    private static String safeName(String s) {
        return s.matches("[A-Za-z0-9_-]+\\.jpg") ? s : null;
    }

    private static WebResourceResponse jpeg(InputStream in) {
        return new WebResourceResponse("image/jpeg", null, in);
    }

    /** Runs on the WebView's network thread, so a blocking download is fine. */
    private static File hosted(Context c, String url, String name) throws java.io.IOException {
        return download(c, "photos", url, name, KEEP);
    }

    /** url into files/<folder>/<name> once (the newest keep stay) */
    private static synchronized File download(Context c, String folder, String url, String name, int keep) throws java.io.IOException {
        File dir = new File(c.getFilesDir(), folder);
        File f = new File(dir, name);
        if (f.isFile() && f.length() > 0) return f;
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        con.setRequestProperty("User-Agent", UA);   // Wikimedia asks every client to name itself
        File tmp = new File(dir, name + ".tmp");
        try {
            if (con.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            InputStream in = con.getInputStream();
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                out.close();
                in.close();
            }
            if (!tmp.renameTo(f)) return null;
        } finally {
            con.disconnect();
            tmp.delete();
        }
        prune(dir, keep);
        return f;
    }

    /** Keep the newest few; older sets are not shown again for months. */
    private static void prune(File dir, int keep) {
        File[] all = dir.listFiles((d, n) -> n.endsWith(".jpg"));
        if (all == null || all.length <= keep) return;
        Arrays.sort(all, new Comparator<File>() {
            @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
        });
        for (int i = keep; i < all.length; i++) all[i].delete();
    }
}
