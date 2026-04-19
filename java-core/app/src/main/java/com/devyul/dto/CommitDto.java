package com.devyul.dto;

import java.util.Date;

public class CommitDto {
    private final String repoName;
    private final String message;
    private final String sha;
    private final Date authoredDate;

    public CommitDto(String repoName, String message, String sha, Date authoredDate) {
        this.repoName = repoName;
        this.message = message;
        this.sha = sha;
        this.authoredDate = authoredDate;
    }

    public String getRepoName() {
        return repoName;
    }

    public String getMessage() {
        return message;
    }

    public String getSha() {
        return sha;
    }

    public Date getAuthoredDate() {
        return authoredDate;
    }
}
