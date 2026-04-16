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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import java.io.StringReader;

public class App {
    private static final String GITHUB_TOKEN = System.getenv("GITHUB_TOKEN");
    private static final String NOTION_TOKEN = System.getenv("NOTION_TOKEN");
    private static final String NOTION_DB_ID = System.getenv("NOTION_DB_ID");
    private static final String SLACK_WEBHOOK_URL = System.getenv("SLACK_WEBHOOK_URL");

    private static final String APPLICATION_NAME = "Jarvis-Yul-Automation";
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final List<String> SCOPES = Collections.singletonList(GmailScopes.GMAIL_READONLY);

    private static final String TOKENS_DIRECTORY = "tokens";

    public static void main(String[] args) {
        try {
            System.out.println("🚀 Jarvis-Yul 시스템 가동 준비...");

            // 토큰만 물리 파일로 복구합니다.
            if (!restoreToken()) {
                System.err.println("❌ 인증 토큰 복구 실패. 시스템을 종료합니다.");
                return;
            }

            // Gmail 서비스 실행 (파일 I/O 제거, 메모리 직접 로드)
            Gmail gmailService = getGmailService();
            if (gmailService != null) {
                System.out.println("📧 메일함을 확인하는 중...");
                checkNewEmails(gmailService);
            }

            // 📰 뉴스 브리핑 실행 (여기 추가!)
            getHeadlineNews();

            // GitHub & Notion 실행
            runDailyAutomation();

            System.out.println("🏁 모든 자동화 보고가 완료되었습니다.");

        } catch (Exception e) {
            System.err.println("❌ 시스템 실행 중 치명적 에러 발생!");
            e.printStackTrace();
        }
    }

    // ⚠️ 변경점 1: credentials.json 물리 파일을 아예 만들지 않습니다.
    private static boolean restoreToken() {
        try {
            System.out.println("🔐 [Security] 인증 토큰 파일 복구 시작...");
            String b64Token = System.getenv("GMAIL_TOKEN");
            if (b64Token != null && !b64Token.trim().isEmpty()) {
                File tokenDir = new File(TOKENS_DIRECTORY);
                if (!tokenDir.exists())
                    tokenDir.mkdirs();
                File tokenFile = new File(tokenDir, "StoredCredential");
                try (FileOutputStream fos = new FileOutputStream(tokenFile)) {
                    fos.write(Base64.getDecoder().decode(b64Token.trim()));
                    fos.flush();
                }
                System.out.println("✅ StoredCredential (토큰) 복구 완료!");
                return true;
            } else {
                System.err.println("⚠️ GMAIL_TOKEN 시크릿이 없습니다.");
                return false;
            }
        } catch (Exception e) {
            System.err.println("❌ 토큰 복구 중 에러: " + e.getMessage());
            return false;
        }
    }

    // ⚠️ 변경점 2: 인코딩 자동 감지 및 JSON 직접 파싱 (가장 강력한 보호 조치)
    private static Gmail getGmailService() throws Exception {
        String b64Creds = System.getenv("GMAIL_CREDENTIALS");
        if (b64Creds == null || b64Creds.trim().isEmpty()) {
            throw new Exception("❌ GMAIL_CREDENTIALS 시크릿이 존재하지 않습니다.");
        }

        byte[] decodedCreds = Base64.getDecoder().decode(b64Creds.trim());
        String jsonString;

        // PowerShell에서 넘어온 UTF-16LE, UTF-16BE 인코딩을 자동 감지하여 정상적인 문자열로 강제 복구
        if (decodedCreds.length >= 2 && decodedCreds[0] == (byte) 0xFF && decodedCreds[1] == (byte) 0xFE) {
            jsonString = new String(decodedCreds, StandardCharsets.UTF_16LE);
        } else if (decodedCreds.length >= 2 && decodedCreds[0] == (byte) 0xFE && decodedCreds[1] == (byte) 0xFF) {
            jsonString = new String(decodedCreds, StandardCharsets.UTF_16BE);
        } else {
            jsonString = new String(decodedCreds, StandardCharsets.UTF_8);
        }

        // 구글 JSON 파서를 미치게 만드는 숨겨진 BOM(\uFEFF) 문자 및 공백 완벽 제거
        jsonString = jsonString.replace("\uFEFF", "").trim();

        // 그럼에도 JSON이 아니라면, 정확히 어떤 쓰레기값이 들어갔는지 로그에 찍도록 명시
        if (!jsonString.startsWith("{")) {
            throw new Exception("❌ 해독된 문자열이 JSON 형식('{')으로 시작하지 않습니다. 첫 10글자: [" +
                    jsonString.substring(0, Math.min(10, jsonString.length())) + "]");
        }

        // 물리 파일 경로를 버리고, 깨끗하게 정제된 문자열(메모리)을 구글 인증 객체에 직접 주입합니다.
        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new StringReader(jsonString));

        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, clientSecrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(new java.io.File(TOKENS_DIRECTORY)))
                .setAccessType("offline")
                .build();

        Credential credential = new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize("user");
        return new Gmail.Builder(GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME).build();
    }

    // --- GitHub, Notion, Slack 로직 (이전과 동일) ---
    private static void runDailyAutomation() throws Exception {
        System.out.println("🔍 GitHub 활동 분석 중...");
        GitHub github = new GitHubBuilder().withOAuthToken(GITHUB_TOKEN).build();
        LocalDate displayDate = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate fetchDate = displayDate.minusDays(1);

        List<CommitInfo> todayCommits = new ArrayList<>();
        for (GHRepository repo : github.getMyself().listRepositories()) {
            List<GHCommit> commits = repo.queryCommits().since(java.sql.Date.valueOf(fetchDate)).list().toList();
            if (!commits.isEmpty()) {
                List<String> messages = commits.stream().map(c -> {
                    try {
                        return c.getCommitShortInfo().getMessage();
                    } catch (IOException e) {
                        return "로드 실패";
                    }
                }).collect(Collectors.toList());
                todayCommits.add(new CommitInfo(repo.getName(), messages));
            }
        }

        if (!todayCommits.isEmpty()) {
            sendToNotion(displayDate.toString(), todayCommits);
            sendToSlack(String.format("✅ [보고] 주인님, 오늘 %d개 레포에서 잔디를 심으셨습니다! 노션 박제 완료. 🚀", todayCommits.size()));
        } else {
            sendToSlack("🚨 [경고] 주인님, 오늘 아직 잔디가 비어있습니다! ㅡㅡ^");
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
        String allMessages = commitInfos.stream().map(i -> "[" + i.repoName + "]\n- " + String.join("\n- ", i.messages))
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
        Request request = new Request.Builder().url("https://api.notion.com/v1/pages")
                .addHeader("Authorization", "Bearer " + NOTION_TOKEN).addHeader("Notion-Version", "2022-06-28")
                .post(body).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful())
                System.err.println("❌ 노션 에러: " + response.body().string());
        }
    }

    private static void checkNewEmails(Gmail service) throws IOException {
        ListMessagesResponse response = service.users().messages().list("me").setQ("is:unread").setMaxResults(3L)
                .execute();
        List<Message> messages = response.getMessages();
        if (messages != null) {
            for (Message m : messages) {
                Message full = service.users().messages().get("me", m.getId()).execute();
                sendToSlack("📬 [메일 알림] " + full.getSnippet());
            }
        }
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
        } catch (Exception ignored) {
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

    private static void getHeadlineNews() {
        System.out.println("📰 구글 뉴스(RSS) 카테고리별 수집 중...");

        try {
            OkHttpClient client = new OkHttpClient();
            StringBuilder slackMessage = new StringBuilder();
            slackMessage.append("📰 *[자비스 모닝 종합 브리핑]*\n\n");

            // 구글 뉴스 전용 카테고리 코드
            Map<String, String> categories = new LinkedHashMap<>();
            categories.put("BUSINESS", "📈 경제/비즈니스");
            categories.put("TECHNOLOGY", "💻 IT/기술");
            categories.put("NATION", "🌐 국내/사회");
            categories.put("ENTERTAINMENT", "🎬 엔터테인먼트");

            int totalNewsCount = 0;

            // 자바 내장 XML 파서 세팅
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();

            for (Map.Entry<String, String> entry : categories.entrySet()) {
                String catCode = entry.getKey();
                String catName = entry.getValue();

                // 구글 뉴스 RSS URL (한국어, 한국 지역 설정)
                String url = "https://news.google.com/rss/headlines/section/topic/" + catCode
                        + "?hl=ko&gl=KR&ceid=KR:ko";
                Request request = new Request.Builder().url(url).build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        System.out.println("❌ [" + catName + "] 호출 실패: " + response.code());
                        continue;
                    }

                    String xmlString = response.body().string();
                    Document doc = builder.parse(new InputSource(new StringReader(xmlString)));
                    NodeList itemNodes = doc.getElementsByTagName("item");

                    if (itemNodes.getLength() > 0) {
                        slackMessage.append("*").append(catName).append("*\n");
                        // 상위 2개 뉴스만 추출
                        for (int i = 0; i < Math.min(5, itemNodes.getLength()); i++) {
                            Element item = (Element) itemNodes.item(i);
                            String title = item.getElementsByTagName("title").item(0).getTextContent();
                            String link = item.getElementsByTagName("link").item(0).getTextContent();

                            slackMessage.append("• <").append(link).append("|").append(title).append(">\n");
                            totalNewsCount++;
                        }
                        slackMessage.append("\n");
                    }
                }
            }

            if (totalNewsCount > 0) {
                sendToSlack(slackMessage.toString());
                System.out.println("✅ 구글 뉴스 총 " + totalNewsCount + "개 슬랙 전송 완료!");
            } else {
                System.out.println("⚠️ 오늘 수집된 뉴스가 전혀 없습니다.");
            }

        } catch (Exception e) {
            System.err.println("❌ 뉴스 수집 중 에러: " + e.getMessage());
        }
    }
}