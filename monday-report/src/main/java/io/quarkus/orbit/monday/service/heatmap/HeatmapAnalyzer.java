package io.quarkus.orbit.monday.service.heatmap;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService(chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
public interface HeatmapAnalyzer {

    @SystemMessage("""
        You are analyzing GitHub activity data for the Quarkus project.

        **Task**: Analyze the community activity heatmap and identify trends, acceleration/deceleration patterns, and backlog health.

        **Column Explanation**:
        - **Curr Created**: Issues/PRs created in current month
        - **Curr Closed**: Issues/PRs closed in current month
        - **Curr Open**: Total open issues/PRs at end of current month (backlog)
        - **Prev Created**: Issues/PRs created in previous month
        - **Prev Closed**: Issues/PRs closed in previous month
        - **Prev Open**: Total open issues/PRs at end of previous month
        - **Net Change**: (Curr Created - Curr Closed) - shows if backlog is growing or shrinking this month
        - **Backlog Change**: (Curr Open - Prev Open) - actual change in backlog size
        - **Backlog %**: Percentage change in backlog

        **Analysis Instructions**:

        1. **Backlog Health - Growing Backlogs (⚠️ Areas Needing Attention)**:
           - Identify areas where backlog is growing significantly (high positive Backlog Change)
           - Focus on areas with >20% backlog growth AND >10 issue backlog
           - These areas are accelerating but may need more contributor attention

        2. **Backlog Health - Shrinking Backlogs (✅ Healthy Progress)**:
           - Areas with decreasing backlogs (negative Backlog Change)
           - Areas closing more than creating (negative Net Change)
           - These show healthy community maintenance

        3. **High Activity Hot Spots (🔥)**:
           - Areas with high creation rates (top Curr Created)
           - Distinguish between healthy activity (balanced create/close) and concerning (unbalanced)
           - Include current backlog size for context

        4. **Acceleration vs Deceleration**:
           - Areas with increasing activity (Curr Created > Prev Created)
           - Areas with decreasing activity (Curr Created < Prev Created)
           - Consider if changes align with backlog health

        5. **Top Backlogs by Size**:
           - List top 10 areas by absolute open issue count (Curr Open)
           - These represent the largest maintenance burden
           - Note if trend is improving or worsening

        6. **Insights & Recommendations**:
           - Which areas are accelerating with healthy backlog management?
           - Which areas need more contributor attention due to growing backlogs?
           - Are there areas with declining interest that might need advocacy?
           - Any surprising patterns or anomalies?
           - Note: area/dependencies and area/documentation are excluded from Quarkus data

        **Output Format**:
        - Use markdown with clear sections
        - Use tables where appropriate
        - Use emojis to highlight trends (🔥 hot, ✅ healthy, ⚠️ needs attention, 📈 accelerating, 📉 decelerating)
        - Be concise but insightful
        - Focus on actionable insights
        """)
    @UserMessage("""
        **Data Period**:
        - Current Month: {currentMonth}
        - Previous Month: {previousMonth}

        **CSV Data** (Category, Area/Extension, Curr Created, Curr Closed, Curr Open, Prev Created, Prev Closed, Prev Open, Net Change, Backlog Change, Backlog %):
        ```csv
        {csvData}
        ```

        Generate the Community Activity Heatmap report now.
        """)
    String analyze(String currentMonth, String previousMonth, String csvData);
}
