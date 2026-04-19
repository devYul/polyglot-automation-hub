package com.devyul.client;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;
import com.devyul.client.SlackClient;

public class NewsClient {

    private static final OkHttpClient client = new OkHttpClient();

    private NewsClient() {
    }

    public static void sendNewsBriefing() {
        try {
            StringBuilder slackMessage = new StringBuilder("📰 *[자비스 모닝 종합 브리핑]*\n\n");
            Map<String, String> categories = new LinkedHashMap<>();
            categories.put("BUSINESS", "📈 경제");
            categories.put("TECHNOLOGY", "💻 IT");
            categories.put("NATION", "🇰🇷 국내");
            categories.put("WORLD", "🌍 국제");

            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            for (Map.Entry<String, String> entry : categories.entrySet()) {
                String url = "https://news.google.com/rss/headlines/section/topic/" + entry.getKey()
                        + "?hl=ko&gl=KR&ceid=KR:ko";
                Request request = new Request.Builder().url(url).build();
                try (Response response = client.newCall(request).execute()) {
                    Document doc = builder.parse(new InputSource(new StringReader(response.body().string())));
                    NodeList items = doc.getElementsByTagName("item");
                    if (items.getLength() > 0) {
                        slackMessage.append("*").append(entry.getValue()).append("*\n");
                        for (int i = 0; i < Math.min(2, items.getLength()); i++) {
                            Element item = (Element) items.item(i);
                            String title = item.getElementsByTagName("title").item(0).getTextContent();
                            String link = item.getElementsByTagName("link").item(0).getTextContent();
                            slackMessage.append("• <").append(link).append("|").append(title).append(">\n");
                        }
                        slackMessage.append("\n");
                    }
                }
            }
            SlackClient.sendToSlack(slackMessage.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
