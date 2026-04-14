package com.devyul;
import java.util.List;

public class CommitInfo {
    String repoName;
    List<String> messages;

    public CommitInfo(String repoName, List<String> messages) {
        this.repoName = repoName;
        this.messages = messages;
    }
}