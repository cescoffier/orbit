package io.quarkus.orbit.wg.detection.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import io.quarkus.orbit.wg.detection.model.IssueOrPR;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Helper service for batch classification of issues using the AI classifier.
 * Batches items to reduce API costs and improve performance.
 */
@Singleton
public class BatchClassifier {

    @Inject
    IssueClassifier classifier;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Classify a list of candidates using batch processing with custom batch size.
     *
     * @param proposalText The working group proposal text
     * @param candidates   List of candidates to classify
     * @param batchSize    Number of items per batch
     * @return Map of candidate to classification result (true = match, false = no match)
     */
    public Map<IssueOrPR, Boolean> classifyBatch(String proposalText, List<IssueOrPR> candidates, int batchSize) {
        Map<IssueOrPR, Boolean> results = new LinkedHashMap<>();

        if (candidates.isEmpty()) {
            return results;
        }

        // Process in batches
        for (int i = 0; i < candidates.size(); i += batchSize) {
            int endIndex = Math.min(i + batchSize, candidates.size());
            List<IssueOrPR> batch = candidates.subList(i, endIndex);

            Log.debugf("Processing batch %d-%d of %d candidates",
                    i + 1, endIndex, candidates.size());

            Map<IssueOrPR, Boolean> batchResults = processBatch(proposalText, batch);
            results.putAll(batchResults);
        }

        return results;
    }

    /**
     * Process a single batch of candidates
     */
    private Map<IssueOrPR, Boolean> processBatch(String proposalText, List<IssueOrPR> batch) {
        Map<IssueOrPR, Boolean> results = new LinkedHashMap<>();

        try {
            // Build the items list for the prompt
            // We pass items as follows:
            // - Id: issue/PR id
            //   Type: Issue/PR
            //   Title: title
            //   Description: description, truncated to 2000 chars, no breaklines

            StringBuilder itemsList = new StringBuilder();

            for (IssueOrPR issueOrPR : batch) {
                Log.debugf("Batch candidate: ID=%s, Type=%s, Title=%s",
                        issueOrPR.getDisplayId(), issueOrPR.type(), issueOrPR.title());
                String id = issueOrPR.getDisplayId();
                String type = issueOrPR.type().name();
                String title = issueOrPR.title();
                String body = truncateAndSanitize(issueOrPR.body(), 2000);
                itemsList.append("""
                        - Id: %s
                          Type: %s
                          Title: %s
                          Description: %s
                        """.formatted(id, type, title, body));
            }

            // Call the batch classifier
            Log.infof("Sending batch of %d items to AI for classification", batch.size());
            Log.debugf("Proposal text length: %d chars", proposalText != null ? proposalText.length() : 0);
            Log.debugf("Items list:\n%s", itemsList.toString());

            String response = classifier.classifyBatch(proposalText, itemsList.toString());
            Log.infof("Raw AI response for batch classification: %s", response);

            // Parse the JSON response
            Map<String, Boolean> parsedResults = parseJsonResponse(response, batch);
            // Map results back to candidates
            for (IssueOrPR issueOrPR : batch) {
                var match = parsedResults.get(issueOrPR.getDisplayId());
                if (match == null) {
                    Log.warnf("No result found for item %s, defaulting to false", issueOrPR.getDisplayId());
                    match = false;
                }
                results.put(issueOrPR, match);
            }
        } catch (Exception e) {
            Log.errorf(e, "Error in batch classification, falling back to individual classification");
            throw new RuntimeException(e);
        }

        return results;
    }

    private String truncateAndSanitize(String body, int maxLength) {
        if (body == null) {
            return "";
        }
        String sanitized = body.replaceAll("\\s+", " ").trim();
        if (sanitized.length() > maxLength) {
            return sanitized.substring(0, maxLength) + "...";
        }
        return sanitized;
    }

    /**
     * Parse the JSON response from the AI classifier.
     * Expected format: {"results": {"id1": true, "id2": false, "id3": true}}
     *
     * @param response The raw JSON response
     * @param batch    The batch of items processed
     * @return Map of item number to boolean result
     */
    private Map<String, Boolean> parseJsonResponse(String response, List<IssueOrPR> batch) {
        Map<String, Boolean> results = new HashMap<>();

        if (response == null || response.trim().isEmpty()) {
            Log.warn("Empty response from batch classifier");
            return results;
        }

        try {
            // Clean up the response - remove any markdown code blocks or extra text
            String cleanedResponse = response.trim();

            // Remove markdown code blocks if present
            if (cleanedResponse.startsWith("```json")) {
                cleanedResponse = cleanedResponse.substring(7);
            } else if (cleanedResponse.startsWith("```")) {
                cleanedResponse = cleanedResponse.substring(3);
            }
            if (cleanedResponse.endsWith("```")) {
                cleanedResponse = cleanedResponse.substring(0, cleanedResponse.length() - 3);
            }
            cleanedResponse = cleanedResponse.trim();

            // Parse JSON
            JsonNode root = objectMapper.readTree(cleanedResponse);
            JsonNode resultsNode = root.get("results");

            if (resultsNode == null) {
                Log.warnf("No 'results' field found in response: %s", cleanedResponse);
                return results;
            }

            // Extract results
            for (Map.Entry<String, JsonNode> entry : resultsNode.properties()) {
                try {
                    String item = entry.getKey();
                    boolean match = entry.getValue().asBoolean();
                    results.put(item, match);
                    Log.debugf("Parsed JSON: Item %s -> %s", item, match ? "YES" : "NO");
                } catch (NumberFormatException e) {
                    Log.warnf("Could not parse item number from key: %s", entry.getKey());
                }
            }

            // Validate completeness
            if (results.size() < batch.size()) {
                Log.warnf("Incomplete results: expected %d, got %d", batch.size(), results.size());

                // Fill in missing items with false
                for (IssueOrPR issueOrPR : batch) {
                    if (!results.containsKey(issueOrPR.getDisplayId())) {
                        Log.warnf("Item %s missing from results, defaulting to false", issueOrPR.getDisplayId());
                        results.put(issueOrPR.getDisplayId(), false);
                    }
                }
            }

        } catch (Exception e) {
            Log.errorf(e, "Failed to parse JSON response: %s", response);
            // Return empty map to trigger fallback
        }

        return results;
    }
}
