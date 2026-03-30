# Quarkus Orbit

A collection of Quarkus-based tools for monitoring and managing the Quarkus ecosystem.

[![Build](https://github.com/quarkusio/orbit/actions/workflows/build.yml/badge.svg)](https://github.com/quarkusio/orbit/actions/workflows/build.yml)

Licensed under [Apache License 2.0](LICENSE).

## Tools

| Tool | Description |
|---|---|
| [Working Group Reporting](working-group-reporting/) | Track and report on Quarkus working group activity via GitHub Projects V2 |
| [GitHub Pulse](github-pulse/) | Score and surface important merged PRs across repositories |
| [Monday Intelligence Report](monday-report/) | Weekly executive briefing on GitHub activity across the Quarkus ecosystem |

## Prerequisites

- Java 25 (with preview features) - install via [SDKMAN](https://sdkman.io/): `sdk install java 25-tem`
- Maven 3.9+ (included via `./mvnw` wrapper)
- [just](https://github.com/casey/just) command runner (optional)

## Environment Variables

Copy `.env.template` to `.env` and fill in your values.

| Variable | Used By | Description |
|---|---|---|
| `GITHUB_TOKEN` | All | GitHub Personal Access Token with `repo`, `read:org`, `read:discussion` scopes |
| `GEMINI_API_KEY` | All | Google Gemini API key for AI-powered features |
| `OPENAI_API_KEY` | Working Group Reporting | OpenAI API key (for status update generation) |

## Building

```bash
# Build all modules
just build-all

# Build a specific module
just build-pulse
just build-monday
just build-wg
```

## Project Structure

```
orbit/
  github-pulse/          # PR scoring and surfacing tool
  monday-report/         # Weekly executive briefing generator
  working-group-reporting/
    cli-app/             # CLI for WG reports, reminders, status updates
    detection-app/       # Web app for AI-powered issue/PR detection
```

Each module has its own `justfile` with module-specific commands (build, dev, run).
See each tool's README for usage instructions.

## License

Apache License 2.0 - see [LICENSE](LICENSE) for details.
