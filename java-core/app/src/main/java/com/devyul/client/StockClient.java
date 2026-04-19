package com.devyul.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.LinkedHashMap;
import java.util.Map;

public class StockClient {

    private static final OkHttpClient client = new OkHttpClient();

    private StockClient() {
    }

    public static void sendStockBriefing() {
        System.out.println("📈 주식 시황 데이터 수집 중...");
        Map<String, String> stockMap = new LinkedHashMap<>();
        stockMap.put("TSLA.O", "TSLA (테슬라)");
        stockMap.put("PLTR.O", "PLTR (팔란티어)");

        int successCount = 0;
        JsonArray attachments = new JsonArray();

        for (Map.Entry<String, String> entry : stockMap.entrySet()) {
            String apiTicker = entry.getKey();
            String displayName = entry.getValue();
            String baseTicker = apiTicker.split("\\.")[0];
            String url = "https://api.stock.naver.com/stock/" + apiTicker + "/basic";

            try {
                Request request = new Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build();
                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null)
                        throw new Exception("HTTP " + response.code());
                    String jsonResponse = response.body().string();
                    JsonObject jsonObject = JsonParser.parseString(jsonResponse).getAsJsonObject();

                    String price = jsonObject.get("closePrice").getAsString();
                    String changeAmt = jsonObject.get("compareToPreviousClosePrice").getAsString();
                    String changePct = jsonObject.get("fluctuationsRatio").getAsString();

                    String directionIcon = (changePct.startsWith("-")) ? "🔵" : "🔴";
                    String hexColor = (changePct.startsWith("-")) ? "#007AFF" : "#FF3B30";

                    JsonObject attachment = new JsonObject();
                    attachment.addProperty("color", hexColor);
                    attachment.addProperty("title", displayName);
                    attachment.addProperty("title_link",
                            "https://finance.naver.com/world/item/main.naver?symbol=" + apiTicker);
                    attachment.addProperty("text", "• 현재가: *$" + price + "*\n• 등락: " + directionIcon + " " + changeAmt
                            + " (" + changePct + "%)");
                    attachment.addProperty("image_url",
                            "https://finviz.com/chart.ashx?t=" + baseTicker + "&ty=c&ta=1&p=d&s=l");
                    attachments.add(attachment);
                    successCount++;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (successCount > 0)
            SlackClient.sendRichSlackMessage("📈 *[자비스 주식 스나이퍼 브리핑]*", attachments);
    }
}
