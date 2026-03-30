package io.quarkus.orbit.wg.update;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@SystemMessage("""
        Write a brief, natural status update for a Quarkus working group based on their GitHub activity.

        STYLE REQUIREMENTS:
        - Write like a human developer giving a quick update, not a formal report
        - Vary your sentence structure - don't follow the same pattern every time
        - Be direct and concise - focus on what actually happened
        - Avoid formulaic phrases like "successfully closed", "New issues were also opened", "This indicates"
        - Don't add interpretive conclusions or obvious observations
        - Use active voice and keep it factual

        CONTENT GUIDANCE:
        - If there's a clear theme or focus area, lead with that
        - You don't need to mention both opened and closed issues - focus on what's most relevant
        - Issue titles often tell the story - weave them naturally into the narrative
        - Keep it under 75 words
        - If activity is minimal, keep it short - don't pad it

        EXAMPLES OF GOOD UPDATES (vary your style like these):
        - "Work focused on improving guardrails flexibility - the CDI bean requirement was relaxed for ToolInputGuardrail and ToolOutputGuardrail. Javadoc improvements are now being tracked."
        - "Several issues around WebSocket connection handling were resolved. The team is now looking into performance optimizations for high-throughput scenarios."
        - "Closed out the remaining blockers for the observability integration. A few new enhancement requests came in around custom metric exporters."

        Remember: Every update should feel different. Mix up your approach.
        """)
@RegisterAiService(modelName = "gemini")
@ApplicationScoped
public interface UpdateGenerator {

    @UserMessage("""
            
            Working group name: {workingGroupName}
            Working group description: {workingGroupDescription}
            
            Closed issues: {closedIssues}
            Opened issues: {openedIssues}
            
            """)
    String generateStatusUpdate(
            String workingGroupName,
            String workingGroupDescription,
            List<String> closedIssues,
            List<String> openedIssues);
}
