package com.devyul.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class NotionClient {

    private static final String NOTION_TOKEN = System.getenv("NOTION_TOKEN");
    private static final String NOTION_DB_ID = System.getenv("NOTION_DB_ID");
    private static final OkHttpClient client = new OkHttpClient();
    private static final Gson gson = new Gson();

    private NotionClient() {
    }

    public static boolean sendToNotionWithDuplicateCheck(String date, String repoName, String commitMsg, String sha)
            throws IOException {
        if (NOTION_TOKEN == null || NOTION_DB_ID == null) {
            return false;
        }

        // 1. Check for duplicates
        String queryJson = "{\"filter\":{\"property\":\"커밋ID\",\"rich_text\":{\"equals\":\"" + sha + "\"}}}";
        RequestBody queryBody = RequestBody.create(queryJson, MediaType.get("application/json; charset=utf-8"));
        Request queryReq = new Request.Builder()
                .url("https://api.notion.com/v1/databases/" + NOTION_DB_ID + "/query")
                .addHeader("Authorization", "Bearer " + NOTION_TOKEN)
                .addHeader("Notion-Version", "2022-06-28")
                .post(queryBody)
                .build();

        try (Response response = client.newCall(queryReq).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Unexpected code " + response);
            }
            JsonObject resultJson = JsonParser.parseString(response.body().string()).getAsJsonObject();
            if (resultJson.getAsJsonArray("results").size() > 0) {
                return false; // Duplicate found
            }
        }

        // 2. Create new page if no duplicate
        JsonObject json = new JsonObject();
        json.add("parent", createObject("database_id", NOTION_DB_ID));

        JsonObject props = new JsonObject();
        String titleStr = commitMsg.split("\n")[0];
        props.add("제목", createTitle("[" + repoName + "] " + titleStr));
        props.add("레포지토리", createMultiSelect(Collections.singletonList(repoName)));
        props.add("커밋 메시지", createText(commitMsg));
        props.add("커밋ID", createText(sha));
        props.add("날짜", createDate(date));
        props.add("상태", createCheckbox(true));

        json.add("properties", props);
        RequestBody body = RequestBody.create(gson.toJson(json), MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url("https://api.notion.com/v1/pages")
                .addHeader("Authorization", "Bearer " + NOTION_TOKEN)
                .addHeader("Notion-Version", "2022-06-28")
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            return response.isSuccessful();
        }
    }

    // --- Notion JSON Helpers ---
    private static JsonObject createTitle(String text) {
        JsonObject w = new JsonObject();
        JsonArray a = new JsonArray();
        JsonObject t = new JsonObject();
        JsonObject c = new JsonObject();
        c.addProperty("content", text);
        t.add("text", c);
        a.add(t);
        w.add("title", a);
        return w;
    }

    private static JsonObject createText(String text) {
        JsonObject w = new JsonObject();
        JsonArray a = new JsonArray();
        JsonObject t = new JsonObject();
        JsonObject c = new JsonObject();
        c.addProperty("content", text);
        t.add("text", c);
        a.add(t);
        w.add("rich_text", a);
        return w;
    }

    private static JsonObject createMultiSelect(List<String> names) {
        JsonObject w = new JsonObject();
        JsonArray a = new JsonArray();
        for (String n : names) {
            JsonObject o = new JsonObject();
            o.addProperty("name", n);
            a.add(o);
        }
        w.add("multi_select", a);
        return w;
    }

    private static JsonObject createDate(String date) {
        JsonObject w = new JsonObject();
        JsonObject d = new JsonObject();
        d.addProperty("start", date);
        w.add("date", d);
        return w;
    }

    private static JsonObject createCheckbox(boolean checked) {
        JsonObject w = new JsonObject();
        w.addProperty("checkbox", checked);
        return w;
    }

    private static JsonObject createObject(String key, String val) {
        JsonObject o = new JsonObject();
        o.addProperty(key, val);
        return o;
    }
}
