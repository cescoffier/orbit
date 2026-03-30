# Contributing to Quarkus Orbit

Thank you for your interest in contributing!

## Prerequisites

- Java 25 with preview features: `sdk install java 25-tem && sdk use java 25-tem`
- Maven 3.9+ (use the included `./mvnw` wrapper)
- A GitHub Personal Access Token (see `.env.template`)

## Getting Started

1. Fork and clone the repository
2. Copy `.env.template` to `.env` and fill in your tokens
3. Build all modules: `./mvnw clean install -DskipTests`
4. Run tests: `./mvnw clean verify`

## Running Individual Tools

Each tool has its own `justfile` with build and run commands. Navigate to the module directory and run `just --list` to see available commands.

## Pull Requests

- Create a feature branch from `main`
- Ensure `./mvnw clean verify` passes
- Keep changes focused and well-described

## Project Structure

- `github-pulse/` - PR scoring and surfacing tool (CLI)
- `monday-report/` - Weekly executive briefing generator (CLI)
- `working-group-reporting/cli-app/` - Working group reporting CLI
- `working-group-reporting/detection-app/` - Working group detection web app

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
