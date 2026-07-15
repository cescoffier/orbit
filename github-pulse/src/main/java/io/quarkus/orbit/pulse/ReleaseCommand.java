package io.quarkus.orbit.pulse;

import io.quarkus.orbit.pulse.config.PrPulseConfig;
import io.quarkus.orbit.pulse.model.ReleasePullRequest;
import io.quarkus.orbit.pulse.service.ReleaseService;
import jakarta.enterprise.context.control.ActivateRequestContext;
import picocli.CommandLine;

import java.util.List;
import java.util.stream.Collectors;

@CommandLine.Command(
        name = "release",
        description = "List pull requests included in a specific release tag"
)
public class ReleaseCommand implements Runnable {

    private final ReleaseService releaseService;

    @CommandLine.Parameters(index = "0",
            description = "Repository name (e.g. 'smallrye-graphql' or 'smallrye/smallrye-graphql')")
    String repo;

    @CommandLine.Parameters(index = "1",
            description = "Release tag name (e.g. '3.0.0.Beta5' or '3.37.2')")
    String tag;

    public ReleaseCommand(ReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    @Override
    @ActivateRequestContext
    public void run() {
        PrPulseConfig.Repository repoConfig = releaseService.findRepoConfig(repo).orElse(null);
        if (repoConfig == null) {
            String known = releaseService.knownRepos().stream()
                    .map(r -> "  - " + r)
                    .collect(Collectors.joining("\n"));
            System.err.println("Unknown repository: " + repo);
            System.err.println("Known repositories:\n" + known);
            return;
        }

        String fullName = repoConfig.owner() + "/" + repoConfig.name();
        System.out.printf("Fetching PRs for %s @ %s  [strategy: %s]%n%n",
                fullName, tag, repoConfig.releaseStrategy());

        List<ReleasePullRequest> prs;
        try {
            prs = releaseService.fetchPRsForRelease(repo, tag);
        } catch (Exception e) {
            System.err.println("Failed to fetch release PRs: " + e.getMessage());
            return;
        }

        if (prs.isEmpty()) {
            System.out.println("No pull requests found for this release.");
            return;
        }

        System.out.printf("%-6s  %-12s  %-20s  %s%n", "PR", "Merged", "Author", "Title");
        System.out.println("-".repeat(100));

        for (ReleasePullRequest pr : prs) {
            String merged = pr.mergedAt() != null ? pr.mergedAt().substring(0, 10) : "—";
            String author = pr.author().isBlank() ? "—" : "@" + pr.author();
            String title = pr.title().length() > 60 ? pr.title().substring(0, 58) + ".." : pr.title();
            System.out.printf("#%-5d  %-12s  %-20s  %s%n", pr.number(), merged, author, title);
        }

        System.out.printf("%n%d PR(s) found in %s @ %s%n", prs.size(), fullName, tag);
    }
}
