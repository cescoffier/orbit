package io.quarkus.orbit.monday.service.issues;

import io.quarkus.logging.Log;
import io.quarkus.orbit.monday.service.support.LlmRetry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.concurrent.Semaphore;

@ApplicationScoped
public class IssueSummarizationService {

    @Inject
    IssueDebateSummarizer summarizer;

    @Inject
    @Named("llmSemaphore")
    Semaphore llmSemaphore;

    public SummarizedHotIssue summarize(HotIssue issue) {
        String label = issue.repository() + "#" + issue.number();
        Log.infof("Summarizing debate for %s", label);

        String summary = LlmRetry.withRetry(llmSemaphore,
                () -> summarizer.summarize(issue), label);

        return new SummarizedHotIssue(
                issue.repository(),
                issue.number(),
                issue.url(),
                issue.title(),
                issue.description(),
                summary
        );
    }
}
