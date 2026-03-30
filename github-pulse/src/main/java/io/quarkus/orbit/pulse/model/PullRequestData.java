package io.quarkus.orbit.pulse.model;

import java.util.List;

public record PullRequestData(
        String repoOwner,
        String repoName,
        int number,
        String title,
        String url,
        String author,
        String description,
        int additions,
        int deletions,
        int commentCount,
        List<String> filePaths,
        List<String> labels
) {
    public String repoIdentifier() {
        return repoOwner + "/" + repoName;
    }
}
