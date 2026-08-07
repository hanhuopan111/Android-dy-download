package com.han.wmsave;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.io.OutputStream;

/**
 * 视频转 GIF 动图：
 * 1. MediaMetadataRetriever 按固定间隔抽帧
 * 2. AnimatedGifEncoder 逐帧编码
 * 参数：输出宽度 480px、10fps、最长 8 秒（避免生成时间过长）
 */
public class GifConverter {

    /** 转码进度回调（0-100） */
    public interface Progress {
        void onProgress(int percent);
    }

    public static final int MAX_WIDTH = 480;
    public static final int FPS = 10;
    public static final long MAX_DURATION_MS = 8000;

    /** 将视频文件转码为 GIF，写入指定输出流 */
    public static void convert(File video, OutputStream out, Progress p) throws Exception {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(video.getAbsolutePath());
            String dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long duration = dur == null ? MAX_DURATION_MS : Long.parseLong(dur);
            long end = Math.min(duration, MAX_DURATION_MS);
            int frameMs = 1000 / FPS;
            int total = (int) (end / frameMs) + 1;

            AnimatedGifEncoder enc = new AnimatedGifEncoder();
            enc.start(out);
            enc.setDelay(frameMs);
            enc.setRepeat(0);

            int idx = 0;
            Bitmap prev = null;
            for (long t = 0; t < end; t += frameMs) {
                Bitmap bmp = mmr.getFrameAtTime(t * 1000,
                        MediaMetadataRetriever.OPTION_CLOSEST);
                if (bmp != null) {
                    Bitmap scaled = scaleToWidth(bmp, MAX_WIDTH);
                    enc.addFrame(scaled);
                    if (prev != null && !prev.isRecycled()) prev.recycle();
                    prev = scaled;
                    if (bmp != scaled && !bmp.isRecycled()) bmp.recycle();
                }
                idx++;
                if (p != null) p.onProgress(Math.min(100, idx * 100 / total));
            }
            if (prev != null && !prev.isRecycled()) prev.recycle();
            enc.finish();
        } finally {
            mmr.release();
        }
    }

    /** 等比缩放到指定宽度（未超宽时返回原图） */
    private static Bitmap scaleToWidth(Bitmap bmp, int maxW) {
        int w = bmp.getWidth();
        if (w <= maxW) return bmp;
        int h = bmp.getHeight();
        int nw = maxW;
        int nh = Math.max(1, Math.round(h * maxW / (float) w));
        return Bitmap.createScaledBitmap(bmp, nw, nh, true);
    }
}
