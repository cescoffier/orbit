package io.quarkus.orbit.monday.service.discussions;

import io.quarkus.orbit.monday.service.support.LlmRetry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.concurrent.Semaphore;

@ApplicationScoped
public class DiscussionAnalysisService {

    @Inject
    DiscussionAnalyzer analyzer;

    @Inject
    @Named("llmSemaphore")
    Semaphore llmSemaphore;

    public AnalyzedDiscussion analyzeDiscussion(Discussion discussion) {
        String label = "discussion#" + discussion.number();
        return LlmRetry.withRetry(llmSemaphore,
                () -> analyzer.analyze(discussion), label);
    }
}
