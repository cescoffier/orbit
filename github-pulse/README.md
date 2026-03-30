# GitHub Pulse

A Quarkus CLI tool that scores and surfaces important merged pull requests across GitHub repositories. Uses AI-powered classification to categorize PRs and a pluggable scoring engine to highlight the most significant changes.

## Features

- Fetches merged PRs from configured repositories via GitHub GraphQL API
- Scores PRs based on configurable rules (size, critical paths, comment activity, AI classification)
- Deduplicates across runs using SQLite state tracking
- Generates Markdown reports with scoring breakdown
- Uses Java 25 structured concurrency for parallel repository analysis

## Prerequisites

- Java 25
- Maven 3.9+
- `GITHUB_TOKEN` environment variable
- `GEMINI_API_KEY` environment variable

## Building & Running

From the module directory:

```bash
just build
just run
```

Or from the repository root:

```bash
just build-pulse
```

Reports are saved to `reports/Pulse-<date>.md`.

## Configuration

Edit `src/main/resources/application.yaml` to configure:

- `pr-pulse.repositories` - List of repositories to monitor with per-repo scoring rules
- `pr-pulse.global-threshold` - Minimum score for a PR to appear in the report (default: 60)
- `pr-pulse.lookback-days` - How far back to fetch merged PRs (default: 14)

### Scoring Rules

Each repository can configure:

| Rule | Description |
|---|---|
| `lines-changed-weight` | Score multiplier for lines changed |
| `critical-files-bonus` | Bonus points when critical paths are touched |
| `comment-activity-weight` | Score multiplier for comment count |
| `max-size-score` | Cap on size-based score |
| `feature-score` / `enhancement-score` / `bug-fix-score` | Points awarded by AI-classified PR category |
| `critical-paths` | File path prefixes that trigger the critical files bonus |

## Architecture

- **PulseCommand** - Entry point (`@QuarkusMain`)
- **AnalysisService** - Orchestrates parallel repo analysis using `StructuredTaskScope`
- **GitHubGraphQLClient** - Fetches PR data with semaphore-based throttling
- **ScoringEngine** - Aggregates pluggable `ScoringRule` implementations via CDI
- **ReportService** - Generates Markdown reports
