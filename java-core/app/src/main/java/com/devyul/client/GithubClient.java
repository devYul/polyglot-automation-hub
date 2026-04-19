package com.devyul.client;

import com.devyul.dto.CommitDto;
import org.kohsuke.github.*;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class GithubClient {

    private static final String GITHUB_TOKEN = System.getenv("GITHUB_TOKEN");

    private GithubClient() {
    }

    public static List<CommitDto> getTodayCommits() throws IOException {
        if (GITHUB_TOKEN == null || GITHUB_TOKEN.isEmpty()) {
            System.out.println("GitHub token is not configured. Skipping commit check.");
            return new ArrayList<>();
        }

        GitHub github = new GitHubBuilder().withOAuthToken(GITHUB_TOKEN).build();
        GHMyself myself = github.getMyself();

        // KST today 00:00:00
        ZonedDateTime kstStart = LocalDate.now(ZoneId.of("Asia/Seoul")).atStartOfDay(ZoneId.of("Asia/Seoul"));
        Date sinceDate = Date.from(kstStart.toInstant());

        List<CommitDto> allCommits = new ArrayList<>();

        for (GHRepository repo : myself.listRepositories()) {
            List<GHCommit> commits = repo.queryCommits().since(sinceDate).list().toList();
            for (GHCommit commit : commits) {
                allCommits.add(new CommitDto(
                        repo.getName(),
                        commit.getCommitShortInfo().getMessage(),
                        commit.getSHA1(),
                        commit.getCommitShortInfo().getAuthoredDate()));
            }
        }
        return allCommits;
    }
}
