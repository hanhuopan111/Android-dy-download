package com.han.wmsave;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService parseExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService downloadExecutor = Executors.newFixedThreadPool(3);
    private String pendingSharedText = "";
    private boolean pageReady;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        configureSystemBars();
        requestLegacyStoragePermission();
        captureSharedText(getIntent());

        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        if (Build.VERSION.SDK_INT >= 21) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            settings.setSafeBrowsingEnabled(true);
        }

        web.addJavascriptInterface(new Bridge(), "App");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                pageReady = true;
                deliverSharedText();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("file".equals(uri.getScheme()) && "android_asset".equals(uri.getHost())) {
                    return false;
                }
                openExternal(uri);
                return true;
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = Uri.parse(url);
                if (url.startsWith("file:///android_asset/")) return false;
                openExternal(uri);
                return true;
            }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    private void configureSystemBars() {
        getWindow().setStatusBarColor(Color.rgb(238, 244, 255));
        getWindow().setNavigationBarColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    private void requestLegacyStoragePermission() {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29
                && checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"}, 2);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        captureSharedText(intent);
        deliverSharedText();
    }

    private void captureSharedText(Intent intent) {
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())
                && "text/plain".equals(intent.getType())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null && !text.trim().isEmpty()) pendingSharedText = text.trim();
        }
    }

    private void deliverSharedText() {
        if (!pageReady || web == null || pendingSharedText.isEmpty()) return;
        String text = pendingSharedText;
        pendingSharedText = "";
        web.evaluateJavascript("window.__setSharedText(" + JSONObject.quote(text) + ")", null);
    }

    private void openExternal(Uri uri) {
        try {
            if (uri != null && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        parseExecutor.shutdownNow();
        downloadExecutor.shutdownNow();
        if (web != null) {
            web.removeJavascriptInterface("App");
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private class Bridge {
        @JavascriptInterface
        public String getClipboard() {
            try {
                ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData data = manager.getPrimaryClip();
                if (data != null && data.getItemCount() > 0) {
                    return String.valueOf(data.getItemAt(0).coerceToText(MainActivity.this));
                }
            } catch (Exception ignored) {
            }
            return "";
        }

        @JavascriptInterface
        public void parse(final String text) {
            parseExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    final String result = doParse(text);
                    postJs("window.__onParse(" + result + ")");
                }
            });
        }

        @JavascriptInterface
        public void download(final String url, final String fallbackUrl,
                             final String fileName, final String kind,
                             final String referer, final String downloadId) {
            downloadExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        Downloader.downloadToGallery(MainActivity.this, url, fallbackUrl,
                                fileName, kind, referer, new Downloader.Progress() {
                                    @Override
                                    public void onProgress(int percent) {
                                        postProgress(downloadId, percent);
                                    }
                                });
                        postDone(downloadId, true, "已保存到相册（相册/河岸保存）");
                    } catch (Exception e) {
                        postDone(downloadId, false, friendlyError(e));
                    }
                }
            });
        }

        @JavascriptInterface
        public void downloadGif(final String url, final String fallbackUrl,
                                final String fileName, final String referer,
                                final String downloadId, final int width,
                                final int seconds, final int fps) {
            downloadExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    File video = null;
                    File gif = null;
                    try {
                        video = Downloader.downloadToCache(MainActivity.this, url,
                                fallbackUrl, referer, new Downloader.Progress() {
                                    @Override
                                    public void onProgress(int percent) {
                                        postProgress(downloadId, percent * 55 / 100);
                                    }
                                });
                        gif = File.createTempFile("gif_", ".gif", getCacheDir());
                        FileOutputStream output = new FileOutputStream(gif);
                        GifConverter.convert(video, output, width, seconds, fps,
                                new GifConverter.Progress() {
                                    @Override
                                    public void onProgress(int percent) {
                                        postProgress(downloadId, 55 + percent * 45 / 100);
                                    }
                                });
                        Downloader.saveToMediaStore(MainActivity.this, gif,
                                fileName, true, null);
                        postDone(downloadId, true, "动图已保存到相册（相册/河岸保存）");
                    } catch (Exception e) {
                        postDone(downloadId, false, friendlyError(e));
                    } finally {
                        if (video != null) video.delete();
                        if (gif != null) gif.delete();
                    }
                }
            });
        }

        /** 下载图片后在本机进行 2× 缩放和轻度锐化，以高质量 JPG 保存。 */
        @JavascriptInterface
        public void enhanceImage(final String url, final String fileName,
                                 final String referer, final String downloadId,
                                 final int scale, final int strength) {
            downloadExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    File source = null;
                    File enhanced = null;
                    try {
                        source = Downloader.downloadToCache(MainActivity.this, url, "", referer,
                                new Downloader.Progress() {
                                    @Override
                                    public void onProgress(int percent) {
                                        postProgress(downloadId, percent * 35 / 100);
                                    }
                                });
                        enhanced = File.createTempFile("enhanced_", ".jpg", getCacheDir());
                        ImageEnhancer.enhance(source, enhanced, scale, strength,
                                new ImageEnhancer.Progress() {
                                    @Override
                                    public void onProgress(int percent) {
                                        postProgress(downloadId, 35 + percent * 65 / 100);
                                    }
                                });
                        Downloader.saveToMediaStore(MainActivity.this, enhanced,
                                fileName, true, null);
                        postDone(downloadId, true, "增强版已保存到相册（相册/河岸保存）");
                    } catch (Exception e) {
                        postDone(downloadId, false, friendlyError(e));
                    } finally {
                        if (source != null) source.delete();
                        if (enhanced != null) enhanced.delete();
                    }
                }
            });
        }

        @JavascriptInterface
        public void openGallery() {
            main.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
                        startActivity(intent);
                    } catch (Exception e) {
                        postJs("window.__toast('没有找到可用的相册应用','error')");
                    }
                }
            });
        }
    }

    private String doParse(String text) {
        try {
            PlatformParser.Item item = PlatformParser.parse(text);
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("platform", item.platform);
            result.put("platformName", item.platformName);
            result.put("id", item.id);
            result.put("desc", item.desc);
            result.put("nickname", item.nickname);
            result.put("videoUrl", item.videoUrl);
            result.put("videoUrlSd", item.videoUrlSd);
            result.put("coverUrl", item.coverUrl);
            result.put("referer", item.referer);
            result.put("images", new org.json.JSONArray(item.imageUrls));
            return result.toString();
        } catch (Exception e) {
            try {
                JSONObject result = new JSONObject();
                result.put("ok", false);
                result.put("error", friendlyError(e));
                return result.toString();
            } catch (Exception ignored) {
                return "{\"ok\":false,\"error\":\"解析失败，请稍后重试\"}";
            }
        }
    }

    private void postProgress(String id, int percent) {
        postJs("window.__onProgress(" + JSONObject.quote(id) + "," + percent + ")");
    }

    private void postDone(String id, boolean ok, String message) {
        postJs("window.__onDone(" + JSONObject.quote(id) + "," + ok + ","
                + JSONObject.quote(message) + ")");
    }

    private void postJs(final String script) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (web != null) web.evaluateJavascript(script, null);
            }
        });
    }

    private static String friendlyError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return "操作失败，请稍后重试";
        if (message.contains("Unable to resolve host") || message.contains("failed to connect")) {
            return "网络连接失败，请检查网络后重试";
        }
        if (message.contains("timeout") || message.contains("timed out")) {
            return "请求超时，请稍后重试";
        }
        return message;
    }
}
