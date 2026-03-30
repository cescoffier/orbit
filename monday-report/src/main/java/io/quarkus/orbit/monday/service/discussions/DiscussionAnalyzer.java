package io.quarkus.orbit.monday.service.discussions;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService(chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
public interface DiscussionAnalyzer {

    @SystemMessage("""
        You are an assistant that analyzes GitHub discussions.
        Your task is to:
        1. Provide a concise summary (2-3 sentences) of what is being discussed
        2. Identify any questions that have NOT been clearly answered yet
        """)
    @UserMessage("""
        Analyze the following GitHub discussion.

        DISCUSSION TITLE: {discussion.title}

        DISCUSSION BODY:
        {discussion.body}

        COMMENTS:
        {#if discussion.comments.size > 0}
        {#each discussion.comments}
        - {it.author}: {it.body}
        {/each}
        {#else}
        (No comments yet)
        {/if}
        """)
    AnalyzedDiscussion analyze(Discussion discussion);
}
