package com.han.wmsave;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B站解析器：
 * 1. 提取链接（支持 bilibili.com/video/BVxxx 与 b23.tv 短链）
 * 2. view API 获取 cid / 标题 / 作者 / 封面
 * 3. playurl API 获取 mp4 直链（B站原视频本身无水印，直接可用）
 */
public class BiliParser {

    private static final Pattern URL_PAT = Pattern.compile(
            "https?://[\\w\\-./?%&=#~_:+@]*(bilibili\\.com|b23\\.tv)[\\w\\-./?%&=#~_:+@]*");
    private static final Pattern BV_PAT = Pattern.compile("BV[0-9A-Za-z]{10}");

    /** 从分享文本提取 B站链接，找不到返回 null */
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

    /** 解析分享文本，返回统一 Item */
    public static PlatformParser.Item parse(String text) throws Exception {
        String url = extractShareUrl(text);
        if (url == null) throw new Exception("没有找到B站链接，请复制完整的分享链接");

        // b23.tv 短链需要先跟随重定向
        String finalUrl = PlatformParser.resolveUrl(url);

        // 从最终 URL 中提取 BV 号
        String bvid = extractBvid(finalUrl);
        if (bvid == null) {
            // 兜底：短链重定向可能落到非标准页面，抓 HTML 从 og:url 提取
            String html = PlatformParser.httpGetText(finalUrl, "https://www.bilibili.com/");
            String ogUrl = extractOgUrl(html);
            bvid = ogUrl != null ? extractBvid(ogUrl) : null;
        }
        if (bvid == null) throw new Exception("无法识别B站视频ID：" + finalUrl);

        // 1. view API：作品信息（标题/作者/封面/cid）
        JSONObject view = new JSONObject(PlatformParser.httpGetText(
                "https://api.bilibili.com/x/web-interface/view?bvid=" + bvid,
                "https://www.bilibili.com/"));
        if (view.optInt("code") != 0) {
            throw new Exception("B站接口返回：" + view.optString("message", "未知错误"));
        }
        JSONObject vd = view.getJSONObject("data");
        long cid = vd.optLong("cid");
        JSONObject owner = vd.optJSONObject("owner");

        PlatformParser.Item item = new PlatformParser.Item();
        item.platform = "bilibili";
        item.platformName = "B站";
        item.id = bvid;
        item.referer = "https://www.bilibili.com/"; // CDN 防盗链需要
        item.desc = vd.optString("title", "");
        if (owner != null) item.nickname = owner.optString("name", "");
        item.coverUrl = vd.optString("pic", "");

        // 2. playurl API：mp4 直链（fnval=0 返回 durl 数组）
        JSONObject pu = new JSONObject(PlatformParser.httpGetText(
                "https://api.bilibili.com/x/player/playurl?bvid=" + bvid
                        + "&cid=" + cid + "&qn=80&fnval=0",
                "https://www.bilibili.com/"));
        if (pu.optInt("code") != 0) {
            throw new Exception("B站接口返回：" + pu.optString("message", "未知错误"));
        }
        JSONObject puData = pu.optJSONObject("data");
        JSONArray durls = puData == null ? null : puData.optJSONArray("durl");
        if (durls == null || durls.length() == 0) {
            throw new Exception("未获取到视频流，该视频可能为付费/大会员专属");
        }
        // 取文件体积最大的档位
        String bestUrl = "";
        long maxSize = -1;
        for (int i = 0; i < durls.length(); i++) {
            JSONObject d = durls.optJSONObject(i);
            if (d == null) continue;
            long size = d.optLong("size", 0);
            String u = d.optString("url", "");
            if (!u.isEmpty() && size > maxSize) {
                maxSize = size;
                bestUrl = u;
            }
        }
        if (bestUrl.isEmpty()) throw new Exception("未获取到有效的视频地址");
        item.videoUrl = bestUrl;
        item.videoUrlSd = bestUrl; // B站单档位，兜底同地址
        item.hasVideo = true;
        return item;
    }

    /** 从字符串中提取 BV 号，找不到返回 null */
    private static String extractBvid(String s) {
        if (s == null) return null;
        Matcher m = BV_PAT.matcher(s);
        return m.find() ? m.group() : null;
    }

    /** 从 HTML 中提取 og:url 属性值，找不到返回 null */
    private static String extractOgUrl(String html) {
        if (html == null) return null;
        Matcher m = Pattern.compile(
                "<meta[^>]*property=[\"']og:url[\"'][^>]*content=[\"']([^\"']+)")
                .matcher(html);
        if (m.find()) return m.group(1);
        // 属性顺序反过来再试一次
        Matcher m2 = Pattern.compile(
                "<meta[^>]*content=[\"']([^\"']+)[\"'][^>]*property=[\"']og:url[\"']")
                .matcher(html);
        return m2.find() ? m2.group(1) : null;
    }
}
