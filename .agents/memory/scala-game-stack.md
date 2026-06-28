---
name: Scala game stack
description: Running a full Scala stack (Scala.js client + http4s WebSocket server) inside the pnpm monorepo, with key gotchas.
---

## Setup

- Java installed via `installProgrammingLanguage({ language: "java-graalvm22.3" })`
- SBT installed via `installSystemDependencies({ packages: ["sbt"] })`
- SBT project lives at `game/` (outside `artifacts/`), uses crossProject for shared code

## Architecture

- `game/shared/` — upickle-serialized protocol, cross-compiled for JVM + Scala.js
- `game/client/` — Scala.js game; output at `game/client/target/scala-3.3.4/client-fastopt/main.js`
- `game/server/` — http4s-ember WebSocket server; reads `SCALA_PORT` env var
- `artifacts/pixel-game/` — react-vite artifact (shell only); vite `publicDir` points to the Scala.js output dir; `/ws` proxied to Scala server

## Key gotchas

**Port conflicts**: Port 8080 was already in use. Use 9000 or check with `ss -tlnp` first.

**SBT startup timeout**: First SBT run downloads all dependencies (can take 3–5 min). Do NOT use `waitForPort` in the workflow — it will time out and mark the workflow as failed. Configure without `waitForPort` and let it run in the background.

**Scala.js JS interop**: `js.Math.random()` returns a Scala `Double`, NOT a JS number. Calling `.toString(36)` fails at compile time. Use `(js.Math.random() * 0xFFFFFFF).toInt.toHexString` instead.

**SBT multi-command**: `sbt "client/fastLinkJS; server/run"` compiles Scala.js first, then runs the JVM server — this is the correct workflow command.

**ModuleKind.NoModule**: Produces a single self-executing `main.js`. Load with `<script src="/main.js">` (not `type="module"`). Entry is called automatically via `scalaJSUseMainModuleInitializer := true`.

**Why:** Learned during first build of this cooperative grid game.
