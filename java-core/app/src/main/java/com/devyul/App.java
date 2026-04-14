package com.devyul;

import org.kohsuke.github.*;
import okhttp3.*;
import com.google.gson.*;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

public class App {
    private static final String GITHUB_TOKEN = System.getenv("GITHUB_TOKEN");
    private static final String NOTION_TOKEN = System.getenv("NOTION_TOKEN");
    private static final String NOTION_DB_ID = System.getenv("NOTION_DB_ID");

    public static void main(String[] args) {
        try {
            GitHub github = new GitHubBuilder().withOAuthToken(GITHUB_TOKEN).build();
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
            List<CommitInfo> todayCommits = new ArrayList<>();

            System.out.println("🔍 오늘 날짜의 활동을 분석 중입니다...");

            for (GHRepository repo : github.getMyself().listRepositories()) {
                List<GHCommit> commits = repo.queryCommits()
                        .since(java.sql.Date.valueOf(today))
                        .list().toList();

                if (!commits.isEmpty()) {
                    List<String> messages = commits.stream()
                            .map(c -> {
                                try {
                                    return c.getCommitShortInfo().getMessage();
                                } catch (IOException e) {
                                    return "메시지 로드 실패";
                                }
                            })
                            .collect(Collectors.toList());

                    todayCommits.add(new CommitInfo(repo.getName(), messages));
                    System.out.println("✅ [" + repo.getName() + "]에서 " + messages.size() + "개의 커밋을 확인했습니다.");
                }
            }

            if (!todayCommits.isEmpty()) {
                sendToNotion(today.toString(), todayCommits);
            } else {
                System.out.println("⚠️ 오늘 기록된 커밋 활동이 없습니다.");
            }

        } catch (Exception e) {
            System.err.println("❌ 실행 중 에러 발생: " + e.getMessage());
        }
    }

    private static void sendToNotion(String date, List<CommitInfo> commitInfos) throws IOException {
        OkHttpClient client = new OkHttpClient();
        JsonObject json = new JsonObject();

        JsonObject parent = new JsonObject();
        parent.addProperty("database_id", NOTION_DB_ID);
        json.add("parent", parent);

        JsonObject props = new JsonObject();

        // 1. 제목 (날짜 정보)
        props.add("제목", createTitle(date + " 활동 보고"));

        // 2. 레포지토리 목록 (쉼표로 구분)
        List<String> repoNameList = commitInfos.stream()
                .map(i -> i.repoName)
                .collect(Collectors.toList());
        props.add("레포지토리", createMultiSelect(repoNameList, true, false));

        // 3. 커밋 메시지 (레포별로 구분하여 정리)
        String allMessages = commitInfos.stream()
                .map(i -> "[" + i.repoName + "]\n- " + String.join("\n- ", i.messages))
                .collect(Collectors.joining("\n\n"));
        props.add("커밋 메시지", createText(allMessages));

        // 4. 날짜 및 상태
        JsonObject dateProp = new JsonObject();
        JsonObject dateVal = new JsonObject();
        dateVal.addProperty("start", date);
        dateProp.add("date", dateVal);
        props.add("날짜", dateProp);

        JsonObject checkbox = new JsonObject();
        checkbox.addProperty("checkbox", true);
        props.add("상태", checkbox);

        json.add("properties", props);

        RequestBody body = RequestBody.create(
                new Gson().toJson(json),
                MediaType.get("application/json; charset=utf-8"));

        Request request = new Request.Builder()
                .url("https://api.notion.com/v1/pages")
                .addHeader("Authorization", "Bearer " + NOTION_TOKEN)
                .addHeader("Notion-Version", "2022-06-28")
                .post(body).build();

        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                System.out.println("✨ 노션 데이터베이스 업데이트 성공!");
            } else {
                System.err.println("❌ 전송 실패: " + response.body().string());
            }
        }
    }

    private static JsonObject createTitle(String text) {
        JsonObject wrapper = new JsonObject();
        JsonArray array = new JsonArray();
        JsonObject textObj = new JsonObject();
        JsonObject content = new JsonObject();
        content.addProperty("content", text);
        textObj.add("text", content);
        array.add(textObj);
        wrapper.add("title", array);
        return wrapper;
    }

    private static JsonObject createText(String text) {
        JsonObject wrapper = new JsonObject();
        JsonArray array = new JsonArray();
        JsonObject textObj = new JsonObject();
        JsonObject content = new JsonObject();
        content.addProperty("content", text);
        textObj.add("text", content);
        array.add(textObj);
        wrapper.add("rich_text", array);
        return wrapper;
    }

    private static JsonObject createMultiSelect(List<String> names, boolean allowMultiple, boolean allowCreate) {
        JsonObject wrapper = new JsonObject();
        JsonArray array = new JsonArray();
        for (String name : names) {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", name);
            array.add(obj);
        }
        wrapper.add("multi_select", array);
        return wrapper;
    }
}