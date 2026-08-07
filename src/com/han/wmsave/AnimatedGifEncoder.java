package com.han.wmsave;

import android.graphics.Bitmap;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GIF89a 动图编码器（精简实现）：
 * - 每帧 256 色调色板（颜色直方图取前 256，超出部分按最近色映射）
 * - LZW 压缩，支持无限循环（NETSCAPE2.0 扩展）
 * - 纯 Java 无外部依赖
 */
public class AnimatedGifEncoder {

    private OutputStream out;
    private int delayMs = 100;     // 每帧间隔（毫秒）
    private int repeat = 0;        // 循环次数，0 = 无限循环
    private int width, height;     // 帧尺寸（各帧应一致）
    private boolean firstFrame = true;

    /** 开始编码，指定输出流 */
    public void start(OutputStream out) {
        this.out = out;
    }

    /** 设置帧间隔毫秒数 */
    public void setDelay(int ms) {
        delayMs = ms;
    }

    /** 设置循环次数，0 表示无限循环 */
    public void setRepeat(int r) {
        repeat = r;
    }

    /** 添加一帧位图 */
    public void addFrame(Bitmap bmp) throws IOException {
        if (out == null) throw new IOException("编码器未启动");
        width = bmp.getWidth();
        height = bmp.getHeight();

        int[] pixels = new int[width * height];
        bmp.getPixels(pixels, 0, width, 0, 0, width, height);

        // 1. 统计颜色直方图，取前 256 色作为调色板
        HashMap<Integer, Integer> hist = new HashMap<Integer, Integer>();
        for (int px : pixels) {
            int key = px & 0xFFFFFF;
            Integer c = hist.get(key);
            hist.put(key, c == null ? 1 : c + 1);
        }
        List<Map.Entry<Integer, Integer>> list =
                new ArrayList<Map.Entry<Integer, Integer>>(hist.entrySet());
        Collections.sort(list, new Comparator<Map.Entry<Integer, Integer>>() {
            @Override
            public int compare(Map.Entry<Integer, Integer> a, Map.Entry<Integer, Integer> b) {
                return b.getValue().compareTo(a.getValue());
            }
        });
        int n = Math.min(list.size(), 256);
        int[] palette = new int[256];
        for (int i = 0; i < n; i++) palette[i] = list.get(i).getKey();
        // 补齐到 256 色（用黑色）
        for (int i = n; i < 256; i++) palette[i] = 0;

        // 2. 像素 → 调色板索引（5-5-5 桶缓存最近色查找）
        byte[] index = new byte[pixels.length];
        int[] cache = new int[1 << 15];
        Arrays.fill(cache, -1);
        for (int i = 0; i < pixels.length; i++) {
            int px = pixels[i] & 0xFFFFFF;
            int bucket = ((px >> 16 & 0xF8) << 7) | ((px >> 8 & 0xF8) << 2) | (px >> 3 & 0x07);
            int cached = cache[bucket];
            if (cached < 0) {
                cached = nearestIndex(palette, n, px);
                cache[bucket] = cached;
            }
            index[i] = (byte) cached;
        }

        // 3. 写出帧数据
        if (firstFrame) {
            writeHeader();
            writeLsd();
            writeNetscapeExt();
            firstFrame = false;
        }
        writeGraphicControl();
        writeImageDescriptor();
        writePalette(palette);
        writeLzw(index);
    }

    /** 结束编码，写出结束符 */
    public void finish() throws IOException {
        out.write(0x3B);
        out.flush();
    }

    // ---------- GIF 结构 ----------

    /** GIF89a 文件头 */
    private void writeHeader() throws IOException {
        out.write("GIF89a".getBytes("US-ASCII"));
    }

    /** 逻辑屏幕描述符（无全局色表，每帧自带局部色表） */
    private void writeLsd() throws IOException {
        writeShort(width);
        writeShort(height);
        out.write(0x00); // packed：无全局色表
        out.write(0x00); // 背景色
        out.write(0x00); // 像素纵横比
    }

    /** NETSCAPE2.0 循环扩展 */
    private void writeNetscapeExt() throws IOException {
        out.write(0x21);
        out.write(0xFF);
        out.write(0x0B);
        out.write("NETSCAPE2.0".getBytes("US-ASCII"));
        out.write(0x03);
        out.write(0x01);
        writeShort(repeat);
        out.write(0x00);
    }

    /** 图形控制扩展（帧间隔） */
    private void writeGraphicControl() throws IOException {
        out.write(0x21);
        out.write(0xF9);
        out.write(0x04);
        out.write(0x00); // packed：无透明、处置方式 0
        writeShort(delayMs / 10); // 单位 10ms
        out.write(0x00); // 透明色索引（未用）
        out.write(0x00);
    }

    /** 图像描述符（256 色局部色表） */
    private void writeImageDescriptor() throws IOException {
        out.write(0x2C);
        writeShort(0);
        writeShort(0);
        writeShort(width);
        writeShort(height);
        out.write(0x87); // 局部色表存在，大小 2^8=256
    }

    /** 局部色表（256 色 × RGB） */
    private void writePalette(int[] palette) throws IOException {
        for (int i = 0; i < 256; i++) {
            out.write(palette[i] >> 16 & 0xFF);
            out.write(palette[i] >> 8 & 0xFF);
            out.write(palette[i] & 0xFF);
        }
    }

    /** 小端写 16 位整数 */
    private void writeShort(int v) throws IOException {
        out.write(v & 0xFF);
        out.write(v >> 8 & 0xFF);
    }

    // ---------- LZW 压缩 ----------

    private int bitBuf;      // 位缓冲
    private int bitCount;    // 缓冲位数
    private byte[] subBlock = new byte[255];
    private int subLen;

    /** LZW 编码像素索引序列（8 位输入，码长 9-12 位动态增长） */
    private void writeLzw(byte[] index) throws IOException {
        // 最小码长 8（对应 256 色）
        int minCodeSize = 8;
        int clearCode = 1 << minCodeSize; // 256
        int eoiCode = clearCode + 1;      // 257
        int codeSize = minCodeSize + 1;   // 9
        int nextCode = eoiCode + 1;       // 258
        HashMap<Integer, Integer> dict = new HashMap<Integer, Integer>();

        bitBuf = 0;
        bitCount = 0;
        subLen = 0;

        out.write(minCodeSize);
        writeBits(clearCode, codeSize);

        int prefix = index[0] & 0xFF;
        for (int i = 1; i < index.length; i++) {
            int k = index[i] & 0xFF;
            Integer code = dict.get((prefix << 8) | k);
            if (code != null) {
                prefix = code;
                continue;
            }
            writeBits(prefix, codeSize);
            if (nextCode < 4096) {
                dict.put((prefix << 8) | k, nextCode);
                if (nextCode == (1 << codeSize) && codeSize < 12) {
                    codeSize++;
                }
                nextCode++;
            }
            if (nextCode >= 4096) {
                // 字典已满：发 clear 重置
                writeBits(clearCode, codeSize);
                dict.clear();
                codeSize = minCodeSize + 1;
                nextCode = eoiCode + 1;
            }
            prefix = k;
        }
        writeBits(prefix, codeSize);
        writeBits(eoiCode, codeSize);
        flushBits();
        flushSubBlock();
    }

    /** 低位优先写指定比特数的码 */
    private void writeBits(int code, int size) throws IOException {
        bitBuf |= code << bitCount;
        bitCount += size;
        while (bitCount >= 8) {
            writePixelByte(bitBuf & 0xFF);
            bitBuf >>>= 8;
            bitCount -= 8;
        }
    }

    private void flushBits() throws IOException {
        while (bitCount > 0) {
            writePixelByte(bitBuf & 0xFF);
            bitBuf >>>= 8;
            bitCount -= 8;
        }
    }

    /** 按 GIF 子块格式输出（每块最多 255 字节） */
    private void writePixelByte(int b) throws IOException {
        subBlock[subLen++] = (byte) b;
        if (subLen == 255) {
            out.write(255);
            out.write(subBlock, 0, 255);
            subLen = 0;
        }
    }

    private void flushSubBlock() throws IOException {
        if (subLen > 0) {
            out.write(subLen);
            out.write(subBlock, 0, subLen);
            subLen = 0;
        }
        out.write(0);
    }

    // ---------- 调色板查找 ----------

    /** 在调色板前 n 项中找最近色索引（RGB 欧氏距离） */
    private static int nearestIndex(int[] palette, int n, int px) {
        int r = px >> 16 & 0xFF, g = px >> 8 & 0xFF, b = px & 0xFF;
        int best = 0;
        int bestD = Integer.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            int c = palette[i];
            int dr = (c >> 16 & 0xFF) - r;
            int dg = (c >> 8 & 0xFF) - g;
            int db = (c & 0xFF) - b;
            int d = dr * dr + dg * dg + db * db;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }
}
