package com.han.wmsave;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 多平台解析路由 + 各解析器共享工具：
 * 1. 根据分享文本中的域名识别平台
 * 2. 分发到对应平台的解析器（抖音/B站/小红书）
 * 3. 提供所有解析器共用的 HTTP 请求工具
 */
public class PlatformParser {

    /** 解析结果统一结构（所有平台共用） */
    public static class Item {
        public String platform;       // 平台标识：douyin/bilibili/xiaohongshu
        public String platformName;   // 平台显示名：抖音/B站/小红书
        public String id;             // 作品ID
        public String desc = "";
        public String nickname = "";
        public String videoUrl = "";   // 无水印视频直链（最高画质档）
        public String videoUrlSd = ""; // 兜底画质直链
        public String coverUrl = "";   // 封面图
        public String referer = "";    // 下载时需要的 Referer（平台CDN防盗链用，空则不带）
        public List<String> imageUrls = new ArrayList<String>();
        public boolean hasVideo = false;
    }

    static final String UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
    static final int TIMEOUT = 15000;

    /** 识别平台并解析分享文本，返回统一 Item */
    public static Item parse(String text) throws Exception {
        if (text == null || text.trim().isEmpty()) {
            throw new Exception("请先粘贴分享链接");
        }
        if (text.contains("bilibili.com") || text.contains("b23.tv")) {
            return BiliParser.parse(text);
        }
        if (text.contains("xiaohongshu.com") || text.contains("xhslink.com")) {
            return XhsParser.parse(text);
        }
        if (text.contains("douyin.com")) {
            return Parser.parseDouyin(text);
        }
        throw new Exception("暂不支持该平台的链接，当前支持：抖音 / B站 / 小红书");
    }

    /** 跟随重定向，返回最终 URL */
    public static String resolveUrl(String shortUrl) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(shortUrl).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        c.setConnectTimeout(TIMEOUT);
        c.setReadTimeout(TIMEOUT);
        int code = c.getResponseCode();
        String finalUrl = c.getURL().toString();
        c.disconnect();
        if (code >= 400) throw new Exception("链接访问失败(HTTP " + code + ")");
        return finalUrl;
    }

    /** 抓取页面文本（自动跟随重定向、去BOM） */
    public static String httpGetText(String url, String referer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        if (referer != null) c.setRequestProperty("Referer", referer);
        c.setConnectTimeout(TIMEOUT);
        c.setReadTimeout(TIMEOUT);
        int code = c.getResponseCode();
        InputStream in = (code >= 400) ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        c.disconnect();
        String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        // 去掉 UTF-8 BOM
        if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);
        return text;
    }

    /** 从 start 位置匹配与之配对的大括号，返回其下标；找不到返回 -1 */
    public static int matchBrace(String s, int start) {
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = start; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (inStr) {
                if (esc) { esc = false; continue; }
                if (ch == '\\') { esc = true; continue; }
                if (ch == '"') inStr = false;
                continue;
            }
            if (ch == '"') { inStr = true; continue; }
            if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }
}
