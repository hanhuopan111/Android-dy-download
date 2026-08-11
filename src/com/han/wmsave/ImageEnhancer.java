package com.han.wmsave;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 轻量本地图片增强：高质量缩放 + 温和锐化 + 高质量 JPEG 编码。
 * 不依赖云端服务或大型 AI 模型，适合在普通手机上批量处理。
 */
public class ImageEnhancer {

    public interface Progress {
        void onProgress(int percent);
    }

    private static final int MAX_LONG_EDGE = 4096;
    private static final long MAX_PIXELS = 8_000_000L;

    public static void enhance(File input, File output, int requestedScale,
                               int requestedStrength, Progress progress) throws Exception {
        int scale = Math.max(1, Math.min(2, requestedScale));
        int strength = Math.max(0, Math.min(40, requestedStrength));
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(input.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new Exception("无法读取图片文件");
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = 1;
        while ((long) (bounds.outWidth / options.inSampleSize)
                * (bounds.outHeight / options.inSampleSize) > MAX_PIXELS
                || Math.max(bounds.outWidth / options.inSampleSize,
                bounds.outHeight / options.inSampleSize) > MAX_LONG_EDGE) {
            options.inSampleSize *= 2;
        }
        Bitmap source = BitmapFactory.decodeFile(input.getAbsolutePath(), options);
        if (source == null) throw new Exception("图片解码失败，格式可能不受支持");
        if (progress != null) progress.onProgress(15);

        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        double ratio = scale;
        ratio = Math.min(ratio, MAX_LONG_EDGE / (double) Math.max(sourceWidth, sourceHeight));
        ratio = Math.min(ratio, Math.sqrt(MAX_PIXELS / (double) (sourceWidth * (long) sourceHeight)));
        ratio = Math.max(1.0, ratio);
        int width = Math.max(1, (int) Math.round(sourceWidth * ratio));
        int height = Math.max(1, (int) Math.round(sourceHeight * ratio));

        Bitmap scaled = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(scaled);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        canvas.drawBitmap(source, null, new android.graphics.Rect(0, 0, width, height), paint);
        if (source != scaled) source.recycle();
        if (progress != null) progress.onProgress(40);

        if (strength > 0 && width >= 3 && height >= 3) {
            sharpenInPlace(scaled, strength, progress);
        }

        FileOutputStream stream = new FileOutputStream(output);
        try {
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, 96, stream)) {
                throw new Exception("增强图片编码失败");
            }
            stream.flush();
        } finally {
            stream.close();
            scaled.recycle();
        }
        if (progress != null) progress.onProgress(100);
    }

    /** 使用三行滑动窗口锐化，避免为整张图片额外申请一份像素数组。 */
    private static void sharpenInPlace(Bitmap bitmap, int strength, Progress progress) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] previous = new int[width];
        int[] current = new int[width];
        int[] next = new int[width];
        int[] output = new int[width];
        bitmap.getPixels(previous, 0, width, 0, 0, width, 1);
        bitmap.getPixels(current, 0, width, 0, 1, width, 1);
        bitmap.getPixels(next, 0, width, 0, 2, width, 1);

        for (int y = 1; y < height - 1; y++) {
            if (y + 1 < height) bitmap.getPixels(next, 0, width, 0, y + 1, width, 1);
            output[0] = current[0];
            output[width - 1] = current[width - 1];
            for (int x = 1; x < width - 1; x++) {
                int center = current[x];
                int left = current[x - 1];
                int right = current[x + 1];
                int up = previous[x];
                int down = next[x];
                int alpha = center >>> 24;
                int red = enhanceChannel(center >> 16, left >> 16, right >> 16,
                        up >> 16, down >> 16, strength);
                int green = enhanceChannel(center >> 8, left >> 8, right >> 8,
                        up >> 8, down >> 8, strength);
                int blue = enhanceChannel(center, left, right, up, down, strength);
                output[x] = (alpha << 24) | (red << 16) | (green << 8) | blue;
            }
            bitmap.setPixels(output, 0, width, 0, y, width, 1);
            int[] swap = previous;
            previous = current;
            current = next;
            next = swap;
            if (progress != null && y % 24 == 0) {
                progress.onProgress(40 + y * 50 / Math.max(1, height));
            }
        }
    }

    private static int enhanceChannel(int center, int left, int right,
                                      int up, int down, int strength) {
        center &= 0xFF;
        int detail = center * 4 - (left & 0xFF) - (right & 0xFF)
                - (up & 0xFF) - (down & 0xFF);
        int value = center + detail * strength / 100;
        return Math.max(0, Math.min(255, value));
    }
}
