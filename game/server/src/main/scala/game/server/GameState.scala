package game.server

import cats.effect.*
import cats.effect.std.*
import cats.syntax.all.*
import fs2.*
import fs2.concurrent.*
import game.shared.*
import upickle.default.*

val GRID_W = 30
val GRID_H = 20

val PLAYER_COLORS = Vector(
  "#e74c3c", "#3498db", "#2ecc71", "#f39c12",
  "#9b59b6", "#1abc9c", "#e67e22", "#e91e63",
  "#00bcd4", "#ff5722", "#8bc34a", "#673ab7"
)

class GameState(
  playersRef: Ref[IO, Map[String, Player]],
  connsRef: Ref[IO, Map[String, Queue[IO, Option[String]]]],
  connPlayerRef: Ref[IO, Map[String, String]],
  colorIdxRef: Ref[IO, Int]
):
  private def broadcast(state: ServerState): IO[Unit] =
    val json = write(state)
    for
      conns <- connsRef.get
      _ <- conns.values.toList.parTraverse_(_.offer(Some(json)))
    yield ()

  def connect(connId: String): IO[Queue[IO, Option[String]]] =
    for
      q <- Queue.bounded[IO, Option[String]](256)
      _ <- connsRef.update(_ + (connId -> q))
    yield q

  def disconnect(connId: String): IO[Unit] =
    for
      connPlayer <- connPlayerRef.get
      playerId = connPlayer.get(connId)
      conns <- connsRef.get
      _ <- conns.get(connId).traverse_(_.offer(None))
      _ <- connsRef.update(_ - connId)
      _ <- connPlayerRef.update(_ - connId)
      _ <- playerId.traverse_(pid => playersRef.update(_ - pid))
      ps <- playersRef.get
      _ <- broadcast(ServerState(ps, GRID_W, GRID_H))
    yield ()

  def handle(connId: String, msg: ClientMsg): IO[Unit] = msg match
    case ClientMsg.Join(name, clientId) =>
      val playerId = if clientId.nonEmpty then clientId else connId
      for
        idx <- colorIdxRef.getAndUpdate(i => (i + 1) % PLAYER_COLORS.size)
        color = PLAYER_COLORS(idx)
        safeName = name.take(12).trim match
          case "" => "Player"
          case n  => n
        player = Player(playerId, GRID_W / 2, GRID_H / 2, color, safeName)
        _ <- playersRef.update(_.updated(playerId, player))
        _ <- connPlayerRef.update(_.updated(connId, playerId))
        ps <- playersRef.get
        _ <- broadcast(ServerState(ps, GRID_W, GRID_H))
      yield ()

    case ClientMsg.Move(dx, dy) =>
      for
        connPlayer <- connPlayerRef.get
        _ <- connPlayer.get(connId).traverse_ { playerId =>
          for
            ps <- playersRef.get
            _ <- ps.get(playerId).traverse_ { p =>
              val nx = (p.x + dx).max(0).min(GRID_W - 1)
              val ny = (p.y + dy).max(0).min(GRID_H - 1)
              if nx != p.x || ny != p.y then
                for
                  _ <- playersRef.update(_.updated(playerId, p.copy(x = nx, y = ny)))
                  ps2 <- playersRef.get
                  _ <- broadcast(ServerState(ps2, GRID_W, GRID_H))
                yield ()
              else IO.unit
            }
          yield ()
        }
      yield ()

object GameState:
  def make: IO[GameState] =
    for
      players <- Ref.of[IO, Map[String, Player]](Map.empty)
      conns <- Ref.of[IO, Map[String, Queue[IO, Option[String]]]](Map.empty)
      connPlayer <- Ref.of[IO, Map[String, String]](Map.empty)
      colorIdx <- Ref.of[IO, Int](0)
    yield GameState(players, conns, connPlayer, colorIdx)
