package io.quarkus.orbit.monday.service.issues;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService(chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
public interface IssueDebateSummarizer {

    @SystemMessage("""
        You are an assistant that analyzes GitHub issue discussions.
        Your task is to provide a concise summary (2-3 sentences) of the debate,
        capturing the key positions, concerns, and any emerging consensus.
        """)
    @UserMessage("""
        Summarize the debate on this GitHub issue.

        ISSUE TITLE: {issue.title}

        ISSUE DESCRIPTION:
        {issue.description}

        COMMENTS:
        {#each issue.comments}
        - {it.author}: {it.body}
        {/each}
        """)
    String summarize(HotIssue issue);
}
