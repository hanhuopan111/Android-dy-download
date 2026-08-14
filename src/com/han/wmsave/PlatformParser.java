package com.han.wmsave;

import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多平台解析路由 + 各解析器共享工具：
 * 1. 根据分享文本中的域名识别平台
 * 2. 分发到对应平台的解析器（抖音/B站/小红书）
 * 3. 提供所有解析器共用的 HTTP 请求工具：
 *    - 全局 Cookie 会话（抖音 2026.08 起要求带 ttwid 会话才返回分享页数据）
 *    - 自动求解抖音 WAF JS 挑战（sha256 工作量证明，与浏览器端逻辑一致）
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

    /** WAF 挑战页特征：挑战参数 cs 所在片段 */
    private static final Pattern WAF_CS_PAT =
            Pattern.compile("_wafchallengeid\",cs=\"([^\"]+)\"");

    /** 全局 Cookie 会话：抖音分享页需要带 ttwid 等会话 Cookie 才返回作品数据 */
    static {
        CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
    }

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

    /** 跟随重定向，返回最终 URL（自动处理 WAF 挑战） */
    public static String resolveUrl(String shortUrl) throws Exception {
        Response r = httpGet(shortUrl, null);
        if (r.status >= 400) throw new Exception("链接访问失败(HTTP " + r.status + ")");
        return r.finalUrl;
    }

    /** 抓取页面文本（自动跟随重定向、去BOM、带Cookie会话、自动过WAF挑战） */
    public static String httpGetText(String url, String referer) throws Exception {
        return httpGet(url, referer).body;
    }

    // ---------- 内部实现 ----------

    private static class Response {
        int status;
        String finalUrl;
        String body;
    }

    /** 发起 GET；遇到抖音 WAF 挑战页时求解并自动重试（最多 3 次） */
    private static Response httpGet(String url, String referer) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            Response r = rawGet(url, referer);
            if (!isWafChallenge(r.body)) return r;
            solveWafChallenge(r.body);
        }
        throw new Exception("访问过于频繁被拦截，请稍后再试");
    }

    private static boolean isWafChallenge(String html) {
        return html != null && html.contains("_wafchallengeid") && html.contains("Please wait");
    }

    /**
     * 求解抖音 WAF JS 挑战：找到最小整数 i 使 sha256(prefix || i) == expect，
     * 再把结果写入 _wafchallengeid Cookie（与浏览器端 b64tou8a/s256 逻辑一致）
     */
    private static void solveWafChallenge(String html) throws Exception {
        Matcher m = WAF_CS_PAT.matcher(html);
        if (!m.find()) throw new Exception("验证页解析失败");
        String cs = m.group(1);
        byte[] json = Base64.decode(padBase64(cs), Base64.DEFAULT);
        JSONObject c = new JSONObject(new String(json, StandardCharsets.UTF_8));
        JSONObject v = c.getJSONObject("v");
        byte[] prefix = Base64.decode(padBase64(v.getString("a")), Base64.DEFAULT);
        byte[] expect = Base64.decode(padBase64(v.getString("c")), Base64.DEFAULT);

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] numBuf = new byte[10];
        for (int i = 0; i <= 1000000; i++) {
            md.reset();
            md.update(prefix);
            int len = numberBytes(i, numBuf);
            md.update(numBuf, 0, len);
            if (MessageDigest.isEqual(md.digest(), expect)) {
                // 与浏览器端相同：d = base64("i")，cookie = base64(JSON)
                c.put("d", Base64.encodeToString(
                        String.valueOf(i).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
                String cookieValue = Base64.encodeToString(
                        c.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                CookieManager cm = (CookieManager) CookieHandler.getDefault();
                cm.getCookieStore().add(
                        URI.create("https://www.iesdouyin.com"),
                        new HttpCookie("_wafchallengeid", cookieValue));
                return;
            }
        }
        throw new Exception("验证挑战求解失败");
    }

    /** 整数转十进制字节（写入 buf，返回长度），避免循环内反复分配字符串 */
    private static int numberBytes(int n, byte[] buf) {
        if (n == 0) { buf[0] = '0'; return 1; }
        int len = 0;
        for (int t = n; t > 0; t /= 10) len++;
        int p = len;
        for (int t = n; t > 0; t /= 10) buf[--p] = (byte) ('0' + t % 10);
        return len;
    }

    /** JS atob 容忍缺省填充，补齐 '=' 供 Base64 解码 */
    private static String padBase64(String s) {
        int pad = (4 - s.length() % 4) % 4;
        StringBuilder sb = new StringBuilder(s);
        for (int i = 0; i < pad; i++) sb.append('=');
        return sb.toString();
    }

    /** 原始 GET（不处理挑战），跟随重定向并读回响应体 */
    private static Response rawGet(String url, String referer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        if (referer != null) c.setRequestProperty("Referer", referer);
        c.setConnectTimeout(TIMEOUT);
        c.setReadTimeout(TIMEOUT);
        int code = c.getResponseCode();
        InputStream in = (code >= 400) ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
        }
        String finalUrl = c.getURL().toString();
        c.disconnect();
        String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        // 去掉 UTF-8 BOM
        if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);
        Response r = new Response();
        r.status = code;
        r.finalUrl = finalUrl;
        r.body = text;
        return r;
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
