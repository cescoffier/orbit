# Monday Intelligence Report

A Quarkus CLI tool that generates weekly executive briefings and monthly community heatmaps by analyzing GitHub activity across the Quarkus ecosystem.

## Features

- Scans 47+ repositories across Quarkus, SmallRye, and Quarkiverse organizations
- Parallel repository scanning using Java 25 virtual threads
- AI-powered executive briefing generation using Google Gemini
- Community activity heatmap with acceleration metrics
- Advanced GitHub API rate limiting
- GitHub discussion analysis

## Prerequisites

- Java 25
- Maven 3.9+
- `GITHUB_TOKEN` environment variable
- `GEMINI_API_KEY` environment variable

## Building & Running

From the module directory:

```bash
just build
just report    # Weekly executive briefing
just heatmap   # Monthly community heatmap
```

Or from the repository root:

```bash
just build-monday
```

## Commands

### `report` - Weekly Executive Briefing

Analyzes the previous calendar week (Monday to Sunday) and generates an AI-powered briefing covering:
- High-impact merges
- Consensus-required issues
- Blocker alerts
- Stale PRs requiring attention
- Unanswered discussions
- Risk radar

### `heatmap` - Monthly Community Heatmap

Analyzes activity acceleration across Quarkus areas and Quarkiverse extensions by comparing the last two months.

Options:
- `--output <path>` - Custom output directory

## Configuration

Edit `src/main/resources/application.yaml` to configure:

- `monday-report.repositories` - List of repositories to monitor
- `monday-report.output-dir` - Report output directory (default: `reports`)
- `monday-report.heatmap-output-dir` - Heatmap output directory (default: `reports/heatmap`)

## Architecture

- **MondayIntelligenceReportCommand / HeatmapCommand** - Picocli CLI entry points
- **GitHubScanService** - Orchestrates parallel repository scanning with Mutiny
- **GithubService** - Kohsuke GitHub API client with rate limiting
- **GitHubGraphQLService** - GraphQL API for area/extension activity metrics
- **ExecutiveBriefingGenerator** - LangChain4j AI service for report generation
- **ConcurrencyService** - Virtual thread management with semaphore control
