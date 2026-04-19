package com.devyul;

import com.devyul.client.*;
import com.devyul.dto.CommitDto;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class App {

    public static void main(String[] args) {
        String task = (args.length > 0) ? args[0].toUpperCase() : "ALL";
        try {
            System.out.println("🚀 Jarvis-Yul 시스템 가동 준비... (Task: " + task + ")");

            GmailClient.sendUnreadMailBriefing();

            if (task.equals("NEWS") || task.equals("ALL")) {
                NewsClient.sendNewsBriefing();
            }

            if (task.equals("STOCK") || task.equals("ALL")) {
                StockClient.sendStockBriefing();
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

    private static void runDailyAutomation() {
        System.out.println("🔍 GitHub 활동 분석 및 KST 시간순 정렬 중...");
        try {
            List<CommitDto> allCommits = GithubClient.getTodayCommits();
            if (allCommits.isEmpty()) {
                System.out.println("🚨 새로 추가할 잔디가 없습니다.");
                return;
            }

            allCommits.sort(Comparator.comparing(CommitDto::getAuthoredDate));

            int totalNewCount = 0;
            Map<String, List<String>> summaryMap = new LinkedHashMap<>();
            LocalDate todayKST = LocalDate.now(ZoneId.of("Asia/Seoul"));

            for (CommitDto commit : allCommits) {
                if (NotionClient.sendToNotionWithDuplicateCheck(todayKST.toString(), commit.getRepoName(),
                        commit.getMessage(),
                        commit.getSha())) {
                    totalNewCount++;
                    summaryMap.computeIfAbsent(commit.getRepoName(), k -> new ArrayList<>()).add(commit.getMessage());
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
                SlackClient.sendToSlack(finalMsg);
            } else {
                System.out.println("🚨 새로 추가할 잔디가 없습니다.");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}