package com.han.wmsave;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

public class MainActivity extends Activity {

    private WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        // Android 7-9 保存到相册需要存储权限
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29) {
            if (checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"}, 2);
            }
        }

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.addJavascriptInterface(new Bridge(), "App");
        web.setWebViewClient(new WebViewClient());
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /** 给 JS 调用的原生桥 */
    private class Bridge {

        /** 读取剪贴板内容 */
        @JavascriptInterface
        public String getClipboard() {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData d = cm.getPrimaryClip();
                if (d != null && d.getItemCount() > 0) {
                    return String.valueOf(d.getItemAt(0).coerceToText(MainActivity.this));
                }
            } catch (Exception ignored) {
            }
            return "";
        }

        /** 解析分享文本，回调 window.__onParse(json) */
        @JavascriptInterface
        public void parse(final String text) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final String result = doParse(text);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            web.evaluateJavascript("window.__onParse(" + result + ")", null);
                        }
                    });
                }
            }).start();
        }

        /**
         * 下载并保存到相册，回调：
         * window.__onProgress(dlId, percent)
         * window.__onDone(dlId, ok, msg)
         * fallbackUrl：高画质下载失败时自动降级重试的地址
         * referer：平台 CDN 防盗链需要的请求头，可为空
         */
        @JavascriptInterface
        public void download(final String url, final String fallbackUrl,
                             final String fileName, final String kind,
                             final String referer, final String dlId) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Downloader.downloadToGallery(MainActivity.this, url, fallbackUrl,
                                fileName, kind, referer,
                                new Downloader.Progress() {
                                    @Override
                                    public void onProgress(int percent) {
                                        postJs("window.__onProgress('" + escapeJs(dlId)
                                                + "'," + percent + ")");
                                    }
                                });
                        postJs("window.__onDone('" + escapeJs(dlId)
                                + "',true,'已保存到相册（相册/无水印下载）')");
                    } catch (Exception e) {
                        postJs("window.__onDone('" + escapeJs(dlId) + "',false,'"
                                + escapeJs(String.valueOf(e.getMessage())) + "')");
                    }
                }
            }).start();
        }

        /**
         * 下载视频并转码为 GIF 动图保存到相册，回调同 download。
         * 进度：下载占 0-60，转码占 60-100
         */
        @JavascriptInterface
        public void downloadGif(final String url, final String fallbackUrl,
                                final String fileName, final String referer,
                                final String dlId) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    File tmp = null;
                    File gifTmp = null;
                    try {
                        tmp = Downloader.downloadToCache(MainActivity.this, url,
                                fallbackUrl, referer, new Downloader.Progress() {
                                    @Override
                                    public void onProgress(int percent) {
                                        postJs("window.__onProgress('" + escapeJs(dlId)
                                                + "'," + (percent * 60 / 100) + ")");
                                    }
                                });
                        gifTmp = File.createTempFile("gif_", ".gif",
                                MainActivity.this.getCacheDir());
                        GifConverter.convert(tmp, new FileOutputStream(gifTmp),
                                new GifConverter.Progress() {
                                    @Override
                                    public void onProgress(int percent) {
                                        postJs("window.__onProgress('" + escapeJs(dlId)
                                                + "'," + (60 + percent * 40 / 100) + ")");
                                    }
                                });
                        Downloader.saveToMediaStore(MainActivity.this, gifTmp,
                                fileName, true, null);
                        postJs("window.__onDone('" + escapeJs(dlId)
                                + "',true,'动图已保存到相册（相册/无水印下载）')");
                    } catch (Exception e) {
                        postJs("window.__onDone('" + escapeJs(dlId) + "',false,'"
                                + escapeJs(String.valueOf(e.getMessage())) + "')");
                    } finally {
                        if (tmp != null) tmp.delete();
                        if (gifTmp != null) gifTmp.delete();
                    }
                }
            }).start();
        }

        private void postJs(final String js) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    web.evaluateJavascript(js, null);
                }
            });
        }
    }

    private String doParse(String text) {
        try {
            // 平台路由：根据链接识别抖音/B站/小红书并分发到对应解析器
            PlatformParser.Item item = PlatformParser.parse(text);

            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("platform", item.platform);
            o.put("platformName", item.platformName);
            o.put("id", item.id);
            o.put("desc", item.desc);
            o.put("nickname", item.nickname);
            o.put("videoUrl", item.videoUrl);
            o.put("videoUrlSd", item.videoUrlSd);
            o.put("coverUrl", item.coverUrl);
            o.put("referer", item.referer);
            o.put("images", new org.json.JSONArray(item.imageUrls));
            return o.toString();
        } catch (Exception e) {
            try {
                JSONObject o = new JSONObject();
                o.put("ok", false);
                o.put("error", e.getMessage() == null ? "未知错误" : e.getMessage());
                return o.toString();
            } catch (Exception ignored) {
                return "{\"ok\":false,\"error\":\"解析失败\"}";
            }
        }
    }

    private static String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
