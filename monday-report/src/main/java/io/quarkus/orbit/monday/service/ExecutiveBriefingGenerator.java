package io.quarkus.orbit.monday.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkus.orbit.monday.service.discussions.AnalyzedDiscussion;
import io.quarkus.orbit.monday.service.issues.HotIssue;
import io.quarkus.orbit.monday.service.issues.MergedPR;
import io.quarkus.orbit.monday.service.issues.StalePR;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@RegisterAiService(chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
@ApplicationScoped
public interface ExecutiveBriefingGenerator {

    @SystemMessage("""
        You are the Executive Assistant to the Co-Lead of Quarkus.
        Analyze GitHub activity logs and produce a 'Monday Morning Executive Briefing' in Markdown format.

        **CRITICAL INSTRUCTIONS:**

        1. **High-Impact Merges**: Analyze the merged PRs and identify which ones are truly high-impact based on:
           - Scope of changes (files changed, lines added/deleted)
           - PR description content (new features, major refactorings, architectural changes)
           - NOT just the number of changes, but the significance
           List only PRs that represent significant features, core improvements, or major bug fixes.

        2. **Consensus Required / Hot Issues**: Summarize issues with >10 comments that seem to indicate:
           - Debates or controversial decisions
           - Design discussions requiring input
           - Community concerns that need addressing

        3. **Blocker Alert**: Identify PRs that appear blocked based on:
           - Explicit mentions of "blocked", "waiting", "needs" in recent comments
           - PRs with changes requested but no author response
           - PRs stuck in review for extended periods

        4. **Stale PRs Requiring Attention**:
           - PRs with changes requested but no author activity for 2+ weeks
           - PRs awaiting review for 2+ weeks with no activity
           Prioritize by importance/scope.

        5. **Discussions**: Summarize:
           - Discussions with pending unanswered questions that need responses
           - Key points from discussion summaries
           - Prioritize by urgency/importance

        6. **Risk Radar**: Mention any:
           - Bugs or regressions that seem serious
           - Security concerns
           - Breaking changes that need communication

        **FORMAT:**
        - Use clear headings with emojis
        - Be concise - bullet points preferred
        - When referencing PRs/Issues, use the EXACT markdown links from the raw data
        - DO NOT create new links or use just #NUMBER format - copy the URLs provided
        - Focus on ACTIONABLE items
        - Keep each section to 3-5 bullets maximum
        - Skip sections with no relevant data

        The executive has limited time - be brief and highlight what matters.
        """)
    @UserMessage("""
        Analyze the following GitHub activity from the previous calendar week ({weekStart} to {weekEnd}).

        
        ### Merged PRs:
        {#each merged}
        - [PR #{it.number}]({it.url}): {it.title}
          - Repository: {it.repository}
          - Author: {it.author}
          - Changes: {it.filesChanged} files (+{it.additions}/-{it.deletions})
          - Description: {it.description}
        {/each}

        ### Hot Issues (>10 comments):
        {#each hot}
        - [Issue #{it.number}]({it.url}): {it.title}
          - Repository: {it.repository}
          - Description: {it.description}
          - Recent comments:
          {#each it.comments}
            - {it.author}: {it.body}
          {/each}
        {/each}

        ### Stale PRs (Changes Requested):
        {#each staled}
        - [PR #{it.number}]({it.url}): {it.title}
          - Repository: {it.repository}
          - Author: {it.author}
          - Stale for {it.daysStale} days
        {/each}

        ### Stale PRs (Awaiting Review):
        {#each staledAwaitingReviews}
        - [PR #{it.number}]({it.url}): {it.title}
          - Repository: {it.repository}
          - Author: {it.author}
          - Stale for {it.daysStale} days
        {/each}

        ### Discussions:
        {#each discussions}
        - [{it.title}]({it.url})
          - Repository: {it.repository}
          - Summary: {it.summary}
          {#if it.pendingQuestions.size > 0}
          - Pending questions:
            {#each it.pendingQuestions}
            - {it}
            {/each}
          {/if}
        {/each}

        """)
    String generateBriefing(String weekStart, String weekEnd, List<HotIssue> hot, List<StalePR> staled, List<StalePR> staledAwaitingReviews, List<MergedPR> merged, List<AnalyzedDiscussion> discussions);
}
