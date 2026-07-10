package io.quarkus.orbit.pulse.entity;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ReleaseEntityTest {

    @Test
    @Transactional
    void createReleaseAndAssociatePRs() {
        var repo = new RepositoryEntity();
        repo.owner = "smallrye";
        repo.name = "smallrye-mutiny-release-test";
        repo.source = RepositorySource.UPSTREAM;
        repo.persist();

        var pr1 = new ScoredPullRequestEntity();
        pr1.repository = repo;
        pr1.prNumber = 100;
        pr1.title = "feat: add Uni.onItem";
        pr1.author = "dev1";
        pr1.url = "https://github.com/smallrye/smallrye-mutiny/pull/100";
        pr1.totalScore = 75.0;
        pr1.scoredAt = Instant.now();
        pr1.persist();

        var pr2 = new ScoredPullRequestEntity();
        pr2.repository = repo;
        pr2.prNumber = 101;
        pr2.title = "fix: NPE in Multi";
        pr2.author = "dev2";
        pr2.url = "https://github.com/smallrye/smallrye-mutiny/pull/101";
        pr2.totalScore = 60.0;
        pr2.scoredAt = Instant.now();
        pr2.persist();

        var release = new ReleaseEntity();
        release.repository = repo;
        release.tag = "2.7.0";
        release.analyzedAt = Instant.now();
        release.pullRequests.add(pr1);
        release.pullRequests.add(pr2);
        release.persist();

        // Verify lookup
        var found = ReleaseEntity.findByRepoAndTag(repo, "2.7.0");
        assertTrue(found.isPresent());
        assertEquals("2.7.0", found.get().tag);
        assertEquals(2, found.get().pullRequests.size());

        // Verify repo listing
        var repoReleases = ReleaseEntity.findByRepo(repo);
        assertEquals(1, repoReleases.size());

        // Verify not found for wrong tag
        assertTrue(ReleaseEntity.findByRepoAndTag(repo, "9.9.9").isEmpty());
    }

    @Test
    @Transactional
    void prCanBelongToMultipleReleases() {
        var repo = new RepositoryEntity();
        repo.owner = "smallrye";
        repo.name = "smallrye-mutiny-multi-rel-test";
        repo.source = RepositorySource.UPSTREAM;
        repo.persist();

        var pr = new ScoredPullRequestEntity();
        pr.repository = repo;
        pr.prNumber = 200;
        pr.title = "fix: backported fix";
        pr.author = "dev1";
        pr.url = "https://github.com/smallrye/smallrye-mutiny/pull/200";
        pr.totalScore = 80.0;
        pr.scoredAt = Instant.now();
        pr.persist();

        var release1 = new ReleaseEntity();
        release1.repository = repo;
        release1.tag = "2.6.1";
        release1.analyzedAt = Instant.now();
        release1.pullRequests.add(pr);
        release1.persist();

        var release2 = new ReleaseEntity();
        release2.repository = repo;
        release2.tag = "2.7.0";
        release2.analyzedAt = Instant.now();
        release2.pullRequests.add(pr);
        release2.persist();

        assertEquals(2, ReleaseEntity.findByRepo(repo).size());

        // The PR appears in both releases
        var r1 = ReleaseEntity.findByRepoAndTag(repo, "2.6.1").orElseThrow();
        var r2 = ReleaseEntity.findByRepoAndTag(repo, "2.7.0").orElseThrow();
        assertTrue(r1.pullRequests.contains(pr));
        assertTrue(r2.pullRequests.contains(pr));
    }
}
