# AGENTS.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Maftourbot is a Telegram bot + web admin panel for managing sport mafia tournament seating arrangements. It syncs tournament data from the gomafia.pro API, stores seating/table info in PostgreSQL, and notifies registered players about their table assignments via Telegram.

## Build & Run

```bash
./gradlew build          # Build everything (includes tests)
./gradlew test           # Run tests only
./gradlew run            # Run the server locally (port 8080)
./gradlew buildFatJar    # Build executable JAR with all dependencies
```

Local database: `docker compose up maftourbot-db` starts PostgreSQL on port 5436. The app expects env vars or falls back to defaults in `application.conf` (localhost:5436, user/pass: postgres/postgres).

Required env var for bot functionality: `TELEGRAM_BOT_TOKEN`.

## Tech Stack

- **Kotlin 2.1 / JVM 17** with **Ktor 3.1** (Netty engine)
- **Exposed ORM** (DAO pattern) with PostgreSQL + HikariCP connection pool
- **kotlin-telegram-bot** library for Telegram bot commands
- **gomafia-library** (`io.github.mralex1810:gomafia-library`) — custom client for gomafia.pro REST API
- **Thymeleaf** for admin HTML templates
- **kotlinx.serialization** + **Jackson** for JSON
- Metrics via Micrometer/Prometheus at `/metrics-micrometer`

## Architecture

All source lives in `src/main/kotlin/` under package `online.mafoverlay` — flat structure, no sub-packages.

**Startup flow** (`Application.kt:main`): initializes DB → creates repositories → creates HTTP client + GomafiaRestClient → creates TournamentService → creates Telegram bot with command handlers → starts bot polling → starts Ktor HTTP server.

Key components:
- **`Application.kt`** — entry point, Telegram bot command handlers (`/start`, `/register`, `/arrangement`, `/help`), HTTP routing (admin panel), and the `GomafiaRestClient.getTournamentDto()` extension that maps gomafia API responses into domain DTOs
- **`Entities.kt`** — Exposed table definitions (`Players`, `Tournaments`, `Tours`, `TournamentTables`, `TourTablePlayers`) and DAO entity classes with `toDto()` conversions
- **`Dtos.kt`** — data classes: `PlayerDto`, `TournamentDto`, `TourDto`, `TableDto`, `PlayerGameDto`, `PlayerArrangementDto`
- **`PlayerRepository.kt`** — contains both `PlayerRepository` and `TournamentRepository` (CRUD for players, tournaments, tours, table locations, seating)
- **`TournamentService.kt`** — business logic for player arrangement lookups across tournaments
- **`NotificationService.kt`** — sends Telegram notifications for tour starts and broadcast messages to tournament participants

**Data model**: Tournaments have Tours, which have Tables. Tables have a location (shared across tours via `TournamentTables`). Player seating per tour is stored in `TourTablePlayers`. Players link Telegram IDs to gomafia profile IDs.

**Admin routes** (all under `/admin`): view tournaments, view tournament details, update tour start times, update table locations, trigger notifications for a tour, broadcast messages to participants.

## CI/CD

GitHub Actions (`.github/workflows/docker-publish.yml`): pushes to `main` build and publish a Docker image to `ghcr.io`. Manual `workflow_dispatch` triggers deployment via SSH to a VM running `docker compose`.

## Language

The codebase, comments, and user-facing strings are in Russian.
