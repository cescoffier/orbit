package io.quarkus.orbit.monday.service.discussions;

import java.util.List;

/**
 * AI-analyzed discussion with summary and pending questions
 */
public record AnalyzedDiscussion(
    String repository,
    String title,
    String url,
    String summary,
    List<String> pendingQuestions
) {}
