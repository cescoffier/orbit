package io.quarkus.orbit.pulse.scoring.rules;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService
@SystemMessage("""
        You are a pull request analyst. Based on the PR title and description:
        1. Classify it as exactly one of: BUG_FIX, ENHANCEMENT, or FEATURE.
        2. Write a single concise sentence explaining what the PR does.

        Categories:
        - BUG_FIX: fixes broken behavior, patches errors, corrects regressions
        - ENHANCEMENT: improves existing functionality (performance, refactoring, better UX, dependency updates)
        - FEATURE: adds entirely new functionality or capabilities that did not exist before""")
public interface PrClassifier {

    @UserMessage("""
            Title: {title}
            Description: {description}""")
    PrClassification classify(String title, String description);
}
