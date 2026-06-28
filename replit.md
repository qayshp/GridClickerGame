# Pixel Grid — Cooperative Game

A cooperative pixel art grid game where multiple players move characters on a shared 30×20 grid in real time. Full Scala stack: Scala.js client + http4s WebSocket server.

## Run & Operate

- **Scala Game Server** workflow — `cd game && SCALA_PORT=9000 sbt "client/fastLinkJS; server/run"` (compiles Scala.js then starts http4s on port 9000)
- **artifacts/pixel-game: web** workflow — `pnpm --filter @workspace/pixel-game run dev` (vite dev server, proxies `/ws` → Scala server)
- To recompile Scala.js only: `cd game && sbt client/fastLinkJS`
- To restart the server only: kill the workflow and rerun (SBT caches, restarts in ~5s)

## Stack

- **Game client**: Scala.js 1.18.2 + scalajs-dom, compiled to `game/client/target/scala-3.3.4/client-fastopt/main.js`
- **Game server**: Scala 3.3.4 + http4s-ember (WebSocket) + cats-effect + upickle
- **Frontend shell**: Vite dev server at `/` (serves the compiled Scala.js + proxies `/ws`)
- **Shared protocol**: `game/shared/` cross-compiled for JVM + Scala.js (upickle JSON)
- SBT 1.10.11, Java GraalVM 22.3.1

## Where things live

_Populate as you build — short repo map plus pointers to the source-of-truth file for DB schema, API contracts, theme files, etc._

## Architecture decisions

_Populate as you build — non-obvious choices a reader couldn't infer from the code (3-5 bullets)._

## Product

_Describe the high-level user-facing capabilities of this app once they exist._

## User preferences

_Populate as you build — explicit user instructions worth remembering across sessions._

## Gotchas

_Populate as you build — sharp edges, "always run X before Y" rules._

## Pointers

- See the `pnpm-workspace` skill for workspace structure, TypeScript setup, and package details
