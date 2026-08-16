package com.shaneqwq.lifeassistant;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * 內容自動更新。
 *
 * 網頁本身打包在 APK 裡（第一次安裝、或永遠沒網路時就用這份），
 * 之後每次開啟會到 GitHub Pages 比對 index.html 的雜湊值，
 * 有變動就整包下載到內部儲存空間，下次開啟改用新的那份。
 *
 * 因為新舊都由 WebViewAssetLoader 掛在同一個 origin 底下，
 * localStorage 是跟著 origin 走的，所以更新不會弄丟使用者資料。
 */
final class WebUpdater {

    /** 內容來源：GitHub Pages 上 main 分支的部署結果 */
    private static final String BASE = "https://shaneqwq.github.io/mydaughter/";
    private static final String[] FILES = { "index.html", "manifest.json", "sw.js", "icon.svg" };

    private static final String PREFS = "web_update";
    private static final String KEY_HASH = "content_hash";
    private static final int TIMEOUT_MS = 15000;
    /** index.html 應該遠大於這個大小；太小代表下載被截斷或拿到錯誤頁 */
    private static final int MIN_HTML_BYTES = 2000;

    interface Callback {
        void onResult(String message, boolean updated);
    }

    private WebUpdater() {}

    static File liveDir(Context ctx) {
        return new File(ctx.getFilesDir(), "web");
    }

    /** 已下載且看起來完整的更新才算數 */
    static boolean hasUpdate(Context ctx) {
        File idx = new File(liveDir(ctx), "index.html");
        return idx.isFile() && idx.length() >= MIN_HTML_BYTES;
    }

    /**
     * 第一次啟動時，先把「打包在 APK 內的版本」的雜湊值記下來，
     * 否則第一次檢查一定會判定成有新版而白下載一次。
     */
    static void seedBaselineIfNeeded(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp.getString(KEY_HASH, null) != null) return;
        try (InputStream in = ctx.getAssets().open("www/index.html")) {
            sp.edit().putString(KEY_HASH, sha256(readAll(in))).apply();
        } catch (Exception ignored) {
            // 讀不到就算了，最多第一次多下載一次
        }
    }

    /** 在背景執行緒檢查並下載；失敗一律安靜處理（沒網路是很正常的情況） */
    static void checkInBackground(final Context ctx, final Callback cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                String msg;
                boolean updated = false;
                try {
                    updated = doCheck(ctx);
                    msg = updated ? "已下載新版本，下次開啟生效" : "已經是最新版本";
                } catch (Exception e) {
                    msg = "無法連線，稍後再試";
                }
                if (cb != null) cb.onResult(msg, updated);
            }
        }, "web-updater").start();
    }

    private static boolean doCheck(Context ctx) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        byte[] html = fetch(BASE + "index.html");
        if (html.length < MIN_HTML_BYTES) throw new IOException("內容過短，可能下載不完整");
        String hash = sha256(html);
        if (hash.equals(sp.getString(KEY_HASH, null))) return false;   // 沒變動

        // 先全部抓到暫存區，確定都成功才替換，避免留下半套內容
        File tmp = new File(ctx.getCacheDir(), "web_tmp");
        deleteRecursive(tmp);
        if (!tmp.mkdirs()) throw new IOException("無法建立暫存資料夾");

        write(new File(tmp, "index.html"), html);
        for (String f : FILES) {
            if (f.equals("index.html")) continue;
            write(new File(tmp, f), fetch(BASE + f));
        }

        File live = liveDir(ctx);
        File old = new File(ctx.getFilesDir(), "web_old");
        deleteRecursive(old);
        if (live.exists() && !live.renameTo(old)) deleteRecursive(live);
        if (!tmp.renameTo(live)) {
            // 理論上 cacheDir 與 filesDir 同一個檔案系統，退路仍保留
            copyDir(tmp, live);
            deleteRecursive(tmp);
        }
        deleteRecursive(old);

        sp.edit().putString(KEY_HASH, hash).apply();
        return true;
    }

    // ── 工具 ──────────────────────────────────────────────────────────

    private static byte[] fetch(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Cache-Control", "no-cache");
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                return readAll(in);
            }
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static void write(File f, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }

    private static String sha256(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                           .append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }

    private static void copyDir(File src, File dst) throws IOException {
        if (!dst.exists() && !dst.mkdirs()) throw new IOException("無法建立 " + dst);
        File[] kids = src.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            File target = new File(dst, k.getName());
            if (k.isDirectory()) {
                copyDir(k, target);
            } else {
                try (InputStream in = new java.io.FileInputStream(k)) {
                    write(target, readAll(in));
                }
            }
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
