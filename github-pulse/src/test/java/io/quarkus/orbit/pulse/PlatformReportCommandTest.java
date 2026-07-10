package io.quarkus.orbit.pulse;

import io.quarkus.orbit.pulse.config.ConfigHelper;
import io.quarkus.orbit.pulse.config.PrPulseConfig;
import io.quarkus.orbit.pulse.entity.ReleaseEntity;
import io.quarkus.orbit.pulse.entity.RepositoryEntity;
import io.quarkus.orbit.pulse.entity.RepositorySource;
import io.quarkus.orbit.pulse.entity.ScoredPullRequestEntity;
import io.quarkus.orbit.pulse.model.PlatformReportInput;
import io.quarkus.orbit.pulse.service.AnalysisService;
import io.quarkus.orbit.pulse.service.ReportService;
import io.quarkus.orbit.pulse.scoring.rules.PrCategory;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class PlatformReportCommandTest {

    @Inject
    PrPulseConfig config;

    @Inject
    ReportService reportService;

    @Inject
    AnalysisService analysisService;

    @Inject
    UserTransaction tx;

    @Test
    void parsesInputAndGeneratesReport(@TempDir Path tempDir) throws Exception {
        // Seed database with test data
        seedTestData();

        // Create YAML input
        Path yaml = tempDir.resolve("platform.yaml");
        Files.writeString(yaml, """
                quarkus-versions:
                  - 3.38.0

                releases:
                  smallrye-mutiny:
                    - 2.8.0
                """);

        // Parse input
        PlatformReportInput input = PlatformReportInput.fromYaml(yaml);
        assertEquals(List.of("3.38.0"), input.quarkusVersions());
        assertEquals(Map.of("smallrye-mutiny", List.of("2.8.0")), input.releases());

        // Verify we can load the seeded data from the database
        tx.begin();
        try {
            var mutiny = RepositoryEntity.findByOwnerAndName("smallrye", "smallrye-mutiny");
            assertTrue(mutiny.isPresent(), "Repository should exist in DB");
            assertEquals(RepositorySource.UPSTREAM, mutiny.get().source);

            var release = ReleaseEntity.findByRepoAndTag(mutiny.get(), "2.8.0");
            assertTrue(release.isPresent(), "Release should exist in DB");
            assertEquals(1, release.get().pullRequests.size(), "Release should have 1 PR");

            var pr = release.get().pullRequests.iterator().next();
            assertEquals(123, pr.prNumber);
            assertEquals("Fix backpressure", pr.title);
            assertEquals(PrCategory.BUG_FIX, pr.category);
        } finally {
            tx.rollback();
        }
    }

    @Test
    void loadsReleasePrsFromDatabase(@TempDir Path tempDir) throws Exception {
        // Seed database
        seedTestData();

        // Load PRs using AnalysisService
        var prs = analysisService.loadReleasePrs("smallrye", "smallrye-mutiny", "2.8.0");
        assertEquals(1, prs.size());

        var pr = prs.getFirst();
        assertEquals(123, pr.pr().number());
        assertEquals("Fix backpressure", pr.pr().title());
        assertEquals(85.0, pr.score());
        assertEquals(PrCategory.BUG_FIX, pr.category());
        assertEquals("Fixes backpressure handling", pr.summary());
    }

    @Test
    void generatesMarkdownFromSeededData(@TempDir Path tempDir) throws Exception {
        // Seed database
        seedTestData();

        // Load PRs and build report structure
        var prs = analysisService.loadReleasePrs("smallrye", "smallrye-mutiny", "2.8.0");
        assertEquals(1, prs.size());

        var prWithVersions = new ReportService.PrWithVersions(prs.getFirst(), List.of("2.8.0"));
        var platformRepoReport = new ReportService.PlatformRepoReport(
                List.of("2.8.0"),
                List.of(prWithVersions)
        );

        Map<RepositorySource, Map<String, ReportService.PlatformRepoReport>> sections = new LinkedHashMap<>();
        sections.put(RepositorySource.UPSTREAM, Map.of("smallrye-mutiny", platformRepoReport));

        // Generate markdown
        String markdown = reportService.generatePlatformReportMarkdown("Test Platform Report", sections);

        assertNotNull(markdown);
        assertTrue(markdown.contains("# Platform Report: Test Platform Report"));
        assertTrue(markdown.contains("## Upstream"));
        assertTrue(markdown.contains("### smallrye-mutiny (2.8.0)"));
        assertTrue(markdown.contains("#123"));
        assertTrue(markdown.contains("Fix backpressure"));
        assertTrue(markdown.contains("Score: 85"));
        assertTrue(markdown.contains("Fixes backpressure handling"));
    }

    @Test
    void unknownRepoThrows(@TempDir Path tempDir) throws Exception {
        Path yaml = tempDir.resolve("platform.yaml");
        Files.writeString(yaml, """
                releases:
                  unknown-repo:
                    - 1.0.0
                """);

        PlatformReportInput input = PlatformReportInput.fromYaml(yaml);
        assertThrows(IllegalArgumentException.class, () -> {
            for (var entry : input.releases().entrySet()) {
                ConfigHelper.findRepoConfig(config, entry.getKey())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Unknown repository '%s'. Check application.yaml for configured repositories."
                                        .formatted(entry.getKey())));
            }
        });
    }

    @Test
    void multipleVersionsShowVersionTagsInMarkdown(@TempDir Path tempDir) throws Exception {
        // Seed data with multiple versions
        seedMultipleVersionsData();

        // Load PRs from both versions
        var prs280 = analysisService.loadReleasePrs("smallrye", "smallrye-mutiny", "2.8.0");
        var prs290 = analysisService.loadReleasePrs("smallrye", "smallrye-mutiny", "2.9.0");

        assertEquals(1, prs280.size());
        assertEquals(2, prs290.size());

        // Build report with PR appearing in both versions
        var pr123 = prs280.getFirst();
        var prWithVersions = new ReportService.PrWithVersions(pr123, List.of("2.8.0", "2.9.0"));

        var platformRepoReport = new ReportService.PlatformRepoReport(
                List.of("2.8.0", "2.9.0"),
                List.of(prWithVersions)
        );

        Map<RepositorySource, Map<String, ReportService.PlatformRepoReport>> sections = new LinkedHashMap<>();
        sections.put(RepositorySource.UPSTREAM, Map.of("smallrye-mutiny", platformRepoReport));

        String markdown = reportService.generatePlatformReportMarkdown("Multi-Version Report", sections);

        assertTrue(markdown.contains("### smallrye-mutiny (2.8.0, 2.9.0)"));
        assertTrue(markdown.contains("`2.8.0, 2.9.0`"), "Version tags should appear for PRs");
    }

    void seedTestData() throws Exception {
        tx.begin();
        try {
            // Create repo
            RepositoryEntity mutiny = RepositoryEntity.findByOwnerAndName("smallrye", "smallrye-mutiny")
                    .orElseGet(() -> {
                        RepositoryEntity r = new RepositoryEntity();
                        r.owner = "smallrye";
                        r.name = "smallrye-mutiny";
                        r.source = RepositorySource.UPSTREAM;
                        r.persist();
                        return r;
                    });

            // Create scored PR
            ScoredPullRequestEntity pr = ScoredPullRequestEntity.findByRepoAndNumber(mutiny, 123).orElseGet(() -> {
                ScoredPullRequestEntity p = new ScoredPullRequestEntity();
                p.repository = mutiny;
                p.prNumber = 123;
                p.title = "Fix backpressure";
                p.author = "dev1";
                p.url = "https://github.com/smallrye/smallrye-mutiny/pull/123";
                p.totalScore = 85.0;
                p.scoredAt = Instant.now();
                p.category = PrCategory.BUG_FIX;
                p.summary = "Fixes backpressure handling";
                p.persist();
                return p;
            });

            // Create release and associate
            ReleaseEntity release = ReleaseEntity.findByRepoAndTag(mutiny, "2.8.0").orElseGet(() -> {
                ReleaseEntity rel = new ReleaseEntity();
                rel.repository = mutiny;
                rel.tag = "2.8.0";
                rel.analyzedAt = Instant.now();
                rel.persist();
                return rel;
            });
            release.pullRequests.add(pr);

            tx.commit();
        } catch (Exception e) {
            tx.rollback();
            throw e;
        }
    }

    void seedMultipleVersionsData() throws Exception {
        tx.begin();
        try {
            RepositoryEntity mutiny = RepositoryEntity.findByOwnerAndName("smallrye", "smallrye-mutiny")
                    .orElseGet(() -> {
                        RepositoryEntity r = new RepositoryEntity();
                        r.owner = "smallrye";
                        r.name = "smallrye-mutiny";
                        r.source = RepositorySource.UPSTREAM;
                        r.persist();
                        return r;
                    });

            // PR 123 - in both 2.8.0 and 2.9.0
            ScoredPullRequestEntity pr123 = ScoredPullRequestEntity.findByRepoAndNumber(mutiny, 123).orElseGet(() -> {
                ScoredPullRequestEntity p = new ScoredPullRequestEntity();
                p.repository = mutiny;
                p.prNumber = 123;
                p.title = "Fix backpressure";
                p.author = "dev1";
                p.url = "https://github.com/smallrye/smallrye-mutiny/pull/123";
                p.totalScore = 85.0;
                p.scoredAt = Instant.now();
                p.category = PrCategory.BUG_FIX;
                p.summary = "Fixes backpressure handling";
                p.persist();
                return p;
            });

            // PR 124 - only in 2.9.0
            ScoredPullRequestEntity pr124 = ScoredPullRequestEntity.findByRepoAndNumber(mutiny, 124).orElseGet(() -> {
                ScoredPullRequestEntity p = new ScoredPullRequestEntity();
                p.repository = mutiny;
                p.prNumber = 124;
                p.title = "Add new operator";
                p.author = "dev2";
                p.url = "https://github.com/smallrye/smallrye-mutiny/pull/124";
                p.totalScore = 90.0;
                p.scoredAt = Instant.now();
                p.category = PrCategory.FEATURE;
                p.summary = "Adds new reactive operator";
                p.persist();
                return p;
            });

            // Release 2.8.0 with PR 123
            ReleaseEntity release280 = ReleaseEntity.findByRepoAndTag(mutiny, "2.8.0").orElseGet(() -> {
                ReleaseEntity rel = new ReleaseEntity();
                rel.repository = mutiny;
                rel.tag = "2.8.0";
                rel.analyzedAt = Instant.now();
                rel.persist();
                return rel;
            });
            release280.pullRequests.add(pr123);

            // Release 2.9.0 with both PRs
            ReleaseEntity release290 = ReleaseEntity.findByRepoAndTag(mutiny, "2.9.0").orElseGet(() -> {
                ReleaseEntity rel = new ReleaseEntity();
                rel.repository = mutiny;
                rel.tag = "2.9.0";
                rel.analyzedAt = Instant.now();
                rel.persist();
                return rel;
            });
            release290.pullRequests.add(pr123);
            release290.pullRequests.add(pr124);

            tx.commit();
        } catch (Exception e) {
            tx.rollback();
            throw e;
        }
    }
}
