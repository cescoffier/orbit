# Working Group Reporting

A set of Quarkus applications for tracking and managing Quarkus working group activity via GitHub Projects V2.

## Modules

- **cli-app** - Command-line interface for generating reports, managing points of contact, sending reminders, and AI-powered status updates
- **detection-app** - Web application that uses AI to automatically detect and associate GitHub issues/PRs to working groups

## Prerequisites

- Java 25
- Maven 3.9+
- `GITHUB_TOKEN` environment variable (GitHub PAT with repo/project read permissions)
- `GEMINI_API_KEY` environment variable (for AI features)
- `OPENAI_API_KEY` environment variable (for status update generation)

## Building

From the module directory:

```bash
just build
```

Or from the repository root:

```bash
just build-wg
```

## CLI Commands

After building, run the CLI app:

```bash
# Generate a monthly working group activity report
just run report --from 2025-01-01 --to 2025-01-31 --output report.md

# Manage working group points of contact
just run point-of-contact
just run point-of-contact --working-group "WG Name"
just run point-of-contact --working-group "WG Name" --contact "user@example.com"

# Generate reminder emails for inactive working groups
just run reminder --from 2025-01-01 --to 2025-01-31

# AI-powered status update generation
just run generate-status-update --from 2025-01-01 --to 2025-01-31
```

## Detection Web App

Run in dev mode:

```bash
just dev-detection
```

The detection app provides REST endpoints at `http://localhost:8080/api/detection` for starting detection runs, reviewing candidates, and applying associations.

## Configuration

Configuration is in `cli-app/src/main/resources/application.yaml` and `detection-app/src/main/resources/application.yaml`.

Key settings:
- `working-groups.organizations` - GitHub organizations to scan (default: quarkusio, quarkiverse)
- `working-groups.repository-mapping` - Maps working group names to repositories for detection

## Architecture

- Uses **GitHub GraphQL API** via SmallRye GraphQL Client
- Working groups are identified as GitHub Projects V2 with titles starting with "WG -"
- **SQLite** database for persisting point of contact information
- **LangChain4j** with Gemini for AI-powered status updates and issue detection
- **Qute** templates for report and email generation
