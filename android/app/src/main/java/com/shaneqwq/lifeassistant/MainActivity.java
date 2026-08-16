package com.shaneqwq.lifeassistant;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.KeyEvent;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewAssetLoader;

/**
 * 以 WebView 承載打包在 assets 內的網頁。
 *
 * 這裡刻意透過 WebViewAssetLoader 用 https://appassets.androidplatform.net 提供檔案，
 * 而不是直接 file:// 載入 —— file:// 不算安全來源(secure context)，
 * 相機 getUserMedia 與 BarcodeDetector 都會被瀏覽器擋掉，localStorage 也可能受限。
 */
public class MainActivity extends AppCompatActivity {

    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final int REQ_CAMERA = 1;

    private WebView web;
    private PermissionRequest pendingCameraRequest;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        setContentView(web);

        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);                    // localStorage：所有資料都存在這裡
        ws.setDatabaseEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(false);                     // 資產走 asset loader，不需要 file://
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
                    @Override
                    public void run() {
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

        web.loadUrl(ORIGIN + "/assets/www/index.html");
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
