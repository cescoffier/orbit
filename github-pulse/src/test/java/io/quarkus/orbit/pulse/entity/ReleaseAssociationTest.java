package io.quarkus.orbit.pulse.entity;

import io.quarkus.orbit.pulse.model.PullRequestData;
import io.quarkus.orbit.pulse.model.ScoredPullRequest;
import io.quarkus.orbit.pulse.scoring.ScoringRule;
import io.quarkus.orbit.pulse.scoring.rules.PrCategory;
import io.quarkus.orbit.pulse.service.AnalysisService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ReleaseAssociationTest {

    @Inject
    AnalysisService analysisService;

    @Inject
    UserTransaction tx;

    @Test
    void associateWithReleaseCreatesReleaseAndLinks() throws Exception {
        tx.begin();
        try {
            var repo = new RepositoryEntity();
            repo.owner = "testorg";
            repo.name = "assoc-test-repo";
            repo.source = RepositorySource.UPSTREAM;
            repo.persist();

            var spr1 = new ScoredPullRequestEntity();
            spr1.repository = repo;
            spr1.prNumber = 10;
            spr1.title = "feat: something";
            spr1.author = "dev";
            spr1.url = "https://github.com/testorg/assoc-test-repo/pull/10";
            spr1.totalScore = 70.0;
            spr1.scoredAt = Instant.now();
            spr1.persist();

            var spr2 = new ScoredPullRequestEntity();
            spr2.repository = repo;
            spr2.prNumber = 11;
            spr2.title = "fix: something else";
            spr2.author = "dev2";
            spr2.url = "https://github.com/testorg/assoc-test-repo/pull/11";
            spr2.totalScore = 55.0;
            spr2.scoredAt = Instant.now();
            spr2.persist();

            tx.commit();
        } catch (Exception e) {
            tx.rollback();
            throw e;
        }

        var pr1 = new PullRequestData("testorg", "assoc-test-repo", 10, "feat: something",
                "https://github.com/testorg/assoc-test-repo/pull/10", "dev", "", 10, 5, 1,
                List.of(), List.of());
        var pr2 = new PullRequestData("testorg", "assoc-test-repo", 11, "fix: something else",
                "https://github.com/testorg/assoc-test-repo/pull/11", "dev2", "", 5, 2, 0,
                List.of(), List.of());

        var scored1 = new ScoredPullRequest(pr1, 70.0, List.of(), Map.of(), PrCategory.FEATURE, "A feature");
        var scored2 = new ScoredPullRequest(pr2, 55.0, List.of(), Map.of(), PrCategory.BUG_FIX, "A fix");

        analysisService.associateWithRelease("testorg", "assoc-test-repo", "1.0.0",
                List.of(scored1, scored2));

        tx.begin();
        try {
            var repo = RepositoryEntity.findByOwnerAndName("testorg", "assoc-test-repo").orElseThrow();
            var release = ReleaseEntity.findByRepoAndTag(repo, "1.0.0");
            assertTrue(release.isPresent());
            assertEquals(2, release.get().pullRequests.size());
        } finally {
            tx.rollback();
        }
    }

    @Test
    void loadReleasePrsReturnsPreviouslyAssociatedPrs() throws Exception {
        tx.begin();
        try {
            var repo = new RepositoryEntity();
            repo.owner = "testorg";
            repo.name = "load-release-repo";
            repo.source = RepositorySource.UPSTREAM;
            repo.persist();

            var spr1 = new ScoredPullRequestEntity();
            spr1.repository = repo;
            spr1.prNumber = 20;
            spr1.title = "feat: first";
            spr1.author = "dev";
            spr1.url = "https://github.com/testorg/load-release-repo/pull/20";
            spr1.totalScore = 75.0;
            spr1.scoredAt = Instant.now();
            spr1.category = PrCategory.FEATURE;
            spr1.summary = "First feature";
            spr1.persist();

            var spr2 = new ScoredPullRequestEntity();
            spr2.repository = repo;
            spr2.prNumber = 21;
            spr2.title = "fix: second";
            spr2.author = "dev2";
            spr2.url = "https://github.com/testorg/load-release-repo/pull/21";
            spr2.totalScore = 60.0;
            spr2.scoredAt = Instant.now();
            spr2.category = PrCategory.BUG_FIX;
            spr2.summary = "A bug fix";
            spr2.persist();

            tx.commit();
        } catch (Exception e) {
            tx.rollback();
            throw e;
        }

        var pr1 = new PullRequestData("testorg", "load-release-repo", 20, "feat: first",
                "https://github.com/testorg/load-release-repo/pull/20", "dev", "", 10, 5, 1,
                List.of(), List.of());
        var pr2 = new PullRequestData("testorg", "load-release-repo", 21, "fix: second",
                "https://github.com/testorg/load-release-repo/pull/21", "dev2", "", 5, 2, 0,
                List.of(), List.of());

        var scored1 = new ScoredPullRequest(pr1, 75.0, List.of(), Map.of(), PrCategory.FEATURE, "First feature");
        var scored2 = new ScoredPullRequest(pr2, 60.0, List.of(), Map.of(), PrCategory.BUG_FIX, "A bug fix");

        analysisService.associateWithRelease("testorg", "load-release-repo", "3.0.0",
                List.of(scored1, scored2));

        List<ScoredPullRequest> loaded = analysisService.loadReleasePrs("testorg", "load-release-repo", "3.0.0");
        assertEquals(2, loaded.size());

        var prNumbers = loaded.stream().map(s -> s.pr().number()).toList();
        assertTrue(prNumbers.contains(20));
        assertTrue(prNumbers.contains(21));
    }

    @Test
    void loadReleasePrsReturnsEmptyForUnknownRelease() {
        List<ScoredPullRequest> loaded = analysisService.loadReleasePrs("testorg", "nonexistent", "9.9.9");
        assertTrue(loaded.isEmpty());
    }

    @Test
    void associateWithReleaseIsIdempotent() throws Exception {
        tx.begin();
        try {
            var repo = new RepositoryEntity();
            repo.owner = "testorg";
            repo.name = "idempotent-test-repo";
            repo.source = RepositorySource.UPSTREAM;
            repo.persist();

            var spr = new ScoredPullRequestEntity();
            spr.repository = repo;
            spr.prNumber = 50;
            spr.title = "feat: idempotent";
            spr.author = "dev";
            spr.url = "https://github.com/testorg/idempotent-test-repo/pull/50";
            spr.totalScore = 80.0;
            spr.scoredAt = Instant.now();
            spr.persist();

            tx.commit();
        } catch (Exception e) {
            tx.rollback();
            throw e;
        }

        var pr = new PullRequestData("testorg", "idempotent-test-repo", 50, "feat: idempotent",
                "https://github.com/testorg/idempotent-test-repo/pull/50", "dev", "", 10, 5, 1,
                List.of(), List.of());
        var scored = new ScoredPullRequest(pr, 80.0, List.of(), Map.of(), PrCategory.FEATURE, "Feature");

        analysisService.associateWithRelease("testorg", "idempotent-test-repo", "2.0.0",
                List.of(scored));
        analysisService.associateWithRelease("testorg", "idempotent-test-repo", "2.0.0",
                List.of(scored));

        tx.begin();
        try {
            var repo = RepositoryEntity.findByOwnerAndName("testorg", "idempotent-test-repo").orElseThrow();
            var releases = ReleaseEntity.findByRepo(repo);
            assertEquals(1, releases.size());
            assertEquals(1, releases.getFirst().pullRequests.size());
        } finally {
            tx.rollback();
        }
    }
}
