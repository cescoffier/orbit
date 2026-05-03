package io.quarkus.orbit.wg.detection.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * AI service for classifying whether Issues or PRs belong to a Working Group.
 * Uses multi-WG-aware classification to avoid associating issues with multiple wrong WGs.
 */
@RegisterAiService(chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
@ApplicationScoped
public interface IssueClassifier {

    /**
     * Classify a batch of issues/PRs against multiple Working Groups simultaneously.
     * Each issue is assigned to the single most appropriate WG, or none.
     *
     * Returns a JSON object mapping item IDs to the name of the matching WG (or empty string for no match).
     * Example: {"results": {"owner/repo#1": "WG - Observability.Next", "owner/repo#2": "", "owner/repo#3": "WG - Quarkus Data"}}
     */
    @SystemMessage("""
        You are a Quarkus expert and your goal is to triage GitHub Issues and Pull Requests to check if they can be
        associated with one of the given Quarkus Working Groups based on their defined scopes.

        You will receive MULTIPLE Working Groups with their proposals, and a list of Issues/PRs.
        For each Issue/PR, you must determine which Working Group (if any) it BEST belongs to.

        CRITICAL: Each issue/PR should be assigned to AT MOST ONE Working Group — the most specific match.
        Do NOT assign the same issue to multiple Working Groups.

        You MUST respond with a valid JSON object in this exact format:
        {
          "results": {
            "<issue-id 1>": "WG - Name of Best Matching WG",
            "<issue-id 2>": "",
            "<issue-id 3>": "WG - Another WG Name"
          }
        }

        Where:
        - Keys (<issue-id n>) are the id of the issue/PR being classified
        - Values are the EXACT name of the matching Working Group, or an empty string "" if no WG matches

        STRICT Classification Rules:

        Assign to a WG only if ALL of these conditions are met:
        1. The issue/PR title or description are in scope of that working group's proposal.
        2. The issue/PR is implementing, fixing, or enhancing functionality SPECIFIC to that working group.
        3. That WG is the BEST and MOST SPECIFIC match among all the provided WGs.

        Assign "" (no match) if ANY of these apply:
        - The issue/PR is routine maintenance (dependency updates, CI fixes, build updates)
        - The issue/PR is a general bug that could happen in any context (e.g., memory leaks, config issues)
        - The issue/PR only incidentally touches a WG's area (e.g., a Java 25 dependency bump is NOT about "Java 25 support")
        - The issue/PR is about general infrastructure, tooling, or housekeeping
        - The connection to any WG is tangential, indirect, or assumed
        - You have ANY doubt about whether it fits
        - Multiple WGs seem equally relevant (prefer no match over a wrong match)

        Common "" (no match) examples:
        - "Bump dependency X from version A to B" → "" (routine maintenance)
        - "Update CI configuration" → "" (infrastructure)
        - "Fix memory leak in logger" → "" (general bug, not domain-specific)
        - "Config property not working in tests" → "" (general test issue)
        - "Fix typo in documentation" → "" (general maintenance)
        - "Upgrade Maven plugin" → "" (build maintenance)
        - "Fix NullPointerException in X" → "" (general bug)

        IMPORTANT:
        - Default to "" when uncertain (better to miss a match than create false positives)
        - Each issue MUST map to AT MOST ONE WG — pick the best match or none
        - You MUST provide a result for EVERY item listed
        - The WG name in the result must EXACTLY match one of the provided WG names
        - Your entire response must be valid JSON with no text before or after
        """)
    @UserMessage("""
        Here are the Working Groups and their proposals (scopes):

        {workingGroupProposals}

        ---

        Classify each of the following Issues/PRs to the SINGLE best matching Working Group, or "" if none match:

        {itemsList}

        For each item, ask yourself these questions in order:
        1. Does the title or description match any Working Group's proposal SCOPE?
        2. Is the issue about implementing/fixing functionality SPECIFIC to that domain?
        3. If multiple WGs could match, which one is the MOST SPECIFIC fit?

        If you answer NO to questions 1 or 2, or if the match is ambiguous, assign "".

        Return a JSON object with the classification results:
        {
          "results": {
            "<issue-id 1>": "WG - Name" or "",
            "<issue-id 2>": "WG - Name" or "",
            ...
          }
        }

        Critical reminders:
        - General bugs (memory leaks, NPEs, config issues) are ALWAYS "" unless explicitly domain-related
        - Dependency bumps, CI updates, and general maintenance are ALWAYS ""
        - If the domain is not explicitly mentioned in the title/description, it's ""
        - When in doubt, choose ""
        - NEVER assign the same issue to multiple WGs — pick the single best match or none

        Respond ONLY with the JSON object, no other text.
        """)
    String classifyBatch(String workingGroupProposals, String itemsList);
}
