# Pixel Grid — Cooperative Game

Pixel Grid is a full-stack Scala browser game: Scala.js renders the canvas
client, http4s owns the real-time simulation, and Express provides browser
authentication plus PostgreSQL persistence.

## Run

- Project workflow: `cd game && SCALA_PORT=9000 sbt "client/fastLinkJS; server/run"`
- API service: `PORT=8080 pnpm --filter @workspace/api-server dev`
- Web service: `PORT=24402 SCALA_PORT=9000 pnpm --filter @workspace/pixel-game dev`
- Scala.js rebuild: `cd game && sbt client/fastLinkJS`

The Replit artifact definitions start the API and web services. The root
workflow starts the Scala server.

## Active repository map

- `game/shared`: upickle WebSocket protocol shared by JVM and Scala.js
- `game/client`: Scala.js canvas renderer and DOM HUD controller
- `game/server`: authoritative simulation and WebSocket server
- `artifacts/pixel-game`: Vite shell that builds and serves generated `main.js` and
  proxies `/api` and `/ws`
- `artifacts/api-server`: browser OIDC, health check, and game persistence API
- `lib/api-spec`: OpenAPI source used to generate `lib/api-zod`
- `lib/api-zod`: generated request/response schemas used by the API server
- `lib/db`: Drizzle schema and PostgreSQL connection
- `scripts/post-merge.sh`: dependency install and database schema sync

## Architecture

- The http4s server owns gameplay state and advances it once per second.
- The Scala.js client renders server snapshots; canvas handles the playfield
  while the DOM handles status, shop controls, leaderboard, and reset.
- Logged-in players use Replit OIDC and PostgreSQL persistence. Guests can play
  without authentication and are session-only.
- Vite serves fast-linked Scala.js during development and the optimized linker
  output in production.

## Gotchas

- Compile `client/fastLinkJS` before starting the Vite development server.
  The production web build runs `client/fullLinkJS` itself.
- Set `SCALA_PORT` consistently in the Scala server and Vite proxy.
- The API server requires `DATABASE_URL`; browser OIDC also requires
  `REPL_ID`.
