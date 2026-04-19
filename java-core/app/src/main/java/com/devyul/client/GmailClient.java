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
    private static final String TOKENS_DIRECTORY = "tokens";

    private GmailClient() {
    }

    public static void sendUnreadMailBriefing() {
        try {
            if (!restoreToken()) {
                System.out.println("⚠️ Gmail 토큰 복원 실패: 인증이 필요한 작업은 스킵합니다.");
                return;
            }
            Gmail service = getGmailService();
            if (service == null) {
                System.out.println("⚠️ Gmail 서비스 생성 실패: 작업을 스킵합니다.");
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

    private static Gmail getGmailService() throws IOException, GeneralSecurityException {
        String b64 = System.getenv("GMAIL_CREDENTIALS");
        if (b64 == null)
            return null;

        byte[] decoded = Base64.getDecoder().decode(b64.trim());
        GoogleClientSecrets secrets = GoogleClientSecrets.load(JSON_FACTORY, new StringReader(new String(decoded)));

        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, secrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(new File(TOKENS_DIRECTORY)))
                .build();

        Credential credential = new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize("user");

        return new Gmail.Builder(GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }
}
