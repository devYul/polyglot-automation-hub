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

    // ⚠️ [중요] 경로 설정: 서버(GitHub Actions) 실행 위치에 맞게 조정
    // java-core 폴더에서 실행되므로 app/src/... 경로를 사용합니다.
    private static final String CREDENTIALS_PATH = "app/src/main/resources/credentials.json";
    private static final String TOKENS_DIRECTORY = "app/tokens";

    public static void main(String[] args) {
        // [STEP 1] 최우선 작업: 보안 파일 물리적 복구
        restoreSecrets();

        try {
            System.out.println("🚀 Jarvis-Yul 시스템 가동 중...");

            // [STEP 2] Gmail 서비스 기동 (물리 파일 직접 참조)
            System.out.println("📧 메일함을 확인하는 중...");
            Gmail gmailService = getGmailService();
            if (gmailService != null) {
                checkNewEmails(gmailService);
            }

            // [STEP 3] GitHub 잔디 분석
            analyzeGitHubActivity();

        } catch (Exception e) {
            System.err.println("❌ 시스템 가동 중 치명적 에러 발생!");
            e.printStackTrace();
        }
    }

    private static void restoreSecrets() {
        try {
            System.out.println("🔐 [Security] 보안 파일 복구 공정 시작...");

            // 1. credentials.json 복구
            String b64Creds = System.getenv("GMAIL_CREDENTIALS");
            if (b64Creds != null && !b64Creds.isEmpty()) {
                File file = new File(CREDENTIALS_PATH);
                if (!file.getParentFile().exists())
                    file.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(Base64.getDecoder().decode(b64Creds.trim()));
                }
                System.out.println("✅ credentials.json 복구 성공 (" + file.getAbsolutePath() + ")");
            }

            // 2. StoredCredential 복구
            String b64Token = System.getenv("GMAIL_TOKEN");
            if (b64Token != null && !b64Token.isEmpty()) {
                File tokenDir = new File(TOKENS_DIRECTORY);
                if (!tokenDir.exists())
                    tokenDir.mkdirs();
                File tokenFile = new File(tokenDir, "StoredCredential");
                try (FileOutputStream fos = new FileOutputStream(tokenFile)) {
                    fos.write(Base64.getDecoder().decode(b64Token.trim()));
                }
                System.out.println("✅ 인증 토큰 복구 성공 (" + tokenFile.getAbsolutePath() + ")");
            }
        } catch (Exception e) {
            System.err.println("⚠️ 파일 복구 중 경고: " + e.getMessage());
        }
    }

    private static Gmail getGmailService() throws Exception {
        File credFile = new File(CREDENTIALS_PATH);

        // 리소스가 아닌 실제 파일 존재 여부 확인
        if (!credFile.exists() || credFile.length() == 0) {
            System.err.println("❌ credentials.json 파일이 비어있거나 찾을 수 없습니다.");
            return null;
        }

        // ⚠️ FileInputStream으로 실제 파일을 직접 읽어 파싱 에러 방지
        try (InputStream in = new FileInputStream(credFile)) {
            GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));

            GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, clientSecrets, SCOPES)
                    .setDataStoreFactory(new FileDataStoreFactory(new java.io.File(TOKENS_DIRECTORY)))
                    .setAccessType("offline")
                    .build();

            Credential credential = new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver())
                    .authorize("user");

            return new Gmail.Builder(GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, credential)
                    .setApplicationName(APPLICATION_NAME)
                    .build();
        }
    }

    private static void analyzeGitHubActivity() throws Exception {
        System.out.println("🔍 GitHub 활동 분석 중...");
        GitHub github = new GitHubBuilder().withOAuthToken(GITHUB_TOKEN).build();
        LocalDate displayDate = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate fetchDate = displayDate.minusDays(1);

        List<CommitInfo> todayCommits = new ArrayList<>();

        // .values() 제거한 정상 로직
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
                        }).collect(Collectors.toList());

                todayCommits.add(new CommitInfo(repo.getName(), messages));
                System.out.println("✅ [" + repo.getName() + "] 커밋 발견!");
            }
        }

        if (!todayCommits.isEmpty()) {
            sendToNotion(displayDate.toString(), todayCommits);
            sendToSlack("✅ [보고] 주인님, 오늘 " + todayCommits.size() + "개의 잔디를 심으셨습니다! 노션 업데이트 완료. 🚀");
        } else {
            sendToSlack("🚨 [경고] 주인님, 오늘 잔디가 비어있습니다! ㅡㅡ^");
        }
    }

    private static void checkNewEmails(Gmail service) throws IOException {
        ListMessagesResponse response = service.users().messages().list("me")
                .setQ("is:unread").setMaxResults(3L).execute();

        List<Message> messages = response.getMessages();
        if (messages != null) {
            for (Message m : messages) {
                Message full = service.users().messages().get("me", m.getId()).execute();
                sendToSlack("📬 [메일 알림] " + full.getSnippet());
            }
        }
    }

    private static void sendToNotion(String date, List<CommitInfo> commitInfos) throws IOException {
        OkHttpClient client = new OkHttpClient();
        JsonObject json = new JsonObject();
        JsonObject parent = new JsonObject();
        parent.addProperty("database_id", NOTION_DB_ID);
        json.add("parent", parent);

        JsonObject props = new JsonObject();
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

    // --- 유틸리티 메서드 (JSON 생성) ---
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

    private static class CommitInfo {
        String repoName;
        List<String> messages;

        CommitInfo(String r, List<String> m) {
            this.repoName = r;
            this.messages = m;
        }
    }
}