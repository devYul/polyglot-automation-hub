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
    // 환경 변수들
    private static final String GITHUB_TOKEN = System.getenv("GITHUB_TOKEN");
    private static final String NOTION_TOKEN = System.getenv("NOTION_TOKEN");
    private static final String NOTION_DB_ID = System.getenv("NOTION_DB_ID");
    private static final String SLACK_WEBHOOK_URL = System.getenv("SLACK_WEBHOOK_URL");

    // Google API 관련 설정
    private static final String APPLICATION_NAME = "Jarvis-Yul-Automation";
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final String TOKENS_DIRECTORY_PATH = "tokens";
    private static final List<String> SCOPES = Collections.singletonList(GmailScopes.GMAIL_READONLY);
    private static final String CREDENTIALS_SAVE_PATH = "src/main/resources/credentials.json";

    public static void main(String[] args) {
        // 1. 최우선 과제: 서버 환경 변수로부터 보안 파일 복구
        restoreSecrets();

        try {
            System.out.println("🚀 Jarvis-Yul 시스템 가동 중...");

            // 2. Gmail 모니터링 (슬랙 알림 포함)
            System.out.println("📧 메일함을 확인하는 중...");
            Gmail gmailService = getGmailService();
            checkNewEmails(gmailService);

            // 3. GitHub 잔디 분석
            System.out.println("🔍 오늘 날짜의 활동을 분석 중입니다...");
            GitHub github = new GitHubBuilder().withOAuthToken(GITHUB_TOKEN).build();
            LocalDate displayDate = LocalDate.now(ZoneId.of("Asia/Seoul"));
            LocalDate fetchDate = displayDate.minusDays(1); // 전날부터의 커밋 조회

            List<CommitInfo> todayCommits = new ArrayList<>();

            for (GHRepository repo : github.getMyself().listRepositories().values()) {
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

            // 4. 결과 보고 (Notion 기록 및 Slack 전송)
            if (!todayCommits.isEmpty()) {
                // 노션 기록
                sendToNotion(displayDate.toString(), todayCommits);

                // 슬랙 성공 보고
                String successMsg = String.format(
                        "✅ [성공 보고] 주인님, 오늘 %d개 레포에서 잔디를 무사히 심으셨습니다! 노션에 예쁘게 박제해두었으니 확인해 보세요. 고생하셨습니다! 🚀",
                        todayCommits.size());
                sendToSlack(successMsg);
            } else {
                System.out.println("⚠️ 활동 내역 없음. Jarvis-Yul이 슬랙 경고를 시작합니다.");
                sendToSlack("🚨 [긴급 보고] 주인님, 오늘 아직 잔디를 안 심으셨습니다! Jarvis-Yul이 지켜보고 있으니 서둘러 커밋 부탁드립니다. ㅡㅡ^");
            }

            System.out.println("🏁 모든 자동화 공정을 성공적으로 마쳤습니다.");

        } catch (Exception e) {
            System.err.println("❌ 실행 중 치명적 에러 발생: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // --- 노션 관련 로직 ---
    private static void sendToNotion(String date, List<CommitInfo> commitInfos) throws IOException {
        OkHttpClient client = new OkHttpClient();
        JsonObject json = new JsonObject();

        JsonObject parent = new JsonObject();
        parent.addProperty("database_id", NOTION_DB_ID);
        json.add("parent", parent);

        JsonObject props = new JsonObject();
        props.add("제목", createTitle(date + " 활동 보고"));

        List<String> repoNameList = commitInfos.stream().map(i -> i.repoName).collect(Collectors.toList());
        props.add("레포지토리", createMultiSelect(repoNameList));

        String allMessages = commitInfos.stream()
                .map(i -> "[" + i.repoName + "]\n- " + String.join("\n- ", i.messages))
                .collect(Collectors.joining("\n\n"));
        props.add("커밋 메시지", createText(allMessages));

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
                System.err.println("❌ 노션 전송 실패: " + response.body().string());
            }
        }
    }

    // --- Gmail 관련 로직 ---
    private static Gmail getGmailService() throws Exception {
        // 복구된 파일을 직접 읽기
        File credFile = new File(CREDENTIALS_SAVE_PATH);
        if (!credFile.exists()) {
            throw new FileNotFoundException("열쇠(credentials.json)가 없습니다. 복구 로직을 확인하세요.");
        }

        try (InputStream in = new FileInputStream(credFile)) {
            GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));

            GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, clientSecrets, SCOPES)
                    .setDataStoreFactory(new FileDataStoreFactory(new java.io.File(TOKENS_DIRECTORY_PATH)))
                    .setAccessType("offline")
                    .build();

            Credential credential = new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver())
                    .authorize("user");

            return new Gmail.Builder(GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, credential)
                    .setApplicationName(APPLICATION_NAME)
                    .build();
        }
    }

    private static void checkNewEmails(Gmail service) throws IOException {
        ListMessagesResponse response = service.users().messages().list("me")
                .setQ("is:unread")
                .setMaxResults(3L)
                .execute();

        List<Message> messages = response.getMessages();
        if (messages != null && !messages.isEmpty()) {
            for (Message message : messages) {
                Message fullMessage = service.users().messages().get("me", message.getId()).execute();
                String snippet = fullMessage.getSnippet();
                System.out.println("📬 새 메일 발견: " + snippet);
                sendToSlack("📬 [메일 알림] 주인님, 새 메일이 도착했습니다!\n> " + snippet);
            }
        } else {
            System.out.println("✅ 새로운 메일이 없습니다.");
        }
    }

    // --- 보안 파일 복구 로직 ---
    private static void restoreSecrets() {
        try {
            System.out.println("🔐 보안 파일(Credentials/Token) 복구 공정 시작...");

            // 1. credentials.json 복구
            String b64Creds = System.getenv("GMAIL_CREDENTIALS");
            if (b64Creds != null && !b64Creds.isEmpty()) {
                File file = new File(CREDENTIALS_SAVE_PATH);
                if (!file.getParentFile().exists())
                    file.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(Base64.getDecoder().decode(b64Creds.trim()));
                }
                System.out.println("✅ credentials.json 복구 완료!");
            }

            // 2. StoredCredential 복구
            String b64Token = System.getenv("GMAIL_TOKEN");
            if (b64Token != null && !b64Token.isEmpty()) {
                File tokenDir = new File(TOKENS_DIRECTORY_PATH);
                if (!tokenDir.exists())
                    tokenDir.mkdirs();
                try (FileOutputStream fos = new FileOutputStream(new File(tokenDir, "StoredCredential"))) {
                    fos.write(Base64.getDecoder().decode(b64Token.trim()));
                }
                System.out.println("✅ StoredCredential 복구 완료!");
            }
        } catch (Exception e) {
            System.err.println("⚠️ 복구 중 경고: " + e.getMessage());
        }
    }

    // --- 슬랙 전송 로직 ---
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
                System.out.println("🔔 슬랙 보고 완료.");
            }
        }
    }

    // --- 유틸리티 메서드들 ---
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

    private static JsonObject createMultiSelect(List<String> names) {
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

    // --- 데이터 모델 클래스 ---
    private static class CommitInfo {
        String repoName;
        List<String> messages;

        CommitInfo(String repoName, List<String> messages) {
            this.repoName = repoName;
            this.messages = messages;
        }
    }
}