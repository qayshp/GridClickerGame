# Pixel Grid Game

A cooperative/competitive real-time pixel art game built with **Scala.js** (frontend) and **http4s + Cats Effect** (backend), running over WebSockets.

---

## How to Play

Players auto-move every second — no keyboard input needed. Your goal is to rack up points by eating food pellets while avoiding monsters.

- **Yellow pellets** are food. Your player drifts toward the nearest one automatically.
- **Monsters** (marked `M`) roam the grid, occasionally chasing players. Getting caught costs you **5 points**.
- **Score** accumulates from eating food. Spend points in the Shop to buy upgrades.

---

## Upgrades (Shop)

| Upgrade | Cost | Effect |
|---|---|---|
| Pathfinder ★ | 5 pts | Actively navigates toward the nearest food pellet |
| Sprint ⚡ | 15 pts | Moves 2 squares per tick, consuming both cells |
| Bounty ✦ | 20 pts | Food pays 10 pts instead of 5 |
| Magnet ◆ | 25 pts | Pulls the nearest pellet 1 step closer each tick |
| Repel ⊗ | 30 pts | Pushes monsters away if they come within 3 squares |
| Aura ◉ | 40 pts | Auto-eats any food in the 8 adjacent cells after moving |
| Blink ◈ | 60 pts | Teleports you to a safe cell instead of taking monster damage |

Upgrades are permanent for the session. Multiple upgrades stack.

---

## Visual Guide

- **Colored squares** — players. Each player gets a unique color.
- **Yellow dots** — food pellets.
- **Red `M`** — monsters.
- **Corner badges** on a player square indicate upgrades:
  - ★ top-right (Pathfinder) · ◆ top-left (Magnet) · ⚡ bottom-right (Sprint) · ✦ bottom-left (Bounty)
- **Rings** around a player square:
  - Green inner ring — Aura
  - Blue middle ring — Repel
  - Teal outer ring — Blink

---

## Reset

The **⟳ Reset Game** button (below the leaderboard) resets all players' scores and upgrades, respawns food and monsters, and restarts the tick counter. A confirmation dialog appears before anything happens.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Game client | Scala.js 1.18 + scalajs-dom |
| Game server | Scala 3.3, http4s-ember, Cats Effect 3, WebSockets |
| API server | Node.js + Express |
| Auth | Replit Auth (OpenID Connect) |
| Database | PostgreSQL (player persistence) |
| Build | SBT + pnpm monorepo |

---

## Development

### Prerequisites
- Java 21 (GraalVM)
- SBT 1.10+
- Node.js / pnpm

### Build & Run

**Compile the Scala code:**
```bash
cd game && sbt "client/fastLinkJS; server/compile"
```

**Start the game server** (port 9000):
```bash
cd game && SCALA_PORT=9000 sbt "server/run"
```

**Start the API server** (port 8080):
```bash
PORT=8080 pnpm --filter @workspace/api-server dev
```

**Start the frontend** (Vite, port 24402):
```bash
PORT=24402 SCALA_PORT=9000 pnpm --filter @workspace/pixel-game dev
```

After recompiling Scala.js, the Vite dev server picks up the new JS automatically — no restart needed. Only restart the game server (`server/run`) after changing server-side Scala.

**Create a production web build:**
```bash
pnpm --filter @workspace/pixel-game build
```

The production build runs the optimized Scala.js linker before Vite, so it is
self-contained on a fresh checkout.

Run `pnpm build` from the repository root to validate TypeScript, build both
services, link the Scala.js client, and compile the Scala server.
