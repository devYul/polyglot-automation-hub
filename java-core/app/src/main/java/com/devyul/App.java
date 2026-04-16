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
    // 1. 환경 변수 로드
    private static final String GITHUB_TOKEN = System.getenv("GITHUB_TOKEN");
    private static final String NOTION_TOKEN = System.getenv("NOTION_TOKEN");
    private static final String NOTION_DB_ID = System.getenv("NOTION_DB_ID");
    private static final String SLACK_WEBHOOK_URL = System.getenv("SLACK_WEBHOOK_URL");

    // 2. Google API 설정
    private static final String APPLICATION_NAME = "Jarvis-Yul-Automation";
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final List<String> SCOPES = Collections.singletonList(GmailScopes.GMAIL_READONLY);

    // ⚠️ 서버 실행 환경(java-core 폴더 기준)에 맞춘 물리 경로
    private static final String CREDENTIALS_PATH = "src/main/resources/credentials.json";
    private static final String TOKENS_PATH = "tokens";

    /**
     * [최우선 과제] static 블록: 클래스가 로드되는 즉시 실행되어 파일을 복구합니다.
     * main 메서드보다 먼저 실행되므로 파일 부재로 인한 에러를 방지합니다.
     */
    static {
        try {
            System.out.println("🔐 [Security] 최우선 보안 파일 복구 공정 시작...");

            // (1) credentials.json 복구
            String b64Creds = System.getenv("GMAIL_CREDENTIALS");
            if (b64Creds != null && !b64Creds.isEmpty()) {
                File file = new File(CREDENTIALS_PATH);
                if (!file.getParentFile().exists())
                    file.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(Base64.getDecoder().decode(b64Creds.trim()));
                }
                System.out.println("✅ credentials.json 복구 완료: " + file.getAbsolutePath());
            }

            // (2) StoredCredential 복구
            String b64Token = System.getenv("GMAIL_TOKEN");
            if (b64Token != null && !b64Token.isEmpty()) {
                File tokenDir = new File(TOKENS_PATH);
                if (!tokenDir.exists())
                    tokenDir.mkdirs();
                File tokenFile = new File(tokenDir, "StoredCredential");
                try (FileOutputStream fos = new FileOutputStream(tokenFile)) {
                    fos.write(Base64.getDecoder().decode(b64Token.trim()));
                }
                System.out.println("✅ StoredCredential 복구 완료.");
            }
        } catch (Exception e) {
            System.err.println("⚠️ 초기 복구 중 주의사항: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        try {
            System.out.println("🚀 Jarvis-Yul 시스템 가동 중...");

            // 1. Gmail 모니터링
            System.out.println("📧 메일함을 확인하는 중...");
            Gmail gmailService = getGmailService();
            if (gmailService != null) {
                checkNewEmails(gmailService);
            }

            // 2. GitHub & Notion 자동화
            runDailyAutomation();

            System.out.println("🏁 모든 자동화 보고가 완료되었습니다.");

        } catch (Exception e) {
            System.err.println("❌ 시스템 실행 중 치명적 에러!");
            e.printStackTrace();
        }
    }

    // --- Gmail 관련 로직 ---
    private static Gmail getGmailService() throws Exception {
        File credFile = new File(CREDENTIALS_PATH);
        if (!credFile.exists())
            return null;

        try (InputStream in = new FileInputStream(credFile)) {
            GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));
            GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, clientSecrets, SCOPES)
                    .setDataStoreFactory(new FileDataStoreFactory(new java.io.File(TOKENS_PATH)))
                    .setAccessType("offline")
                    .build();

            Credential credential = new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver())
                    .authorize("user");
            return new Gmail.Builder(GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, credential)
                    .setApplicationName(APPLICATION_NAME).build();
        }
    }

    private static void checkNewEmails(Gmail service) throws IOException {
        ListMessagesResponse response = service.users().messages().list("me")
                .setQ("is:unread").setMaxResults(3L).execute();

        List<Message> messages = response.getMessages();
        if (messages != null) {
            for (Message m : messages) {
                Message full = service.users().messages().get("me", m.getId()).execute();
                String snippet = full.getSnippet();
                System.out.println("📬 새 메일 발견: " + snippet);
                sendToSlack("📬 [메일 알림] 주인님, 새 메일이 도착했습니다!\n> " + snippet);
            }
        }
    }

    // --- GitHub & Notion 관련 로직 ---
    private static void runDailyAutomation() throws Exception {
        System.out.println("🔍 GitHub 활동 분석 중...");
        GitHub github = new GitHubBuilder().withOAuthToken(GITHUB_TOKEN).build();
        LocalDate displayDate = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate fetchDate = displayDate.minusDays(1);

        List<CommitInfo> todayCommits = new ArrayList<>();
        // .values() 없이 직접 PagedIterable 순회
        for (GHRepository repo : github.getMyself().listRepositories()) {
            List<GHCommit> commits = repo.queryCommits()
                    .since(java.sql.Date.valueOf(fetchDate)).list().toList();

            if (!commits.isEmpty()) {
                List<String> messages = commits.stream().map(c -> {
                    try {
                        return c.getCommitShortInfo().getMessage();
                    } catch (IOException e) {
                        return "메시지 로드 실패";
                    }
                }).collect(Collectors.toList());
                todayCommits.add(new CommitInfo(repo.getName(), messages));
            }
        }

        if (!todayCommits.isEmpty()) {
            sendToNotion(displayDate.toString(), todayCommits);
            sendToSlack(String.format("✅ [보고] 주인님, 오늘 %d개 레포에서 잔디를 심으셨습니다! 노션 박제 완료. 🚀", todayCommits.size()));
        } else {
            sendToSlack("🚨 [경고] 주인님, 오늘 아직 잔디가 비어있습니다! Jarvis-Yul이 지켜보고 있습니다. ㅡㅡ^");
        }
    }

    private static void sendToNotion(String date, List<CommitInfo> commitInfos) throws IOException {
        OkHttpClient client = new OkHttpClient();
        JsonObject json = new JsonObject();
        JsonObject parent = new JsonObject();
        parent.addProperty("database_id", NOTION_DB_ID);
        json.add("parent", parent);

        JsonObject props = new JsonObject();
        // 주인님이 지정하신 컬럼명: 제목, 레포지토리, 날짜, 상태, 커밋 메시지
        props.add("제목", createTitle(date + " 활동 보고"));

        List<String> repoNames = commitInfos.stream().map(i -> i.repoName).collect(Collectors.toList());
        props.add("레포지토리", createMultiSelect(repoNames));

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

        RequestBody body = RequestBody.create(new Gson().toJson(json),
                MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url("https://api.notion.com/v1/pages")
                .addHeader("Authorization", "Bearer " + NOTION_TOKEN)
                .addHeader("Notion-Version", "2022-06-28")
                .post(body).build();

        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful())
                System.out.println("✨ 노션 업데이트 성공!");
            else
                System.err.println("❌ 노션 에러: " + response.body().string());
        }
    }

    // --- 유틸리티 및 Slack 로직 ---
    private static void sendToSlack(String message) {
        if (SLACK_WEBHOOK_URL == null)
            return;
        try {
            OkHttpClient client = new OkHttpClient();
            JsonObject json = new JsonObject();
            json.addProperty("text", message);
            RequestBody body = RequestBody.create(new Gson().toJson(json),
                    MediaType.get("application/json; charset=utf-8"));
            Request request = new Request.Builder().url(SLACK_WEBHOOK_URL).post(body).build();
            client.newCall(request).execute().close();
        } catch (Exception e) {
            System.err.println("Slack 전송 실패");
        }
    }

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

    private static class CommitInfo {
        String repoName;
        List<String> messages;

        CommitInfo(String r, List<String> m) {
            this.repoName = r;
            this.messages = m;
        }
    }
}