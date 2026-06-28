package game.client

import org.scalajs.dom
import org.scalajs.dom.*
import org.scalajs.dom.html.Canvas
import game.shared.*
import upickle.default.*
import scala.scalajs.js

object Main:
  val CELL  = 28
  val GRID_W = 30
  val GRID_H = 20

  var players: Map[String, Player] = Map.empty
  var myId: String = ""
  var ws: WebSocket = null
  var connected = false

  def main(args: Array[String]): Unit =
    dom.document.addEventListener("DOMContentLoaded", (_: Event) => init())

  def init(): Unit =
    val canvas = dom.document.getElementById("gameCanvas").asInstanceOf[Canvas]
    canvas.width  = CELL * GRID_W
    canvas.height = CELL * GRID_H
    val ctx = canvas.getContext("2d").asInstanceOf[CanvasRenderingContext2D]

    val status = dom.document.getElementById("status")

    val proto = if dom.window.location.protocol == "https:" then "wss:" else "ws:"
    val wsUrl = s"${proto}//${dom.window.location.host}/ws"

    ws = new WebSocket(wsUrl)

    ws.onopen = (_: Event) =>
      connected = true
      status.textContent = "Connected! Enter your name:"
      val raw = dom.window.prompt("Enter your name:", "")
      val name = if raw == null || raw.trim.isEmpty then "Player" else raw.trim
      myId = s"p-${(js.Math.random() * 0xFFFFFFF).toInt.toHexString}"
      send(ClientMsg.Join(name, myId))
      status.textContent = s"Playing as: $name"

    ws.onmessage = (e: MessageEvent) =>
      val state = read[ServerState](e.data.toString)
      players = state.players
      renderFrame(ctx, canvas)

    ws.onclose = (_: Event) =>
      connected = false
      status.textContent = "Disconnected. Refresh to reconnect."

    ws.onerror = (_: Event) =>
      status.textContent = "Connection error."

    dom.document.addEventListener("keydown", (e: KeyboardEvent) =>
      if connected then
        e.key match
          case "ArrowUp"    | "w" | "W" => e.preventDefault(); send(ClientMsg.Move(0, -1))
          case "ArrowDown"  | "s" | "S" => e.preventDefault(); send(ClientMsg.Move(0, 1))
          case "ArrowLeft"  | "a" | "A" => e.preventDefault(); send(ClientMsg.Move(-1, 0))
          case "ArrowRight" | "d" | "D" => e.preventDefault(); send(ClientMsg.Move(1, 0))
          case _ => ()
    )

    renderFrame(ctx, canvas)

  def send(msg: ClientMsg): Unit =
    if ws != null && ws.readyState == WebSocket.OPEN then
      ws.send(write(msg))

  def renderFrame(ctx: CanvasRenderingContext2D, canvas: Canvas): Unit =
    val w = canvas.width
    val h = canvas.height

    ctx.fillStyle = "#0d0d1a"
    ctx.fillRect(0, 0, w, h)

    ctx.strokeStyle = "#1a1a36"
    ctx.lineWidth = 1
    for x <- 0 to GRID_W do
      ctx.beginPath()
      ctx.moveTo(x * CELL, 0)
      ctx.lineTo(x * CELL, h)
      ctx.stroke()
    for y <- 0 to GRID_H do
      ctx.beginPath()
      ctx.moveTo(0, y * CELL)
      ctx.lineTo(w, y * CELL)
      ctx.stroke()

    for (_, p) <- players do
      drawPlayer(ctx, p, p.id == myId)

  def drawPlayer(ctx: CanvasRenderingContext2D, p: Player, isMe: Boolean): Unit =
    val px = p.x * CELL
    val py = p.y * CELL
    val s  = CELL

    if isMe then
      ctx.shadowColor = p.color
      ctx.shadowBlur = 12
    else
      ctx.shadowBlur = 0

    ctx.fillStyle = p.color
    ctx.fillRect(px + 5, py + 10, s - 10, s - 12)

    ctx.fillRect(px + 9, py + 3, s - 18, 9)

    ctx.fillStyle = "#ffffff"
    ctx.fillRect(px + 11, py + 5, 3, 3)
    ctx.fillRect(px + s - 14, py + 5, 3, 3)

    ctx.fillStyle = "#000000"
    ctx.fillRect(px + 12, py + 6, 1, 2)
    ctx.fillRect(px + s - 13, py + 6, 1, 2)

    if isMe then
      ctx.strokeStyle = "#ffffff"
      ctx.lineWidth = 1.5
      ctx.strokeRect(px + 2, py + 2, s - 4, s - 4)

    ctx.shadowBlur = 0

    ctx.fillStyle = if isMe then "#ffffff" else "#aaaacc"
    ctx.font = s"bold 9px monospace"
    ctx.textAlign = "center"
    ctx.fillText(p.name, px + s / 2, py + s + 9)
