package game.server

import cats.effect.*
import cats.effect.std.*
import cats.syntax.all.*
import fs2.*
import game.shared.*
import upickle.default.*
import scala.util.Random

val GRID_W      = 30
val GRID_H      = 20
val FOOD_COUNT  = 20
val FOOD_POINTS = 5

val PLAYER_COLORS = Vector(
  "#e74c3c", "#3498db", "#2ecc71", "#f39c12",
  "#9b59b6", "#1abc9c", "#e67e22", "#e91e63",
  "#00bcd4", "#ff5722", "#8bc34a", "#673ab7"
)

def spawnOne(players: Map[String, Player], food: Set[(Int, Int)]): Option[(Int, Int)] =
  val occupied = players.values.map(p => (p.x, p.y)).toSet ++ food
  val free = for
    x <- (0 until GRID_W).toVector
    y <- (0 until GRID_H).toVector
    if !occupied.contains((x, y))
  yield (x, y)
  if free.isEmpty then None
  else Some(free(Random.nextInt(free.size)))

def initialFood(n: Int): Set[(Int, Int)] =
  val rng = Random
  var s = Set.empty[(Int, Int)]
  var attempts = 0
  while s.size < n && attempts < n * 20 do
    s += ((rng.nextInt(GRID_W), rng.nextInt(GRID_H)))
    attempts += 1
  s

class GameState(
  playersRef:    Ref[IO, Map[String, Player]],
  connsRef:      Ref[IO, Map[String, Queue[IO, Option[String]]]],
  connPlayerRef: Ref[IO, Map[String, String]],
  colorIdxRef:   Ref[IO, Int],
  foodRef:       Ref[IO, Set[(Int, Int)]]
):
  private def broadcastCurrent(): IO[Unit] =
    for
      ps   <- playersRef.get
      food <- foodRef.get
      json  = write(ServerState(ps, food.map(p => FoodPos(p._1, p._2)).toList, GRID_W, GRID_H))
      conns <- connsRef.get
      _     <- conns.values.toList.parTraverse_(_.offer(Some(json)))
    yield ()

  def connect(connId: String): IO[Queue[IO, Option[String]]] =
    for
      q <- Queue.bounded[IO, Option[String]](256)
      _ <- connsRef.update(_ + (connId -> q))
    yield q

  def disconnect(connId: String): IO[Unit] =
    for
      connPlayer <- connPlayerRef.get
      pidOpt      = connPlayer.get(connId)
      conns      <- connsRef.get
      _          <- conns.get(connId).traverse_(_.offer(None))
      _          <- connsRef.update(_ - connId)
      _          <- connPlayerRef.update(_ - connId)
      _          <- pidOpt.traverse_ { pid =>
                      for
                        ps <- playersRef.get
                        _  <- ps.get(pid).traverse_ { p =>
                                val ghost = p.copy(online = false)
                                playersRef.update(_.updated(pid, ghost)) *>
                                GameApi.savePlayerData(pid, p.x, p.y, p.points)
                              }
                        _  <- broadcastCurrent()
                      yield ()
                    }
    yield ()

  def handle(connId: String, msg: ClientMsg): IO[Unit] = msg match
    case ClientMsg.Join(name, clientId, userId) =>
      val playerId = if userId.nonEmpty then userId
                     else if clientId.nonEmpty then clientId
                     else connId
      for
        _        <- connPlayerRef.update(_.updated(connId, playerId))
        existing <- playersRef.get.map(_.get(playerId))
        _        <- existing match
                      case Some(p) =>
                        playersRef.update(_.updated(playerId, p.copy(name = name, online = true)))
                      case None =>
                        for
                          idx  <- colorIdxRef.getAndUpdate(i => (i + 1) % PLAYER_COLORS.size)
                          color = PLAYER_COLORS(idx)
                          safeName = name.take(12).trim match
                            case "" => "Player"
                            case n  => n
                          saved <- GameApi.loadPlayerData(playerId)
                          sx    = saved.map(_.x).getOrElse(GRID_W / 2)
                          sy    = saved.map(_.y).getOrElse(GRID_H / 2)
                          sp    = saved.map(_.points).getOrElse(0)
                          p     = Player(playerId, sx, sy, color, safeName, online = true, points = sp)
                          _    <- playersRef.update(_.updated(playerId, p))
                        yield ()
        _ <- broadcastCurrent()
      yield ()

    case ClientMsg.Move(dx, dy) =>
      for
        connPlayer <- connPlayerRef.get
        _          <- connPlayer.get(connId).traverse_(handleMove(connId, _, dx, dy))
      yield ()

  private def handleMove(connId: String, playerId: String, dx: Int, dy: Int): IO[Unit] =
    for
      ps <- playersRef.get
      _  <- ps.get(playerId).traverse_ { p =>
              val nx = (p.x + dx).max(0).min(GRID_W - 1)
              val ny = (p.y + dy).max(0).min(GRID_H - 1)
              if nx == p.x && ny == p.y then IO.unit
              else
                for
                  food    <- foodRef.get
                  ateFood  = food.contains((nx, ny))
                  bonus    = if ateFood then FOOD_POINTS else 0
                  moved    = p.copy(x = nx, y = ny, points = p.points + 1 + bonus)
                  _       <- playersRef.update(_.updated(playerId, moved))
                  _       <- eatFood(food, nx, ny, ateFood)
                  _       <- wanderOneFood()
                  _       <- broadcastCurrent()
                yield ()
            }
    yield ()

  private def eatFood(food: Set[(Int, Int)], nx: Int, ny: Int, ate: Boolean): IO[Unit] =
    if !ate then IO.unit
    else
      for
        ps2       <- playersRef.get
        newFood    = food - ((nx, ny))
        respawned  = spawnOne(ps2, newFood)
        _         <- foodRef.set(respawned.fold(newFood)(newFood + _))
      yield ()

  private def wanderOneFood(): IO[Unit] =
    for
      food <- foodRef.get
      ps   <- playersRef.get
      _    <- if food.isEmpty then IO.unit
              else
                val pellets = food.toVector
                val picked  = pellets(Random.nextInt(pellets.size))
                val without = food - picked
                spawnOne(ps, without) match
                  case None        => IO.unit
                  case Some(newPos) => foodRef.set(without + newPos)
    yield ()

object GameState:
  def make: IO[GameState] =
    for
      players    <- Ref.of[IO, Map[String, Player]](Map.empty)
      conns      <- Ref.of[IO, Map[String, Queue[IO, Option[String]]]](Map.empty)
      connPlayer <- Ref.of[IO, Map[String, String]](Map.empty)
      colorIdx   <- Ref.of[IO, Int](0)
      food       <- Ref.of[IO, Set[(Int, Int)]](initialFood(FOOD_COUNT))
    yield GameState(players, conns, connPlayer, colorIdx, food)
