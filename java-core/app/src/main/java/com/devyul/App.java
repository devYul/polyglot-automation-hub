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
        String task = (args.length > 0) ? args[0].toUpperCase() : "ALL";
        try {
            System.out.println("🚀 Jarvis-Yul 시스템 가동 준비... (Task: " + task + ")");

            if (!restoreToken()) {
                System.out.println("⚠️ 로컬 환경 감지: 인증이 필요한 작업(메일, 깃허브)은 스킵합니다.");
            } else {
                Gmail gmailService = getGmailService();
                if (gmailService != null) {
                    checkNewEmails(gmailService);
                }
            }

            if (task.equals("NEWS") || task.equals("ALL")) {
                getHeadlineNews();
            }

            if (task.equals("STOCK") || task.equals("ALL")) {
                getStockMarket();
            }

            if (task.equals("COMMIT") || task.equals("ALL")) {
                runDailyAutomation();
            }

            System.out.println("🏁 [" + task + "] 임무가 완료되었습니다.");

        } catch (Exception e) {
            System.err.println("❌ 시스템 가동 중 치명적 에러 발생!");
            e.printStackTrace();
        }
    }

    // --- 주식 시황 모듈 ---
    public static void getStockMarket() {
        System.out.println("📈 주식 시황 데이터 수집 중...");
        Map<String, String> stockMap = new LinkedHashMap<>();
        stockMap.put("TSLA.O", "TSLA (테슬라)");
        stockMap.put("PLTR.O", "PLTR (팔란티어)");

        int successCount = 0;
        OkHttpClient client = new OkHttpClient();
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
            sendRichSlackMessage("📈 *[자비스 주식 스나이퍼 브리핑]*", attachments);
    }

    // --- 뉴스 브리핑 ---
    private static void getHeadlineNews() {
        try {
            OkHttpClient client = new OkHttpClient();
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
            sendToSlack(slackMessage.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // --- 🔐 GitHub 잔디 분석 (당일 기준 + 1커밋 1로우) ---
    private static void runDailyAutomation() {
        System.out.println("🔍 GitHub 활동 분석 중...");
        if (GITHUB_TOKEN == null || GITHUB_TOKEN.isEmpty())
            return;

        try {
            GitHub github = new GitHubBuilder().withOAuthToken(GITHUB_TOKEN).build();
            GHMyself myself = github.getMyself();
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

            // ⚠️ 수정: '오늘 00:00' 이후의 커밋만 가져오도록 엄격하게 제한
            Date sinceDate = java.sql.Date.valueOf(today);

            List<CommitInfo> todayCommits = new ArrayList<>();
            int totalCommitCount = 0;

            for (GHRepository repo : myself.listRepositories()) {
                List<GHCommit> commits = repo.queryCommits().since(sinceDate).list().toList();

                if (!commits.isEmpty()) {
                    List<String> messages = new ArrayList<>();
                    for (GHCommit commit : commits) {
                        String msg = commit.getCommitShortInfo().getMessage();
                        messages.add(msg);
                        totalCommitCount++;

                        // ⚠️ 수정: 커밋 한 개당 노션 로우 한 개 생성
                        sendToNotionSingle(today.toString(), repo.getName(), msg);
                    }
                    todayCommits.add(new CommitInfo(repo.getName(), messages));
                }
            }

            if (totalCommitCount > 0) {
                StringBuilder slackBody = new StringBuilder();
                for (CommitInfo info : todayCommits) {
                    slackBody.append("*[").append(info.repoName).append("]* (").append(info.messages.size())
                            .append("회)\n");
                    for (String msg : info.messages) {
                        slackBody.append("- ").append(msg).append("\n");
                    }
                    slackBody.append("\n");
                }

                String finalMsg = String.format("✅ *[실시간 잔디 보고]*\n주인님, 오늘 총 *%d회*의 커밋을 기록하셨습니다! 🚀\n\n%s",
                        totalCommitCount, slackBody.toString());
                sendToSlack(finalMsg);
            } else {
                System.out.println("🚨 오늘 기록된 잔디가 없습니다.");
            }
        } catch (Exception e) {
            System.err.println("❌ 잔디 검사 중 에러: " + e.getMessage());
        }
    }

    // --- 📔 Notion 전송 (1커밋 1로우 전용) ---
    private static void sendToNotionSingle(String date, String repoName, String commitMsg) throws IOException {
        if (NOTION_TOKEN == null || NOTION_DB_ID == null)
            return;
        OkHttpClient client = new OkHttpClient();
        JsonObject json = new JsonObject();
        JsonObject parent = new JsonObject();
        parent.addProperty("database_id", NOTION_DB_ID);
        json.add("parent", parent);

        JsonObject props = new JsonObject();

        // 1. 제목 (커밋 메시지 첫 줄 요약)
        String titleStr = commitMsg.split("\n")[0];
        props.add("제목", createTitle("[" + repoName + "] " + titleStr));

        // 2. 레포지토리 (Multi-select)
        props.add("레포지토리", createMultiSelect(Collections.singletonList(repoName)));

        // 3. 커밋 메시지 (전체 메시지)
        props.add("커밋 메시지", createText(commitMsg));

        // 4. 날짜
        JsonObject dateVal = new JsonObject();
        dateVal.addProperty("start", date);
        JsonObject dateProp = new JsonObject();
        dateProp.add("date", dateVal);
        props.add("날짜", dateProp);

        // 5. 상태
        JsonObject checkbox = new JsonObject();
        checkbox.addProperty("checkbox", true);
        props.add("상태", checkbox);

        json.add("properties", props);

        RequestBody body = RequestBody.create(new Gson().toJson(json),
                MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder().url("https://api.notion.com/v1/pages")
                .addHeader("Authorization", "Bearer " + NOTION_TOKEN)
                .addHeader("Notion-Version", "2022-06-28")
                .post(body).build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful())
                System.err.println("❌ 노션 에러: " + response.body().string());
        }
    }

    // --- 보안/전송 유틸리티 ---
    private static boolean restoreToken() {
        try {
            String b64 = System.getenv("GMAIL_TOKEN");
            if (b64 == null || b64.isEmpty())
                return false;
            File dir = new File(TOKENS_DIRECTORY);
            if (!dir.exists())
                dir.mkdirs();
            try (FileOutputStream fos = new FileOutputStream(new File(dir, "StoredCredential"))) {
                fos.write(Base64.getDecoder().decode(b64.trim()));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Gmail getGmailService() throws Exception {
        String b64 = System.getenv("GMAIL_CREDENTIALS");
        if (b64 == null || b64.isEmpty())
            return null;
        byte[] decoded = Base64.getDecoder().decode(b64.trim());
        String json = new String(decoded, StandardCharsets.UTF_8).replace("\uFEFF", "").trim();
        GoogleClientSecrets secrets = GoogleClientSecrets.load(JSON_FACTORY, new StringReader(json));
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, secrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(new java.io.File(TOKENS_DIRECTORY)))
                .setAccessType("offline").build();
        Credential credential = new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize("user");
        return new Gmail.Builder(GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME).build();
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

    private static void sendToSlack(String text) {
        sendRichSlackMessage(text, null);
    }

    private static void sendRichSlackMessage(String mainText, JsonArray attachments) {
        if (SLACK_WEBHOOK_URL == null)
            return;
        try {
            OkHttpClient client = new OkHttpClient();
            JsonObject json = new JsonObject();
            if (mainText != null)
                json.addProperty("text", mainText);
            if (attachments != null)
                json.add("attachments", attachments);
            RequestBody body = RequestBody.create(new Gson().toJson(json),
                    MediaType.get("application/json; charset=utf-8"));
            Request req = new Request.Builder().url(SLACK_WEBHOOK_URL).post(body).build();
            client.newCall(req).execute().close();
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
}