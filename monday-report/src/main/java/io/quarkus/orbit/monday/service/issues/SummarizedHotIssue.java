package io.quarkus.orbit.monday.service.issues;

public record SummarizedHotIssue(
    String repository,
    int number,
    String url,
    String title,
    String description,
    String debateSummary
) {}
