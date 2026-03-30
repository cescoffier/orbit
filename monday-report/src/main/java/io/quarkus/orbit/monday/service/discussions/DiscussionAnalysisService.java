package io.quarkus.orbit.monday.service.discussions;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class DiscussionAnalysisService {

    @Inject
    DiscussionAnalyzer analyzer;

    public AnalyzedDiscussion analyzeDiscussion(Discussion discussion) {
        return analyzer.analyze(discussion);
    }

}
