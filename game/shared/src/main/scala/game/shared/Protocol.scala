package game.shared

import upickle.default.*

case class Player(
  id: String,
  x: Int,
  y: Int,
  color: String,
  name: String,
  online: Boolean,
  points: Int
) derives ReadWriter

sealed trait ClientMsg derives ReadWriter
object ClientMsg:
  case class Join(name: String, clientId: String, userId: String) extends ClientMsg derives ReadWriter
  case class Move(dx: Int, dy: Int) extends ClientMsg derives ReadWriter

case class ServerState(
  players: Map[String, Player],
  gridW: Int,
  gridH: Int
) derives ReadWriter
