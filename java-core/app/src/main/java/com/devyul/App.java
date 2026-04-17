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
import java.time.*;
import java.time.format.DateTimeFormatter;
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
                System.out.println("⚠️ 로컬 환경 감지: 인증이 필요한 작업은 스킵합니다.");
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

    // --- 뉴스 RSS 브리핑 ---
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

    // --- 🔐 GitHub 활동 분석 (당일/시간순/중복체크) ---
    private static void runDailyAutomation() {
        System.out.println("🔍 GitHub 활동 분석 및 KST 시간순 정렬 중...");
        if (GITHUB_TOKEN == null || GITHUB_TOKEN.isEmpty())
            return;

        try {
            GitHub github = new GitHubBuilder().withOAuthToken(GITHUB_TOKEN).build();
            GHMyself myself = github.getMyself();

            // KST 오늘 00:00:00 기준 설정
            ZonedDateTime kstStart = LocalDate.now(ZoneId.of("Asia/Seoul")).atStartOfDay(ZoneId.of("Asia/Seoul"));
            Date sinceDate = Date.from(kstStart.toInstant());

            List<RawCommitData> allRawCommits = new ArrayList<>();

            for (GHRepository repo : myself.listRepositories()) {
                List<GHCommit> commits = repo.queryCommits().since(sinceDate).list().toList();
                for (GHCommit commit : commits) {
                    allRawCommits.add(new RawCommitData(
                            repo.getName(),
                            commit.getCommitShortInfo().getMessage(),
                            commit.getSHA1(),
                            commit.getCommitShortInfo().getAuthoredDate()));
                }
            }

            // 오래된 순으로 정렬
            allRawCommits.sort(Comparator.comparing(RawCommitData::getAuthoredDate));

            int totalNewCount = 0;
            Map<String, List<String>> summaryMap = new LinkedHashMap<>();

            for (RawCommitData raw : allRawCommits) {
                if (sendToNotionWithDuplicateCheck(kstStart.toLocalDate().toString(), raw.repoName, raw.message,
                        raw.sha)) {
                    totalNewCount++;
                    summaryMap.computeIfAbsent(raw.repoName, k -> new ArrayList<>()).add(raw.message);
                }
            }

            if (totalNewCount > 0) {
                StringBuilder slackBody = new StringBuilder();
                summaryMap.forEach((repo, msgs) -> {
                    slackBody.append("*[").append(repo).append("]* (").append(msgs.size()).append("회)\n");
                    msgs.forEach(m -> slackBody.append("- ").append(m).append("\n"));
                    slackBody.append("\n");
                });
                String finalMsg = String.format("✅ *[실시간 잔디 보고]*\n주인님, 오늘 새롭게 *%d회*의 커밋이 기록되었습니다! 🚀\n\n%s",
                        totalNewCount, slackBody.toString());
                sendToSlack(finalMsg);
            } else {
                System.out.println("🚨 새로 추가할 잔디가 없습니다.");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // --- 📔 Notion 중복 체크 및 1커밋 1로우 전송 ---
    private static boolean sendToNotionWithDuplicateCheck(String date, String repoName, String commitMsg, String sha)
            throws IOException {
        if (NOTION_TOKEN == null || NOTION_DB_ID == null)
            return false;
        OkHttpClient client = new OkHttpClient();

        // 중복 체크 쿼리
        String queryJson = "{\"filter\":{\"property\":\"커밋ID\",\"rich_text\":{\"equals\":\"" + sha + "\"}}}";
        RequestBody queryBody = RequestBody.create(queryJson, MediaType.get("application/json; charset=utf-8"));
        Request queryReq = new Request.Builder()
                .url("https://api.notion.com/v1/databases/" + NOTION_DB_ID + "/query")
                .addHeader("Authorization", "Bearer " + NOTION_TOKEN)
                .addHeader("Notion-Version", "2022-06-28").post(queryBody).build();

        try (Response response = client.newCall(queryReq).execute()) {
            JsonObject resultJson = JsonParser.parseString(response.body().string()).getAsJsonObject();
            if (resultJson.getAsJsonArray("results").size() > 0)
                return false;
        }

        // 데이터 입력
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
        RequestBody body = RequestBody.create(new Gson().toJson(json),
                MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder().url("https://api.notion.com/v1/pages")
                .addHeader("Authorization", "Bearer " + NOTION_TOKEN)
                .addHeader("Notion-Version", "2022-06-28").post(body).build();

        try (Response response = client.newCall(request).execute()) {
            return response.isSuccessful();
        }
    }

    // --- 유틸리티 및 보안 로직 ---
    private static boolean restoreToken() {
        try {
            String b64 = System.getenv("GMAIL_TOKEN");
            if (b64 == null)
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
        if (b64 == null)
            return null;
        byte[] decoded = Base64.getDecoder().decode(b64.trim());
        GoogleClientSecrets secrets = GoogleClientSecrets.load(JSON_FACTORY, new StringReader(new String(decoded)));
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, secrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(new java.io.File(TOKENS_DIRECTORY))).build();
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
            client.newCall(new Request.Builder().url(SLACK_WEBHOOK_URL).post(body).build()).execute().close();
        } catch (Exception ignored) {
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

    private static class RawCommitData {
        String repoName;
        String message;
        String sha;
        Date authoredDate;

        RawCommitData(String r, String m, String s, Date d) {
            this.repoName = r;
            this.message = m;
            this.sha = s;
            this.authoredDate = d;
        }

        public Date getAuthoredDate() {
            return authoredDate;
        }
    }
}