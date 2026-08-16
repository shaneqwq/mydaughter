package com.shaneqwq.lifeassistant;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewAssetLoader;

/**
 * 以 WebView 承載網頁內容。
 *
 * 網頁來源有兩處，都掛在同一個 origin 底下：
 *   /assets/www/  → 打包在 APK 內的版本（第一次安裝、或從未更新成功時使用）
 *   /update/      → 從 GitHub Pages 下載的較新版本
 * 同 origin 是刻意的：localStorage 跟著 origin 走，更新內容不會弄丟使用者資料。
 *
 * 另外刻意不用 file:// 載入 —— file:// 不算安全來源(secure context)，
 * 相機 getUserMedia 與條碼辨識都會被擋掉，localStorage 也可能受限。
 */
public class MainActivity extends AppCompatActivity {

    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final String BUNDLED = ORIGIN + "/assets/www/index.html";
    private static final String UPDATED = ORIGIN + "/update/index.html";
    private static final int REQ_CAMERA = 1;

    private WebView web;
    private PermissionRequest pendingCameraRequest;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        WebUpdater.seedBaselineIfNeeded(this);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/update/", new WebViewAssetLoader.InternalStoragePathHandler(
                        this, WebUpdater.liveDir(this)))
                .build();

        web = new WebView(this);
        setContentView(web);

        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);                    // localStorage：所有資料都存在這裡
        ws.setDatabaseEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        for (String res : request.getResources()) {
                            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) {
                                grantCamera(request);
                                return;
                            }
                        }
                        request.deny();
                    }
                });
            }
        });

        web.addJavascriptInterface(new AppHost(), "AppHost");

        // 有下載好的新版就用新版，否則用打包在 APK 內的
        web.loadUrl(WebUpdater.hasUpdate(this) ? UPDATED : BUNDLED);

        // 開啟時在背景靜靜檢查更新；沒網路就當作沒發生
        WebUpdater.checkInBackground(this, new WebUpdater.Callback() {
            @Override public void onResult(final String message, final boolean updated) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (updated) Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    /** 提供給網頁呼叫的介面：只有唯讀資訊與觸發檢查，不做其他事 */
    private class AppHost {
        @JavascriptInterface
        public boolean isApp() { return true; }

        @JavascriptInterface
        public String appVersion() { return BuildConfig.VERSION_NAME; }

        @JavascriptInterface
        public void checkUpdate() {
            WebUpdater.checkInBackground(MainActivity.this, new WebUpdater.Callback() {
                @Override public void onResult(final String message, final boolean updated) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (web == null) return;
                            String js = "window.__updateStatus && window.__updateStatus("
                                    + jsString(message) + "," + updated + ")";
                            web.evaluateJavascript(js, null);
                        }
                    });
                }
            });
        }
    }

    private static String jsString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** 網頁要相機時，先確認 App 本身已取得系統相機權限 */
    private void grantCamera(PermissionRequest request) {
        boolean granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            request.grant(new String[]{ PermissionRequest.RESOURCE_VIDEO_CAPTURE });
        } else {
            pendingCameraRequest = request;
            ActivityCompat.requestPermissions(this, new String[]{ Manifest.permission.CAMERA }, REQ_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_CAMERA || pendingCameraRequest == null) return;

        boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (ok) pendingCameraRequest.grant(new String[]{ PermissionRequest.RESOURCE_VIDEO_CAPTURE });
        else    pendingCameraRequest.deny();
        pendingCameraRequest = null;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
