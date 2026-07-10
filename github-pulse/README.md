# GitHub Pulse

A Quarkus CLI tool that scores and surfaces important merged pull requests across GitHub repositories. Uses AI-powered classification to categorize PRs and a pluggable scoring engine to highlight the most significant changes.

## Prerequisites

- Java 25
- Maven 3.9+
- [just](https://github.com/casey/just) command runner

## Configuration

### Environment Variables

| Variable | Purpose |
|---|---|
| `GITHUB_TOKEN` | GitHub personal access token for the GraphQL API |
| `GEMINI_API_KEY` | Google Gemini API key for AI-powered PR classification |

Both are required at runtime. Export them before running:

```bash
export GITHUB_TOKEN=ghp_...
export GEMINI_API_KEY=AI...
```

### Repository Configuration

Repositories to monitor are defined in `src/main/resources/application.yaml` under `pr-pulse.repositories`. Each entry looks like:

```yaml
pr-pulse:
  global-threshold: 60        # minimum score for a PR to appear in reports
  lookback-days: 14            # how far back to fetch merged PRs
  repositories:
    - name: "smallrye-mutiny"
      owner: "smallrye"
      source: UPSTREAM           # UPSTREAM, QUARKUS, or QUARKIVERSE
      description: "Reactive programming library"
      artifacts:
        - "io.smallrye.reactive:mutiny-*"
      release-strategy: COMMIT_GRAPH   # or RELEASE_NOTES
      rules:
        size-weight: 0.20
        category-weight: 0.35
        critical-path-weight: 0.25
        comment-weight: 0.20
        critical-paths:
          - "implementation/src/main/java"
          - "api/src/main/java"
```

The `source` field groups repositories in platform reports: `UPSTREAM` for upstream SmallRye/Vert.x projects, `QUARKUS` for quarkusio/quarkus, and `QUARKIVERSE` for Quarkiverse extensions.

The `release-strategy` controls how PRs are discovered for a release tag: `COMMIT_GRAPH` walks the git history between tags via GraphQL, while `RELEASE_NOTES` parses the GitHub release body for PR references.

### Scoring Rules

Each repository can tune four scoring rules via the `rules` section:

| Rule | Weight key | Description |
|---|---|---|
| Size | `size-weight` | Based on lines changed (capped at `max-size-score`, scaled by `size-scale-factor`) |
| Category | `category-weight` | Points based on AI classification (FEATURE, ENHANCEMENT, BUG_FIX) |
| Critical Path | `critical-path-weight` | Bonus when files matching `critical-paths` prefixes are touched |
| Comment Activity | `comment-weight` | Based on review comment count |

Weights should sum to 1.0. The final score is compared against `global-threshold` to filter the report.

## Building

```bash
just build
```

## Usage

### Typical Workflow: Platform Release Report

The standard workflow is:

1. **Run `release-report` for each repository/release** to analyze and store PR scores
2. **Write a platform YAML file** describing the release composition
3. **Run `platform-report`** to generate the aggregated report

#### Step 1: Generate Release Reports

For each repository and release tag included in the platform release:

```bash
just release-report smallrye-mutiny 2.8.0
just release-report smallrye-stork 2.10.0
just release-report quarkus 3.38.0
```

This fetches the PRs associated with each release, scores them using the configured rules and Gemini classification, and stores everything in the local SQLite database (`github_pulse.db`). A per-release Markdown report is also written to `reports/ReleaseReport-<repo>-<tag>.md`.

#### Step 2: Write the Platform YAML File

Create a YAML file (see `platform-report-template.yaml`) describing which releases compose the platform version:

```yaml
quarkus-versions:
  - 3.38.0.CR1
  - 3.38.0

releases:
  smallrye-mutiny:
    - 2.8.0
  smallrye-stork:
    - 2.10.0
    - 2.10.1
  quarkus-langchain4j:
    - 1.11.0
```

`quarkus-versions` is a shorthand that maps to the `quarkus` repository. Everything under `releases` uses the repository `name` from `application.yaml`.

#### Step 3: Generate the Platform Report

```bash
just platform-report platform-report-3.38.yaml
```

Output is written to `reports/PlatformReport-<version>.md`, grouping PRs by source (Upstream, Quarkus, Quarkiverse).

### Other Commands

#### `analyze` -- Periodic PR Pulse

Scans all configured repositories for recently merged PRs (within `lookback-days`), scores them, and generates a pulse report:

```bash
just run                          # analyze all repos
just run analyze smallrye-mutiny  # single repo
just run analyze --lookback=30    # override lookback window
just run analyze --dry-run        # fetch only, no scoring
just run analyze --refresh        # re-score already-processed PRs
```

Reports are saved to `reports/Pulse-<date>.md`.

#### `scores` -- View Stored Scores

Query the local database for previously scored PRs:

```bash
just score smallrye-mutiny              # view scores
just score-and-refresh smallrye-mutiny  # re-fetch and re-score (30-day lookback)
```

### Command Reference

| Just command | Description |
|---|---|
| `just build` | Build the project (skips tests) |
| `just dev` | Start in Quarkus dev mode |
| `just run` | Run the default `analyze` command |
| `just score <repo>` | View stored scores with details |
| `just score-and-refresh <repo>` | Re-fetch and re-score a repo (30-day lookback) |
| `just release-report <repo> <tag>` | Analyze a specific release |
| `just platform-report <file>` | Generate an aggregated platform report from YAML |

## Architecture

- **PulseTopCommand** -- Entry point with four subcommands
- **AnalysisService** -- Orchestrates parallel repo analysis using `StructuredTaskScope` (Java 25 virtual threads)
- **GitHubGraphQLClient** -- Fetches PR data with semaphore-based throttling (5 concurrent requests)
- **ScoringEngine** -- Aggregates pluggable `ScoringRule` implementations via CDI
- **PrClassifier** -- LangChain4j AI service (Gemini) for PR categorization, with SQLite caching and rate limiting
- **ReportService** -- Generates Markdown reports (pulse, release, platform)

Data is stored in a local SQLite database (`github_pulse.db`) using Panache entities. The `ProcessedPr` entity tracks scored PRs to avoid re-reporting, and `PrClassification` caches AI classifications.
