package com.devyul;

import org.kohsuke.github.*;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

public class App {
    public static void main(String[] args) {
        try {
            String token = System.getenv("GITHUB_TOKEN");
            GitHub github = new GitHubBuilder().withOAuthToken(token).build();
            GHUser me = github.getMyself();
            
            System.out.println("🔍 " + me.getLogin() + " 주인님의 오늘 활동을 확인합니다...");

            // 1. 오늘 날짜 구하기
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
            boolean hasCommitsToday = false;

            // 2. 내 레포지토리들 순회 (최근 업데이트 순)
            PagedIterable<GHRepository> repositories = me.listRepositories();
            
            for (GHRepository repo : repositories) {
                // 오늘 날짜의 커밋이 있는지 확인
                List<GHCommit> commits = repo.queryCommits()
                        .since(java.sql.Date.valueOf(today))
                        .list()
                        .toList();

                if (!commits.isEmpty()) {
                    System.out.println("✅ [" + repo.getName() + "] 레포지토리에 커밋이 있습니다!");
                    hasCommitsToday = true;
                }
            }

            if (!hasCommitsToday) {
                System.out.println("⚠️ 주인님, 오늘은 아직 잔디를 심지 않으셨어요! 어서 커밋하세요.");
            } else {
                System.out.println("🎉 오늘 할 일을 다하셨군요! 훌륭합니다.");
            }

        } catch (IOException e) {
            System.err.println("❌ 깃허브 통신 에러: " + e.getMessage());
        }
    }
}