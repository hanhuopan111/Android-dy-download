import com.han.wmsave.Parser;

public class Test {
    public static void main(String[] a) throws Exception {
        String text = "2.82 wsr:/ Happy birthday to Kobe.#篮球 #曼巴精神 #科比生日 https://v.douyin.com/d8LpxMQ/ 复制佌鏈接，da鐦Dou音搜索，直接观看視频！";
        String url = Parser.extractShareUrl(text);
        System.out.println("提取链接 = " + url);
        String fin = Parser.resolveUrl(url);
        System.out.println("最终URL = " + fin);
        String id = Parser.extractAwemeId(fin);
        System.out.println("视频ID   = " + id);
        Parser.Item item = Parser.fetchItem(id);
        System.out.println("作者     = " + item.nickname);
        System.out.println("标题     = " + item.desc);
        System.out.println("视频地址 = " + item.videoUrl);
        System.out.println("封面地址 = " + item.coverUrl);
        System.out.println("图集数量 = " + item.imageUrls.size());
    }
}
