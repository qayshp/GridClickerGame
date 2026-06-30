package game.shared

import upickle.default.*

case class Player(
  id: String,
  x: Int,
  y: Int,
  color: String,
  name: String,
  online: Boolean,
  points: Int,
  upgrades: Set[String]
) derives ReadWriter

sealed trait ClientMsg derives ReadWriter
object ClientMsg:
  case class Join(name: String, clientId: String, userId: String) extends ClientMsg derives ReadWriter
  case class Move(dx: Int, dy: Int) extends ClientMsg derives ReadWriter
  case class BuyUpgrade(upgradeId: String) extends ClientMsg derives ReadWriter

case class FoodPos(x: Int, y: Int) derives ReadWriter

case class ServerState(
  players: Map[String, Player],
  food: List[FoodPos],
  gridW: Int,
  gridH: Int,
  eaten: List[FoodPos],    // pellets consumed by a player this tick
  wandered: List[FoodPos]  // pellets that moved randomly this tick
) derives ReadWriter
