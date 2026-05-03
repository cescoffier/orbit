package io.quarkus.orbit.wg.detection.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkus.orbit.wg.detection.model.IssueOrPR;
import io.quarkus.orbit.wg.detection.model.WorkingGroupBoard;

import java.util.*;

@ApplicationScoped
public class BatchClassifier {

    @Inject
    IssueClassifier classifier;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Classify a list of candidates against multiple Working Groups simultaneously.
     * Each issue is assigned to at most one WG (the best match), preventing
     * the same issue from being incorrectly associated with multiple WGs.
     *
     * @param workingGroups The working groups to classify against (name -> proposal)
     * @param candidates    List of candidates to classify
     * @param batchSize     Number of items per batch
     * @return Map of WG name to list of matched issues
     */
    public Map<String, List<IssueOrPR>> classifyBatch(
            Map<String, String> workingGroups,
            List<IssueOrPR> candidates,
            int batchSize) {

        Map<String, List<IssueOrPR>> results = new LinkedHashMap<>();
        for (String wgName : workingGroups.keySet()) {
            results.put(wgName, new ArrayList<>());
        }

        if (candidates.isEmpty()) {
            return results;
        }

        String wgProposalsText = buildWorkingGroupProposalsText(workingGroups);

        for (int i = 0; i < candidates.size(); i += batchSize) {
            int endIndex = Math.min(i + batchSize, candidates.size());
            List<IssueOrPR> batch = candidates.subList(i, endIndex);

            Log.debugf("Processing batch %d-%d of %d candidates",
                    i + 1, endIndex, candidates.size());

            Map<IssueOrPR, String> batchResults = processBatch(wgProposalsText, workingGroups.keySet(), batch);

            for (Map.Entry<IssueOrPR, String> entry : batchResults.entrySet()) {
                String wgName = entry.getValue();
                if (wgName != null && !wgName.isEmpty() && results.containsKey(wgName)) {
                    results.get(wgName).add(entry.getKey());
                }
            }
        }

        return results;
    }

    String buildWorkingGroupProposalsText(Map<String, String> workingGroups) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : workingGroups.entrySet()) {
            String proposal = entry.getValue();
            if (proposal == null || proposal.isBlank()) {
                proposal = "(No proposal document available)";
            }
            sb.append("""
                    ### %s
                    %s

                    """.formatted(entry.getKey(), truncateAndSanitize(proposal, 3000)));
        }
        return sb.toString();
    }

    /**
     * Process a single batch of candidates against all WGs
     */
    Map<IssueOrPR, String> processBatch(String wgProposalsText, Set<String> wgNames, List<IssueOrPR> batch) {
        Map<IssueOrPR, String> results = new LinkedHashMap<>();

        try {
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

            Log.infof("Sending batch of %d items to AI for multi-WG classification against %d WGs",
                    batch.size(), wgNames.size());

            String response = classifier.classifyBatch(wgProposalsText, itemsList.toString());
            Log.infof("Raw AI response for batch classification: %s", response);

            Map<String, String> parsedResults = parseJsonResponse(response, wgNames, batch);

            for (IssueOrPR issueOrPR : batch) {
                String match = parsedResults.get(issueOrPR.getDisplayId());
                if (match == null) {
                    Log.warnf("No result found for item %s, defaulting to no match", issueOrPR.getDisplayId());
                    match = "";
                }
                results.put(issueOrPR, match);
            }
        } catch (Exception e) {
            Log.errorf(e, "Error in batch classification");
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
     * Expected format: {"results": {"owner/repo#123": "WG - Name", "owner/repo#456": "", ...}}
     */
    Map<String, String> parseJsonResponse(String response, Set<String> validWGNames, List<IssueOrPR> batch) {
        Map<String, String> results = new HashMap<>();

        if (response == null || response.trim().isEmpty()) {
            Log.warn("Empty response from batch classifier");
            return results;
        }

        try {
            String cleanedResponse = response.trim();

            if (cleanedResponse.startsWith("```json")) {
                cleanedResponse = cleanedResponse.substring(7);
            } else if (cleanedResponse.startsWith("```")) {
                cleanedResponse = cleanedResponse.substring(3);
            }
            if (cleanedResponse.endsWith("```")) {
                cleanedResponse = cleanedResponse.substring(0, cleanedResponse.length() - 3);
            }
            cleanedResponse = cleanedResponse.trim();

            JsonNode root = objectMapper.readTree(cleanedResponse);
            JsonNode resultsNode = root.get("results");

            if (resultsNode == null) {
                Log.warnf("No 'results' field found in response: %s", cleanedResponse);
                return results;
            }

            for (Map.Entry<String, JsonNode> entry : resultsNode.properties()) {
                String item = entry.getKey();
                String wgName = entry.getValue().asText("");

                if (!wgName.isEmpty() && !validWGNames.contains(wgName)) {
                    Log.warnf("AI returned unknown WG name '%s' for item %s, treating as no match", wgName, item);
                    wgName = "";
                }

                results.put(item, wgName);
                Log.debugf("Parsed JSON: Item %s -> %s", item, wgName.isEmpty() ? "NO MATCH" : wgName);
            }

            if (results.size() < batch.size()) {
                Log.warnf("Incomplete results: expected %d, got %d", batch.size(), results.size());
                for (IssueOrPR issueOrPR : batch) {
                    if (!results.containsKey(issueOrPR.getDisplayId())) {
                        Log.warnf("Item %s missing from results, defaulting to no match", issueOrPR.getDisplayId());
                        results.put(issueOrPR.getDisplayId(), "");
                    }
                }
            }

        } catch (Exception e) {
            Log.errorf(e, "Failed to parse JSON response: %s", response);
        }

        return results;
    }
}
