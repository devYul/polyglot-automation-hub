package com.devyul.client;

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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

public class GmailClient {

    private static final String APPLICATION_NAME = "Jarvis-Yul-Automation";
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final List<String> SCOPES = Collections.singletonList(GmailScopes.GMAIL_READONLY);
    private static final String TOKENS_DIRECTORY_NAME = "tokens";
    private static final String CREDENTIALS_FILE_NAME = "credentials.json";

    private GmailClient() {
    }

    public static void sendUnreadMailBriefing() {
        try {
            if (!restoreToken()) {
                System.out.println("ℹ️ Gmail 토큰 파일이 없거나 환경 변수가 설정되지 않았습니다.");
            }
            Gmail service = getGmailService();
            if (service == null) {
                System.out.println("⚠️ Gmail 서비스 생성 실패: credentials.json 파일을 찾을 수 없습니다.");
                return;
            }

            ListMessagesResponse response = service.users().messages().list("me").setQ("is:unread").setMaxResults(3L)
                    .execute();
            List<Message> messages = response.getMessages();
            if (messages != null && !messages.isEmpty()) {
                for (Message m : messages) {
                    Message fullMessage = service.users().messages().get("me", m.getId()).execute();
                    SlackClient.sendToSlack("📬 [메일 알림] " + fullMessage.getSnippet());
                }
            }
        } catch (Exception e) {
            System.err.println("Gmail 브리핑 중 에러 발생: " + e.getMessage());
        }
    }

    private static File findFile(String fileName) {
        // 1. 현재 디렉토리 확인
        File file = new File(fileName);
        if (file.exists()) return file;

        // 2. app/ 폴더 아래 확인
        file = new File("app/" + fileName);
        if (file.exists()) return file;

        // 3. 상위 디렉토리 확인 (혹시나 해서)
        file = new File("../" + fileName);
        if (file.exists()) return file;

        return null;
    }

    private static File findDirectory(String dirName) {
        File dir = new File(dirName);
        if (dir.exists() && dir.isDirectory()) return dir;

        dir = new File("app/" + dirName);
        if (dir.exists() && dir.isDirectory()) return dir;

        return new File("app/" + dirName); // 못 찾으면 기본값으로 생성할 위치 반환
    }

    private static boolean restoreToken() {
        try {
            File dir = findDirectory(TOKENS_DIRECTORY_NAME);
            File tokenFile = new File(dir, "StoredCredential");
            
            if (tokenFile.exists() && tokenFile.length() > 0) {
                return true;
            }

            String b64 = System.getenv("GMAIL_TOKEN");
            if (b64 == null) return false;

            if (!dir.exists()) dir.mkdirs();

            try (FileOutputStream fos = new FileOutputStream(tokenFile)) {
                fos.write(Base64.getDecoder().decode(b64.trim()));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Gmail getGmailService() throws IOException, GeneralSecurityException {
        GoogleClientSecrets secrets;
        String b64 = System.getenv("GMAIL_CREDENTIALS");

        if (b64 != null && !b64.isEmpty() && !b64.contains(".json")) {
            byte[] decoded = Base64.getDecoder().decode(b64.trim());
            secrets = GoogleClientSecrets.load(JSON_FACTORY, new StringReader(new String(decoded)));
        } else {
            File credentialsFile = findFile(CREDENTIALS_FILE_NAME);
            if (credentialsFile != null) {
                try (java.io.FileInputStream fis = new java.io.FileInputStream(credentialsFile)) {
                    secrets = GoogleClientSecrets.load(JSON_FACTORY, new java.io.InputStreamReader(fis));
                }
            } else {
                return null;
            }
        }

        File tokensDir = findDirectory(TOKENS_DIRECTORY_NAME);
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, secrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(tokensDir))
                .build();

        Credential credential = new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize("user");

        return new Gmail.Builder(GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }
}
