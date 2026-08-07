package com.han.wmsave;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 小红书解析器：
 * 1. 提取链接（支持 xiaohongshu.com/explore 与 xhslink.com 短链），保留 xsec_token
 * 2. 抓取笔记页 HTML，从 window.__INITIAL_STATE__ 提取作品数据
 * 3. 视频：masterUrl 去掉 wm_ 得到无水印直链；图集：优先取原图地址
 */
public class XhsParser {

    private static final Pattern URL_PAT = Pattern.compile(
            "https?://[\\w\\-./?%&=#~_:+@]*(xiaohongshu\\.com|xhslink\\.com)[\\w\\-./?%&=#~_:+@]*");
    private static final Pattern NOTE_ID_PAT = Pattern.compile(
            "/(?:explore|discovery/item|item)/([0-9a-f]{8,32})");
    private static final Pattern TOKEN_PAT = Pattern.compile(
            "xsec_token=([\\w%\\-]+)");

    /** 从分享文本提取小红书链接，找不到返回 null */
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
        if (url == null) throw new Exception("没有找到小红书链接，请复制完整的分享链接");

        // 短链先跟随重定向（xhslink.com），xsec_token 会在重定向后的 URL 里
        String finalUrl = PlatformParser.resolveUrl(url);

        // 提取笔记 ID
        Matcher idm = NOTE_ID_PAT.matcher(finalUrl);
        String noteId = idm.find() ? idm.group(1) : null;
        if (noteId == null) throw new Exception("无法识别笔记ID：" + finalUrl);

        // xsec_token 是访问页面的必要参数，必须来自分享链接
        Matcher tm = TOKEN_PAT.matcher(finalUrl);
        String token = tm.find() ? tm.group(1) : null;
        if (token == null) {
            throw new Exception("链接缺少访问凭证，请从小红书App重新复制分享链接");
        }

        // 抓取笔记页（xsec_source 为可选参数）
        String pageUrl = "https://www.xiaohongshu.com/explore/" + noteId
                + "?xsec_token=" + token + "&xsec_source=pc_share";
        String html = PlatformParser.httpGetText(pageUrl, pageUrl);

        // 从页面中提取 window.__INITIAL_STATE__ 的 JSON
        int idx = html.indexOf("__INITIAL_STATE__");
        if (idx < 0) throw new Exception("页面数据未找到，请稍后重试或从App重新复制链接");
        int start = html.indexOf('=', idx);
        int brace = html.indexOf('{', start);
        int end = brace < 0 ? -1 : PlatformParser.matchBrace(html, brace);
        if (brace < 0 || end < 0) throw new Exception("页面数据结构异常");
        JSONObject state = new JSONObject(html.substring(brace, end + 1));

        // note.noteDetailMap[noteId].note 为作品数据
        JSONObject noteObj = state.optJSONObject("note");
        JSONObject noteMap = noteObj == null ? null : noteObj.optJSONObject("noteDetailMap");
        JSONObject noteEntry = noteMap == null ? null : noteMap.optJSONObject(noteId);
        JSONObject note = noteEntry == null ? null : noteEntry.optJSONObject("note");
        if (note == null) throw new Exception("未找到作品信息，作品可能已删除");

        PlatformParser.Item item = new PlatformParser.Item();
        item.platform = "xiaohongshu";
        item.platformName = "小红书";
        item.id = noteId;
        item.desc = note.optString("title", "");
        JSONObject user = note.optJSONObject("user");
        if (user != null) item.nickname = user.optString("nickname", "");

        // 封面
        JSONObject cover = note.optJSONObject("cover");
        if (cover != null) item.coverUrl = cover.optString("url", "");
        // 封面为 webp 处理图，去掉 ! 后的处理参数更接近原图
        if (item.coverUrl.indexOf('!') > 0) {
            item.coverUrl = item.coverUrl.substring(0, item.coverUrl.indexOf('!'));
        }

        String type = note.optString("type", "normal");
        if ("video".equals(type)) {
            // 视频：h264 流 masterUrl 去掉 wm_ 前缀得到无水印地址
            JSONObject video = note.optJSONObject("video");
            JSONObject media = video == null ? null : video.optJSONObject("media");
            JSONObject stream = media == null ? null : media.optJSONObject("stream");
            JSONArray h264 = stream == null ? null : stream.optJSONArray("h264");
            if (h264 != null && h264.length() > 0) {
                String master = h264.optJSONObject(0).optString("masterUrl", "");
                if (!master.isEmpty()) {
                    item.videoUrl = master.replace("/wm_", "/");
                    item.videoUrlSd = master; // 兜底：原地址（带水印但保证能下载）
                    item.hasVideo = true;
                }
            }
            if (!item.hasVideo) throw new Exception("未获取到视频流，请稍后重试");
        } else {
            // 图集：优先 urlOrigin 原图，否则去掉 urlDefault 的 ! 处理参数
            JSONArray images = note.optJSONArray("imageList");
            if (images != null) {
                for (int i = 0; i < images.length(); i++) {
                    JSONObject img = images.optJSONObject(i);
                    if (img == null) continue;
                    String u = img.optString("urlOrigin", "");
                    if (u.isEmpty()) {
                        u = img.optString("urlDefault", "");
                        int bang = u.indexOf('!');
                        if (bang > 0) u = u.substring(0, bang);
                    }
                    if (!u.isEmpty()) item.imageUrls.add(u);
                }
            }
            if (item.imageUrls.isEmpty()) throw new Exception("未获取到图片数据");
        }
        return item;
    }
}
