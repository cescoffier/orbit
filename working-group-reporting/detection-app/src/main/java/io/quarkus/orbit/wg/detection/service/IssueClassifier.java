package io.quarkus.orbit.wg.detection.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * AI service for classifying whether Issues or PRs belong to a Working Group.
 * Supports both single-item and batch classification for cost optimization.
 */
@RegisterAiService(chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
@ApplicationScoped
public interface IssueClassifier {

    /**
     * Classify a batch of issues/PRs in a single API call.
     * This is more cost-effective and faster for multiple items.
     *
     * Returns a JSON object mapping item numbers to classification results.
     * Example: {"results": {"1": true, "2": false, "3": true}}
     */
    @SystemMessage("""
        You are a Quarkus expert and your goal is to triage GitHub Issues and Pull Requests to check if they can be 
        associated with a specific Quarkus Working Group based on its defined scope.
        
        Your task is to determine if each Issue or Pull Request is DIRECTLY and SPECIFICALLY related
        to a Quarkus Working Group's scope based on its proposal document.

        You MUST respond with a valid JSON object in this exact format:
        {
          "results": {
            "<issue-id 1>": true,
            "<issue-id 2>": false,
            "<issue-id 3>": true
          }
        }

        Where:
        - Keys (<issue-id n>) are the id of the issue/PR being classified
        - Values are booleans: true if the issue matches the WG scope, false otherwise

        STRICT Classification Rules:

        Set TRUE only if ALL of these conditions are met:
        1. The issue/PR title or description are in scope of the working group's proposal.
        2. The issue/PR is implementing, fixing, or enhancing functionality SPECIFIC to that working group.
       

        Set FALSE if ANY of these apply:
        - The issue/PR is routine maintenance (dependency updates, CI fixes, build updates)
        - The issue/PR is a general bug that could happen in any context (e.g., memory leaks, config issues)
        - The issue/PR only incidentally touches the WG's area (e.g., a Java 25 dependency bump is NOT about "Java 25 support")
        - The issue/PR is about general infrastructure, tooling, or housekeeping      
        - The connection to the WG is tangential, indirect, or assumed
        - You have ANY doubt about whether it fits

        Common FALSE examples:
        - "Bump dependency X from version A to B" → FALSE (routine maintenance)
        - "Update CI configuration" → FALSE (infrastructure)
        - "Fix memory leak in logger" → FALSE (general bug, not domain-specific)
        - "Config property not working in tests" → FALSE (general test issue)
        - "Fix typo in documentation" → FALSE (general maintenance)
        - "Upgrade Maven plugin" → FALSE (build maintenance)
        - "Fix NullPointerException in X" → FALSE (general bug)

        IMPORTANT:
        - Default to FALSE when uncertain (better to miss a match than create false positives)
        - You MUST provide a result for EVERY item listed
        - Your entire response must be valid JSON with no text before or after
        """)
    @UserMessage("""
        Working Group Proposal (this defines the SPECIFIC scope):
        ---
        {proposalText}
        ---

        Classify each of the following Issues/PRs:

        {itemsList}

        For each item, ask yourself these questions in order:
        1. Does the title or description match the Working Group's proposal SCOPE?
        2. Is the issue about implementing/fixing functionality SPECIFIC to that domain?

        If you answer NO to ANY question, classify as FALSE.

        Return a JSON object with the classification results:
        {
          "results": {
            "<issue-id 1>": true/false,
            "<issue-id 2>": true/false,
            ...
          }
        }

        Critical reminders:
        - General bugs (memory leaks, NPEs, config issues) are ALWAYS false unless explicitly domain-related
        - Dependency bumps, CI updates, and general maintenance are ALWAYS false
        - If the domain is not explicitly mentioned in the title/description, it's FALSE
        - When in doubt, choose false.

        Respond ONLY with the JSON object, no other text.
        """)
    String classifyBatch(String proposalText, String itemsList);
}
