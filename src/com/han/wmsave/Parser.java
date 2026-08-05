package com.han.wmsave;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 抖音解析引擎：
 * 1. 从分享文本中提取链接
 * 2. 跟随短链接重定向得到最终页面，提取视频ID
 * 3. 抓取分享页 HTML，从 window._ROUTER_DATA 中提取视频/图集数据
 * 4. 把 playwm 水印地址替换为 play 得到无水印直链
 */
public class Parser {

    public static class Item {
        public String awemeId;
        public String desc = "";
        public String nickname = "";
        public String videoUrl = "";   // 无水印视频直链（最高画质档）
        public String videoUrlSd = ""; // 兜底：默认720p档
        public String coverUrl = "";   // 封面图
        public List<String> imageUrls = new ArrayList<String>();
        public boolean hasVideo = false;
    }

    private static final String UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
    private static final int TIMEOUT = 15000;

    private static final Pattern URL_PAT = Pattern.compile(
            "https?://[\\w\\-./?%&=#~_:+@]+douyin[\\w\\-./?%&=#~_:+@]*");
    private static final Pattern ID_PAT = Pattern.compile(
            "/(?:share/video|share/note|video|note)/(\\d{8,25})");

    /** 从分享口令文本中提取抖音链接，找不到返回 null */
    public static String extractShareUrl(String text) {
        if (text == null) return null;
        Matcher m = URL_PAT.matcher(text);
        if (m.find()) {
            String url = m.group();
            // 去掉结尾的标点符号
            url = url.replaceAll("[),.;，。！!】]+$", "");
            return url;
        }
        return null;
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

    /** 从最终 URL 中提取视频 ID，找不到返回 null */
    public static String extractAwemeId(String finalUrl) {
        Matcher m = ID_PAT.matcher(finalUrl);
        if (m.find()) return m.group(1);
        return null;
    }

    /** 抓取并解析作品数据 */
    public static Item fetchItem(String awemeId) throws Exception {
        // 统一用 iesdouyin 分享页抓取（无需登录，页面内嵌完整数据）
        String html = httpGetText(
                "https://www.iesdouyin.com/share/video/" + awemeId + "/",
                "https://www.iesdouyin.com/");
        if (html == null || html.isEmpty()) throw new Exception("页面为空，请检查网络");

        int idx = html.indexOf("_ROUTER_DATA");
        if (idx < 0) throw new Exception("页面数据未找到，作品可能已删除或需更新应用");

        // 直接定位 videoInfoRes 数据块，不依赖外层嵌套结构（页面结构会变）
        int vi = html.indexOf("\"videoInfoRes\"", idx);
        if (vi < 0) throw new Exception("页面数据未找到，作品可能已删除或需更新应用");
        int start = html.indexOf('{', vi);
        if (start < 0) throw new Exception("页面数据结构异常");
        int end = matchBrace(html, start);
        if (end < 0) throw new Exception("页面数据结构异常");

        JSONObject videoInfo = new JSONObject(html.substring(start, end + 1));
        JSONArray items = videoInfo.optJSONArray("item_list");
        if (items == null || items.length() == 0) {
            throw new Exception("未找到作品信息（可能为私密作品）");
        }

        JSONObject it = items.getJSONObject(0);
        Item item = new Item();
        item.awemeId = it.optString("aweme_id", awemeId);
        item.desc = it.optString("desc", "");
        JSONObject author = it.optJSONObject("author");
        if (author != null) item.nickname = author.optString("nickname", "");

        JSONObject video = it.optJSONObject("video");
        if (video != null) {
            item.hasVideo = true;
            String wmUrl = firstUrl(video.optJSONObject("play_addr"));
            item.videoUrl = toWatermarkFree(wmUrl);      // 最高画质
            item.videoUrlSd = wmUrl.replace("/playwm/", "/play/"); // 720p兜底
            item.coverUrl = firstUrl(video.optJSONObject("cover"));
            if (item.coverUrl.isEmpty()) item.coverUrl = firstUrl(video.optJSONObject("cover_thumb"));
        }

        JSONArray images = it.optJSONArray("images");
        if (images != null) {
            for (int i = 0; i < images.length(); i++) {
                JSONObject img = images.optJSONObject(i);
                String u = bestImageUrl(img);
                if (u != null && !u.isEmpty()) item.imageUrls.add(u);
            }
        }
        if (!item.hasVideo && item.imageUrls.isEmpty()) {
            throw new Exception("未找到视频或图片数据");
        }
        return item;
    }

    /**
     * playwm(带水印) 地址 → play(无水印) 地址，
     * 并把画质档位从默认 720p 提升到 4k（服务器会自动回退到该视频的最高可用画质）
     */
    static String toWatermarkFree(String url) {
        if (url == null || url.isEmpty()) return "";
        String play = url.replace("/playwm/", "/play/");
        return play.replaceAll("ratio=\\d+p", "ratio=4k");
    }

    /** 从 bit_rate 数组里挑码率最高的档位地址（通常画质最好） */
    private static String bestBitRateUrl(JSONObject video) {
        JSONArray br = video.optJSONArray("bit_rate");
        if (br == null || br.length() == 0) return "";
        String best = "";
        int maxBr = -1;
        for (int i = 0; i < br.length(); i++) {
            JSONObject entry = br.optJSONObject(i);
            if (entry == null) continue;
            int rate = entry.optInt("bit_rate", 0);
            String u = firstUrl(entry.optJSONObject("play_addr"));
            if (!u.isEmpty() && rate > maxBr) {
                maxBr = rate;
                best = u;
            }
        }
        return best;
    }

    private static String firstUrl(JSONObject o) {
        if (o == null) return "";
        JSONArray list = o.optJSONArray("url_list");
        if (list != null && list.length() > 0) return list.optString(0, "");
        return "";
    }

    /** 图集图片：优先选 jpg/jpeg 原图，否则取列表最后一张（通常是最大尺寸） */
    private static String bestImageUrl(JSONObject img) {
        if (img == null) return "";
        JSONArray list = img.optJSONArray("url_list");
        if (list == null || list.length() == 0) return "";
        String best = list.optString(0, "");
        for (int i = 0; i < list.length(); i++) {
            String u = list.optString(i, "");
            if (u.contains(".jpeg") || u.contains(".jpg")) return u;
            if (!u.isEmpty()) best = u;
        }
        return best;
    }

    /** 从 start 位置匹配与之配对的大括号，返回其下标；找不到返回 -1 */
    private static int matchBrace(String s, int start) {
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

    private static String httpGetText(String url, String referer) throws Exception {
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
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        return text;
    }
}
