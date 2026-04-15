package com.devyul;

import org.kohsuke.github.*;
import okhttp3.*;
import com.google.gson.*;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;

import java.io.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

public class App {
    private static final String GITHUB_TOKEN = System.getenv("GITHUB_TOKEN");
    private static final String NOTION_TOKEN = System.getenv("NOTION_TOKEN");
    private static final String NOTION_DB_ID = System.getenv("NOTION_DB_ID");
    private static final String SLACK_WEBHOOK_URL = System.getenv("SLACK_WEBHOOK_URL");

    // Google API 관련 상수
    private static final String APPLICATION_NAME = "Jarvis-Yul-Automation";
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final String TOKENS_DIRECTORY_PATH = "tokens";
    private static final List<String> SCOPES = Collections.singletonList(GmailScopes.GMAIL_READONLY);
    private static final String CREDENTIALS_FILE_PATH = "/credentials.json";

    public static void main(String[] args) {
        try {
            System.out.println("🚀 Jarvis-Yul 시스템 가동 중...");

            // --- Gmail 서비스 시작 ---
            System.out.println("📧 메일함을 확인하는 중...");
            Gmail gmailService = getGmailService();
            checkNewEmails(gmailService);
            // ------------------------------

            GitHub github = new GitHubBuilder().withOAuthToken(GITHUB_TOKEN).build();
            LocalDate displayDate = LocalDate.now(ZoneId.of("Asia/Seoul"));
            LocalDate fetchDate = displayDate.minusDays(1);
            ;
            List<CommitInfo> todayCommits = new ArrayList<>();

            System.out.println("🔍 오늘 날짜의 활동을 분석 중입니다...");

            for (GHRepository repo : github.getMyself().listRepositories()) {
                List<GHCommit> commits = repo.queryCommits()
                        .since(java.sql.Date.valueOf(fetchDate))
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
                // 1. 노션 기록
                sendToNotion(displayDate.toString(), todayCommits);

                // 2. 슬랙 성공 보고 (추가!)
                String successMsg = String.format(
                        "✅ [성공 보고] 주인님, 오늘 %d개의 잔디를 무사히 심으셨습니다! 노션에 예쁘게 박제해두었으니 확인해 보세요. 고생하셨습니다! 🚀",
                        todayCommits.size());
                sendToSlack(successMsg);
            } else {
                // 커밋이 없을 때 (기존 경고)
                System.out.println("⚠️ 활동 내역 없음. Jarvis-Yul이 슬랙으로 보고를 시작합니다.");
                sendToSlack("🚨 [긴급 보고] 주인님, 오늘 아직 잔디를 안 심으셨습니다! Jarvis-Yul이 지켜보고 있으니 서둘러 커밋 부탁드립니다. ㅡㅡ^");
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

    private static void sendToSlack(String message) throws IOException {
        if (SLACK_WEBHOOK_URL == null || SLACK_WEBHOOK_URL.isEmpty())
            return;

        OkHttpClient client = new OkHttpClient();
        JsonObject json = new JsonObject();
        json.addProperty("text", message);

        RequestBody body = RequestBody.create(
                new Gson().toJson(json),
                MediaType.get("application/json; charset=utf-8"));

        Request request = new Request.Builder()
                .url(SLACK_WEBHOOK_URL)
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                System.out.println("🔔 Jarvis-Yul이 슬랙 보고를 마쳤습니다.");
            }
        }
    }

    // --- [추가] Google API 인증 및 Gmail 연동 메서드 ---

    private static Gmail getGmailService() throws Exception {
        // 1. 서버(GitHub Actions) 환경인지 확인하고 토큰 복원
        String base64Token = System.getenv("GMAIL_TOKEN");
        if (base64Token != null && !base64Token.isEmpty()) {
            File tokenDir = new File(TOKENS_DIRECTORY_PATH);
            if (!tokenDir.exists())
                tokenDir.mkdirs();

            byte[] decodedBytes = Base64.getDecoder().decode(base64Token);
            try (FileOutputStream fos = new FileOutputStream(new File(tokenDir, "StoredCredential"))) {
                fos.write(decodedBytes);
            }
            System.out.println("🔑 서버 환경: 인증 토큰 복원 완료!");
        }

        // 2. credentials.json 로드 (기존 로직)
        InputStream in = App.class.getResourceAsStream(CREDENTIALS_FILE_PATH);
        if (in == null)
            throw new FileNotFoundException("Resource not found: " + CREDENTIALS_FILE_PATH);

        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));

        // 3. 인증 흐름 설정
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, clientSecrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(new java.io.File(TOKENS_DIRECTORY_PATH)))
                .setAccessType("offline")
                .build();

        // 서버에서는 여기서 브라우저를 띄우지 않고, 복원된 파일로 바로 승인을 마칩니다.
        Credential credential = new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize("user");

        return new Gmail.Builder(GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    private static void checkNewEmails(Gmail service) throws IOException {
        // 읽지 않은 메일 3건 조회
        ListMessagesResponse response = service.users().messages().list("me")
                .setQ("is:unread")
                .setMaxResults(3L)
                .execute();

        List<Message> messages = response.getMessages();
        if (messages != null && !messages.isEmpty()) {
            for (Message message : messages) {
                Message fullMessage = service.users().messages().get("me", message.getId()).execute();
                String snippet = fullMessage.getSnippet();

                System.out.println("📧 새 메일 발견: " + snippet);
                String slackMsg = "📬 [메일 알림] 주인님, 새 메일이 도착했습니다!\n> " + snippet;
                sendToSlack(slackMsg);
            }
        } else {
            System.out.println("✅ 확인된 새 메일이 없습니다.");
        }
    }
}