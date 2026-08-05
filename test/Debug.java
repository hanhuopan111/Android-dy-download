import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

import org.json.JSONObject;

public class Debug {
    public static void main(String[] a) throws Exception {
        String id = "6999605370222054663";
        String url = "https://www.iesdouyin.com/share/video/" + id + "/";
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
                        + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1");
        c.setRequestProperty("Referer", "https://www.iesdouyin.com/");
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        int code = c.getResponseCode();
        InputStream in = c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        c.disconnect();
        String html = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        System.out.println("HTTP " + code + ", length=" + html.length());
        System.out.println("有videoInfoRes: " + html.contains("videoInfoRes"));
        System.out.println("有item_list: " + html.contains("item_list"));
        System.out.println("有playwm: " + html.contains("playwm"));
        System.out.println("有_ROUTER_DATA: " + html.contains("_ROUTER_DATA"));
        int idx = html.indexOf("window._ROUTER_DATA");
        if (idx < 0) idx = html.indexOf("_ROUTER_DATA");
        System.out.println("marker at: " + idx);
        int start = html.indexOf('{', idx);
        System.out.println("first { at: " + start);
        String json = html.substring(start);
        int end = matchBrace(json, 0);
        System.out.println("brace end: " + end + ", json len=" + json.length());
        String jsonStr = json.substring(0, end + 1);
        JSONObject root = new JSONObject(jsonStr);
        Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            System.out.println("root key: [" + k + "] = " + (root.opt(k) instanceof JSONObject ? "JSONObject" : String.valueOf(root.opt(k))));
        }
        System.out.println("---html tail after brace end---");
        System.out.println(json.substring(end + 1, Math.min(end + 60, json.length())));
    }

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
}
