package com.devyul.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

public class SlackClient {

    private static final String SLACK_WEBHOOK_URL = System.getenv("SLACK_WEBHOOK_URL");
    private static final OkHttpClient client = new OkHttpClient();
    private static final Gson gson = new Gson();

    // private constructor to prevent instantiation of this utility class
    private SlackClient() {
    }

    public static void sendToSlack(String text) {
        sendRichSlackMessage(text, null);
    }

    public static void sendRichSlackMessage(String mainText, JsonArray attachments) {
        if (SLACK_WEBHOOK_URL == null) {
            return;
        }
        try {
            JsonObject json = new JsonObject();
            if (mainText != null) {
                json.addProperty("text", mainText);
            }
            if (attachments != null) {
                json.add("attachments", attachments);
            }
            RequestBody body = RequestBody.create(gson.toJson(json),
                    MediaType.get("application/json; charset=utf-8"));
            Request request = new Request.Builder()
                    .url(SLACK_WEBHOOK_URL)
                    .post(body)
                    .build();

            client.newCall(request).execute().close();
        } catch (Exception ignored) {
            // The original code ignored the exception, so we do the same.
        }
    }
}
