package game.server

import cats.effect.*
import cats.effect.std.*
import cats.syntax.all.*
import game.shared.*
import upickle.default.*
import scala.util.Random
import scala.concurrent.duration.*

val GRID_W      = 30
val GRID_H      = 20
val FOOD_COUNT  = 20
val FOOD_POINTS = 5

val UPGRADE_COSTS  = Map("pathfinder" -> 5, "sprint" -> 15, "bounty" -> 20, "magnet" -> 25, "repel" -> 30, "aura" -> 40, "blink" -> 60)
val MONSTER_COUNT  = 3
val MONSTER_DAMAGE = 5

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

// Returns (dx, dy) stepping toward the nearest food pellet
def dirTowardFood(p: Player, food: Set[(Int, Int)], dirs: Vector[(Int, Int)]): (Int, Int) =
  if food.isEmpty then dirs(Random.nextInt(dirs.size))
  else
    val nearest = food.minBy(f => Math.abs(f._1 - p.x) + Math.abs(f._2 - p.y))
    val rx = nearest._1 - p.x
    val ry = nearest._2 - p.y
    if rx == 0 && ry == 0 then dirs(Random.nextInt(dirs.size))
    else if Math.abs(rx) >= Math.abs(ry) then (rx.sign, 0)
    else (0, ry.sign)

private case class ConnectionPlayer(playerId: String, userId: Option[String])
private case class ScheduledSave(
  userId: String,
  player: Player,
  previous: Option[Deferred[IO, Unit]],
  completion: Deferred[IO, Unit]
)

class GameState(
  playersRef:    Ref[IO, Map[String, Player]],
  connsRef:      Ref[IO, Map[String, Queue[IO, Option[String]]]],
  connPlayersRef: Ref[IO, Map[String, ConnectionPlayer]],
  lifecycleMutex: Mutex[IO],
  saveTailsRef:   Ref[IO, Map[String, Deferred[IO, Unit]]],
  colorIdxRef:   Ref[IO, Int],
  foodRef:       Ref[IO, Set[(Int, Int)]],
  eatenRef:      Ref[IO, List[(Int, Int)]],
  wanderRef:     Ref[IO, List[(Int, Int)]],
  monstersRef:   Ref[IO, List[Monster]],
  tickCountRef:  Ref[IO, Int]
):
  private val DIRS = Vector((0,-1),(0,1),(-1,0),(1,0))

  private def broadcastCurrent(): IO[Unit] =
    for
      ps       <- playersRef.get
      food     <- foodRef.get
      eaten    <- eatenRef.get
      wandered <- wanderRef.get
      monsters <- monstersRef.get
      ticks    <- tickCountRef.get
      json      = write(ServerState(
                    ps,
                    food.map(p => FoodPos(p._1, p._2)).toList,
                    eaten.map(p => FoodPos(p._1, p._2)),
                    wandered.map(p => FoodPos(p._1, p._2)),
                    monsters,
                    ticks
                  ))
      conns <- connsRef.get
      _     <- conns.values.toList.parTraverse_(_.tryOffer(Some(json)).void)
    yield ()

  def connect(connId: String): IO[Queue[IO, Option[String]]] =
    for
      q <- Queue.bounded[IO, Option[String]](256)
      _ <- connsRef.update(_ + (connId -> q))
    yield q

  private def persistScheduled(save: ScheduledSave): IO[Unit] =
    val persistLatest =
      save.previous.traverse_(_.get) *>
        saveTailsRef.get.map(_.get(save.userId).contains(save.completion)).flatMap {
          case false => IO.unit
          case true =>
            val player = save.player
            GameApi.savePlayerData(
              save.userId,
              player.x,
              player.y,
              player.points,
              player.upgrades
            )
        }

    IO.uncancelable(_ => persistLatest).guarantee {
      save.completion.complete(()).void *>
        saveTailsRef.update { tails =>
          if tails.get(save.userId).contains(save.completion) then tails - save.userId
          else tails
        }
    }

  def disconnect(connId: String): IO[Unit] =
    IO.uncancelable { _ =>
      for
        queue <- connsRef.modify(conns => (conns - connId, conns.get(connId)))
        _     <- queue.traverse_(_.tryOffer(None).void)
        result <- lifecycleMutex.lock.surround {
                    for
                      finalConnection <- connPlayersRef.modify { connections =>
                                           val connection = connections.get(connId)
                                           val remaining  = connections - connId
                                           val isLast     = connection.filterNot { current =>
                                             remaining.values.exists(_.playerId == current.playerId)
                                           }
                                           (remaining, isLast)
                                         }
                      result <- finalConnection match
                                  case None => IO.pure((false, Option.empty[(String, Player)]))
                                  case Some(connection) =>
                                    playersRef.modify { players =>
                                      players.get(connection.playerId) match
                                        case None =>
                                          (players, (false, Option.empty[(String, Player)]))
                                        case Some(player) =>
                                          connection.userId match
                                            case Some(userId) =>
                                              val next = players.updated(
                                                connection.playerId,
                                                player.copy(online = false)
                                              )
                                              (next, (true, Some((userId, player))))
                                            case None =>
                                              (players - connection.playerId, (true, None))
                                    }
                      (changed, save) = result
                      scheduled <- save.traverse { case (userId, player) =>
                                     for
                                       completion <- Deferred[IO, Unit]
                                       previous <- saveTailsRef.modify { tails =>
                                                     (tails.updated(userId, completion), tails.get(userId))
                                                   }
                                     yield ScheduledSave(userId, player, previous, completion)
                                   }
                    yield (changed, scheduled)
                  }
        (changed, scheduled) = result
        _ <- scheduled.traverse_(persistScheduled)
        _ <- if changed then broadcastCurrent() else IO.unit
      yield ()
    }

  def handle(connId: String, msg: ClientMsg): IO[Unit] = msg match
    case ClientMsg.Join(name, clientId, userId) =>
      val playerId = if userId.nonEmpty then userId
                     else if clientId.nonEmpty then clientId
                     else connId
      for
        alreadyLoaded <- playersRef.get.map(_.contains(playerId))
        saved         <- if alreadyLoaded then IO.pure(None) else GameApi.loadPlayerData(userId)
        joined <- lifecycleMutex.lock.surround {
          for
            stillConnected <- connsRef.get.map(_.contains(connId))
            joined <- if !stillConnected then IO.pure(false)
                      else
                        for
                          _ <- connPlayersRef.update(_.updated(
                                 connId,
                                 ConnectionPlayer(playerId, Option(userId).filter(_.nonEmpty))
                               ))
                          existing <- playersRef.get.map(_.get(playerId))
                          _ <- existing match
                                 case Some(p) =>
                                   playersRef.update(_.updated(playerId, p.copy(name = name, online = true)))
                                 case None =>
                                   for
                                     idx  <- colorIdxRef.getAndUpdate(i => (i + 1) % PLAYER_COLORS.size)
                                     color = PLAYER_COLORS(idx)
                                     safeName = name.take(12).trim match
                                       case "" => "Player"
                                       case n  => n
                                     sx        = saved.map(_.x).getOrElse(GRID_W / 2)
                                     sy        = saved.map(_.y).getOrElse(GRID_H / 2)
                                     sp        = saved.map(_.points).getOrElse(0)
                                     su        = saved.map(_.upgrades).getOrElse("").split(",").filter(_.nonEmpty).toSet
                                     p         = Player(playerId, sx, sy, color, safeName, online = true, points = sp, upgrades = su)
                                     _        <- playersRef.update(_.updated(playerId, p))
                                   yield ()
                        yield true
          yield joined
        }
        _ <- if joined then broadcastCurrent() else IO.unit
      yield ()

    case ClientMsg.Reset() =>
      reset()

    case ClientMsg.Heartbeat() =>
      IO.unit

    case ClientMsg.BuyUpgrade(upgradeId) =>
      val cost = UPGRADE_COSTS.getOrElse(upgradeId, Int.MaxValue)
      for
        connPlayers <- connPlayersRef.get
        changed <- connPlayers.get(connId) match
                     case None => IO.pure(false)
                     case Some(connection) =>
                       playersRef.modify { players =>
                         players.get(connection.playerId) match
                           case Some(player)
                               if player.online &&
                                  player.points >= cost &&
                                  !player.upgrades.contains(upgradeId) =>
                             val upgraded = player.copy(
                               points   = player.points - cost,
                               upgrades = player.upgrades + upgradeId
                             )
                             (players.updated(connection.playerId, upgraded), true)
                           case _ => (players, false)
                       }
        _ <- if changed then broadcastCurrent() else IO.unit
      yield ()

  private def movePlayer(playerId: String, dx: Int, dy: Int): IO[Unit] =
    for
      food <- foodRef.get
      move <- playersRef.modify { players =>
                players.get(playerId) match
                  case Some(player) if player.online =>
                    val nx = (player.x + dx).max(0).min(GRID_W - 1)
                    val ny = (player.y + dy).max(0).min(GRID_H - 1)
                    if nx == player.x && ny == player.y then (players, None)
                    else
                      val ateFood   = food.contains((nx, ny))
                      val foodBonus = if !ateFood then 0
                                      else if player.upgrades.contains("bounty") then FOOD_POINTS * 2
                                      else FOOD_POINTS
                      val moved = player.copy(
                        x = nx,
                        y = ny,
                        points = player.points + 1 + foodBonus
                      )
                      (players.updated(playerId, moved), Some((nx, ny, ateFood)))
                  case _ => (players, None)
              }
      _ <- move.traverse_ { case (nx, ny, ateFood) =>
             if ateFood then eatenRef.update(_ :+ (nx, ny)) *> eatFood(food, nx, ny)
             else IO.unit
           }
    yield ()

  private def eatFood(food: Set[(Int, Int)], nx: Int, ny: Int): IO[Unit] =
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
                  case None         => IO.unit
                  case Some(newPos) => wanderRef.update(_ :+ picked) *> foodRef.set(without + newPos)
    yield ()

  // Collect any food adjacent to p (aura upgrade)
  private def applyAura(p: Player): IO[Unit] =
    val adjacent = Set(
      (p.x - 1, p.y), (p.x + 1, p.y),
      (p.x, p.y - 1), (p.x, p.y + 1)
    ).filter((x, y) => x >= 0 && x < GRID_W && y >= 0 && y < GRID_H)
    for
      food  <- foodRef.get
      nearby = food.intersect(adjacent)
      _     <- nearby.toList.traverse_ { pos =>
                 for
                   cur <- foodRef.get                        // re-check in case a prior eat cleared it
                   _   <- if !cur.contains(pos) then IO.unit
                          else
                            for
                              awarded <- playersRef.modify { players =>
                                           players.get(p.id) match
                                             case Some(current) if current.online =>
                                               val bonus =
                                                 if current.upgrades.contains("bounty") then FOOD_POINTS * 2
                                                 else FOOD_POINTS
                                               val updated = current.copy(points = current.points + bonus)
                                               (players.updated(p.id, updated), true)
                                             case _ => (players, false)
                                         }
                              _ <- (if awarded then eatenRef.update(_ :+ pos) *> eatFood(cur, pos._1, pos._2) else IO.unit)
                            yield ()
                 yield ()
               }
    yield ()

  // Pure: step the second-closest pellet one square toward p
  private def applyMagnet(p: Player, food: Set[(Int, Int)]): Set[(Int, Int)] =
    if food.size < 2 then food
    else
      val sorted  = food.toVector.sortBy(f => Math.abs(f._1 - p.x) + Math.abs(f._2 - p.y))
      val target  = sorted(1)
      val rx      = p.x - target._1
      val ry      = p.y - target._2
      if rx == 0 && ry == 0 then food
      else
        val (mx, my)  = if Math.abs(rx) >= Math.abs(ry) then (rx.sign, 0) else (0, ry.sign)
        val newPos    = (target._1 + mx, target._2 + my)
        val otherFood = food - target
        if otherFood.contains(newPos) then food   // blocked — leave in place
        else otherFood + newPos

  private def moveMonsters(): IO[Unit] =
    for
      ps <- playersRef.get
      online    = ps.values.filter(_.online).toVector
      repellers = online.filter(_.upgrades.contains("repel"))
      _ <- monstersRef.update { ms =>
              ms.map { m =>
                // 50% chase nearest player, 50% random
                val chaseDir =
                  if online.isEmpty || Random.nextDouble() < 0.5 then None
                  else
                    val t  = online.minBy(p => Math.abs(p.x - m.x) + Math.abs(p.y - m.y))
                    val rx = t.x - m.x; val ry = t.y - m.y
                    if rx == 0 && ry == 0 then None
                    else if Math.abs(rx) >= Math.abs(ry) then Some((rx.sign, 0))
                    else Some((0, ry.sign))
                val (dx, dy) = chaseDir.getOrElse(DIRS(Random.nextInt(DIRS.size)))
                val moved = m.copy(x = (m.x + dx).max(0).min(GRID_W - 1), y = (m.y + dy).max(0).min(GRID_H - 1))
                // Repel: push monster 1 step away from each nearby repeller (radius 3)
                repellers.foldLeft(moved) { (mon, p) =>
                  val dist = Math.abs(p.x - mon.x) + Math.abs(p.y - mon.y)
                  if dist > 0 && dist <= 3 then
                    val rx = mon.x - p.x; val ry = mon.y - p.y
                    val (pdx, pdy) = if Math.abs(rx) >= Math.abs(ry) then (rx.sign, 0) else (0, ry.sign)
                    mon.copy(x = (mon.x + pdx).max(0).min(GRID_W - 1), y = (mon.y + pdy).max(0).min(GRID_H - 1))
                  else mon
                }
              }
           }
      // Damage (or blink) any player sharing a cell with a monster
      ms     <- monstersRef.get
      mPos    = ms.map(m => (m.x, m.y)).toSet
      food   <- foodRef.get
      curPs  <- playersRef.get
      hitList = curPs.values.filter(p => p.online && mPos.contains((p.x, p.y))).toList
      _      <- hitList.traverse_ { p =>
                   if p.upgrades.contains("blink") then
                     // Teleport to a random free cell (excludes players, food, and monster positions)
                     val othersMap = curPs.view.filterKeys(_ != p.id).toMap
                     spawnOne(othersMap, food ++ mPos) match
                       case None         => IO.unit   // grid full — no damage, no move
                       case Some((tx,ty)) =>
                         playersRef.update { players =>
                           players.get(p.id) match
                             case Some(current) if current.online =>
                               players.updated(p.id, current.copy(x = tx, y = ty))
                             case _ => players
                         }
                   else
                     playersRef.update { players =>
                       players.get(p.id) match
                         case Some(current) if current.online =>
                           players.updated(
                             p.id,
                             current.copy(points = (current.points - MONSTER_DAMAGE).max(0))
                           )
                         case _ => players
                     }
                 }
    yield ()

  def tick(): IO[Unit] =
    for
      _    <- tickCountRef.update(_ + 1)
      _    <- eatenRef.set(Nil)
      _    <- wanderRef.set(Nil)
      ps   <- playersRef.get
      food <- foodRef.get   // snapshot for pathfinder direction
      _    <- ps.values.toList.filter(_.online).traverse_ { p =>
                val (dx, dy) =
                  if p.upgrades.contains("pathfinder") then dirTowardFood(p, food, DIRS)
                  else DIRS(Random.nextInt(DIRS.size))
                val steps = if p.upgrades.contains("sprint") then 2 else 1
                List.fill(steps)(()).traverse_(_ => movePlayer(p.id, dx, dy))
              }
      ps2  <- playersRef.get   // re-read after moves for accurate positions
      // Aura: collect adjacent food for each aura player
      _    <- ps2.values.toList.filter(p => p.online && p.upgrades.contains("aura")).traverse_(applyAura)
      // Magnet: pull second-closest pellet one step toward each owner
      _    <- ps2.values.toList.filter(p => p.online && p.upgrades.contains("magnet")).traverse_ { p =>
                foodRef.update(applyMagnet(p, _))
              }
      _    <- moveMonsters()
      _    <- wanderOneFood()
      _    <- broadcastCurrent()
    yield ()

  def reset(): IO[Unit] =
    val freshFood = initialFood(FOOD_COUNT)
    for
      _ <- playersRef.update { ps =>
             ps.view.mapValues { p =>
               val (nx, ny) = (Random.nextInt(GRID_W), Random.nextInt(GRID_H))
               p.copy(points = 0, upgrades = Set.empty, x = nx, y = ny)
             }.toMap
           }
      _ <- foodRef.set(freshFood)
      _ <- eatenRef.set(Nil)
      _ <- wanderRef.set(Nil)
      _ <- monstersRef.set(
             List.fill(MONSTER_COUNT)(Monster(Random.nextInt(GRID_W), Random.nextInt(GRID_H)))
           )
      _ <- tickCountRef.set(0)
      _ <- broadcastCurrent()
    yield ()

  def startTickLoop(): IO[Nothing] =
    (IO.sleep(1.second) *> tick()).foreverM

object GameState:
  def make: IO[GameState] =
    for
      players    <- Ref.of[IO, Map[String, Player]](Map.empty)
      conns      <- Ref.of[IO, Map[String, Queue[IO, Option[String]]]](Map.empty)
      connPlayers <- Ref.of[IO, Map[String, ConnectionPlayer]](Map.empty)
      lifecycle  <- Mutex[IO]
      saveTails  <- Ref.of[IO, Map[String, Deferred[IO, Unit]]](Map.empty)
      colorIdx   <- Ref.of[IO, Int](0)
      food       <- Ref.of[IO, Set[(Int, Int)]](initialFood(FOOD_COUNT))
      eaten      <- Ref.of[IO, List[(Int, Int)]](Nil)
      wandered   <- Ref.of[IO, List[(Int, Int)]](Nil)
      initMs      = List.fill(MONSTER_COUNT)(Monster(Random.nextInt(GRID_W), Random.nextInt(GRID_H)))
      monsters   <- Ref.of[IO, List[Monster]](initMs)
      tickCount  <- Ref.of[IO, Int](0)
    yield GameState(players, conns, connPlayers, lifecycle, saveTails, colorIdx, food, eaten, wandered, monsters, tickCount)
