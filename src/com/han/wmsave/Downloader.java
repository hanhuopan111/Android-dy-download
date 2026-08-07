package com.han.wmsave;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 下载并直接保存到系统相册：
 * - Android 10+ 通过 MediaStore 写入 Pictures/无水印下载（图片）、Movies/无水印下载（视频）
 * - Android 7-9 写入公共目录文件 + 媒体扫描
 */
public class Downloader {

    public interface Progress {
        void onProgress(int percent);
    }

    // 注意：B站 CDN 只接受 iPhone 浏览器 UA（Chrome Android 会被拒 403），
    // 故与解析器保持一致
    private static final String UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";

    public static void downloadToGallery(Context ctx, String url, String fallbackUrl,
                                         String fileName, String kind, String referer,
                                         Progress p) throws Exception {
        File tmp = File.createTempFile("dl_", ".tmp", ctx.getCacheDir());
        try {
            try {
                downloadToFile(url, tmp, referer, p);
            } catch (Exception e) {
                // 高画质地址失败时，降级重试默认画质地址
                if (fallbackUrl != null && !fallbackUrl.isEmpty()
                        && !fallbackUrl.equals(url)) {
                    downloadToFile(fallbackUrl, tmp, referer, p);
                } else {
                    throw e;
                }
            }
            boolean isImage = "图片".equals(kind) || "封面".equals(kind);
            saveToMediaStore(ctx, tmp, fileName, isImage, p);
        } finally {
            tmp.delete();
        }
    }

    /** 下载到缓存临时文件并返回（供动图转码等场景使用），失败时自动降级 fallbackUrl */
    public static File downloadToCache(Context ctx, String url, String fallbackUrl,
                                       String referer, Progress p) throws Exception {
        File tmp = File.createTempFile("dl_", ".tmp", ctx.getCacheDir());
        try {
            try {
                downloadToFile(url, tmp, referer, p);
            } catch (Exception e) {
                if (fallbackUrl != null && !fallbackUrl.isEmpty()
                        && !fallbackUrl.equals(url)) {
                    downloadToFile(fallbackUrl, tmp, referer, p);
                } else {
                    throw e;
                }
            }
            return tmp;
        } catch (Exception e) {
            tmp.delete();
            throw e;
        }
    }

    /** 网络下载到临时文件，汇报进度（0-100）；referer 非空时附带请求头 */
    private static long downloadToFile(String url, File dest, String referer,
                                       Progress p) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        if (referer != null && !referer.isEmpty()) {
            c.setRequestProperty("Referer", referer);
        }
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        int code = c.getResponseCode();
        if (code >= 400) throw new Exception("下载失败(HTTP " + code + ")");
        long total = c.getContentLengthLong();
        InputStream in = c.getInputStream();
        OutputStream out = new FileOutputStream(dest);
        byte[] buf = new byte[65536];
        long done = 0;
        int lastPct = -1;
        int n;
        try {
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    int pct = (int) (done * 100 / total);
                    if (pct != lastPct) {
                        lastPct = pct;
                        p.onProgress(pct);
                    }
                }
            }
        } finally {
            in.close();
            out.flush();
            out.close();
            c.disconnect();
        }
        return total;
    }

    /** 保存到系统相册（isImage=true 存 Pictures，否则存 Movies） */
    public static void saveToMediaStore(Context ctx, File file, String fileName,
                                        boolean isImage, Progress p) throws Exception {
        String mime = mimeOf(fileName, isImage);
        if (Build.VERSION.SDK_INT >= 29) {
            // Android 10+：MediaStore 直接写入相册，无需权限
            String dir = isImage ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_MOVIES;
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, dir + "/无水印下载");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = isImage
                    ? ctx.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
                    : ctx.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("保存到相册失败（存储空间不足？）");
            try {
                OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                copyFile(file, os);
                v.clear();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                ctx.getContentResolver().update(uri, v, null, null);
            } catch (Exception e) {
                ctx.getContentResolver().delete(uri, null, null);
                throw e;
            }
        } else {
            // Android 7-9：写公共目录 + 媒体扫描（需要存储权限）
            String dir = isImage ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_MOVIES;
            File folder = new File(Environment.getExternalStoragePublicDirectory(dir), "无水印下载");
            if (!folder.exists() && !folder.mkdirs()) {
                throw new Exception("无法创建保存目录，请检查存储权限");
            }
            File dest = new File(folder, fileName);
            FileOutputStream fos = new FileOutputStream(dest);
            copyFile(file, fos);
            MediaScannerConnection.scanFile(ctx, new String[]{dest.getAbsolutePath()},
                    new String[]{mime}, null);
        }
        if (p != null) p.onProgress(100);
    }

    private static void copyFile(File from, OutputStream out) throws Exception {
        InputStream in = new FileInputStream(from);
        byte[] buf = new byte[65536];
        int n;
        try {
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
            out.flush();
            out.close();
        }
    }

    private static String mimeOf(String fileName, boolean isImage) {
        String f = fileName == null ? "" : fileName.toLowerCase();
        if (isImage) {
            if (f.endsWith(".png")) return "image/png";
            if (f.endsWith(".webp")) return "image/webp";
            if (f.endsWith(".gif")) return "image/gif";
            return "image/jpeg";
        }
        if (f.endsWith(".webm")) return "video/webm";
        return "video/mp4";
    }
}
