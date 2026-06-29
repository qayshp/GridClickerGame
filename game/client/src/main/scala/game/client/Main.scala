package game.client

import org.scalajs.dom
import org.scalajs.dom.*
import org.scalajs.dom.html.Canvas
import game.shared.*
import upickle.default.*
import scala.scalajs.js

object Main:
  val GRID_W = 30
  val GRID_H = 20
  val MAX_CELL = 28
  val MIN_CELL = 8

  var cell: Int = MAX_CELL
  var players: Map[String, Player] = Map.empty
  var myId: String = ""
  var ws: WebSocket = null
  var connected = false

  def computeCell(): Int =
    val availW = dom.window.innerWidth.toInt - 4
    val availH = (dom.window.innerHeight * 0.82).toInt
    val c = Math.min(availW / GRID_W, availH / GRID_H)
    Math.max(MIN_CELL, Math.min(MAX_CELL, c))

  def main(args: Array[String]): Unit =
    dom.document.addEventListener("DOMContentLoaded", (_: Event) => init())

  def init(): Unit =
    val canvas = dom.document.getElementById("gameCanvas").asInstanceOf[Canvas]
    val ctx    = canvas.getContext("2d").asInstanceOf[CanvasRenderingContext2D]
    val status = dom.document.getElementById("status")

    def resize(): Unit =
      cell = computeCell()
      canvas.width  = cell * GRID_W
      canvas.height = cell * GRID_H
      renderFrame(ctx, canvas)

    resize()
    dom.window.addEventListener("resize", (_: Event) => resize())
    renderFrame(ctx, canvas)
    status.textContent = "Checking login…"

    val xhr = new dom.XMLHttpRequest()
    xhr.open("GET", "/api/auth/user")
    xhr.withCredentials = true
    xhr.onload = (_: dom.Event) =>
      if xhr.status == 200 then
        val data = js.JSON.parse(xhr.responseText).asInstanceOf[js.Dynamic]
        val user = data.user
        if user == null || js.isUndefined(user) then
          dom.window.location.href = "/api/login?returnTo=/"
        else
          val firstName = user.firstName.asInstanceOf[js.UndefOr[String]].getOrElse("")
          val userId    = user.id.asInstanceOf[js.UndefOr[String]].getOrElse("")
          val name = firstName.trim match
            case "" => "Player"
            case n  => n
          val logoutBtn = dom.document.getElementById("logout-btn")
          if logoutBtn != null then
            logoutBtn.asInstanceOf[html.Button].style.display = "inline-block"
          connectGame(name, userId, canvas, ctx, status)
      else
        dom.window.location.href = "/api/login?returnTo=/"
    xhr.onerror = (_: dom.Event) =>
      dom.window.location.href = "/api/login?returnTo=/"
    xhr.send()

  def connectGame(
    playerName: String,
    userId: String,
    canvas: Canvas,
    ctx: CanvasRenderingContext2D,
    status: dom.Element
  ): Unit =
    val proto = if dom.window.location.protocol == "https:" then "wss:" else "ws:"
    val wsUrl = s"${proto}//${dom.window.location.host}/ws"

    ws = new WebSocket(wsUrl)

    ws.onopen = (_: Event) =>
      connected = true
      myId = if userId.nonEmpty then userId
             else s"p-${(js.Math.random() * 0xFFFFFFF).toInt.toHexString}"
      send(ClientMsg.Join(playerName, myId, userId))

    ws.onmessage = (e: MessageEvent) =>
      val state = read[ServerState](e.data.toString)
      players = state.players
      players.get(myId).foreach { me =>
        status.textContent = s"Playing as: $playerName  •  ${me.points} pts"
      }
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

  def send(msg: ClientMsg): Unit =
    if ws != null && ws.readyState == WebSocket.OPEN then
      ws.send(write(msg))

  def renderFrame(ctx: CanvasRenderingContext2D, canvas: Canvas): Unit =
    val w = canvas.width
    val h = canvas.height
    val c = cell

    ctx.fillStyle = "#0d0d1a"
    ctx.fillRect(0, 0, w, h)

    ctx.strokeStyle = "#1a1a36"
    ctx.lineWidth = 1
    for x <- 0 to GRID_W do
      ctx.beginPath()
      ctx.moveTo(x * c, 0)
      ctx.lineTo(x * c, h)
      ctx.stroke()
    for y <- 0 to GRID_H do
      ctx.beginPath()
      ctx.moveTo(0, y * c)
      ctx.lineTo(w, y * c)
      ctx.stroke()

    for (_, p) <- players do
      drawPlayer(ctx, p, p.id == myId, c)

  def drawPlayer(ctx: CanvasRenderingContext2D, p: Player, isMe: Boolean, c: Int): Unit =
    val px = p.x * c
    val py = p.y * c

    ctx.save()

    if !p.online then ctx.globalAlpha = 0.35

    if isMe && p.online then
      ctx.shadowColor = p.color
      ctx.shadowBlur = 10
    else
      ctx.shadowBlur = 0

    ctx.fillStyle = if p.online then p.color else "#666688"
    ctx.fillRect(px + c/5, py + c*3/8, c*3/5, c*5/8 - 2)
    ctx.fillRect(px + c*3/10, py + c/10, c*2/5, c*3/8)

    if p.online then
      ctx.fillStyle = "#ffffff"
      ctx.fillRect(px + c*2/5, py + c/7, (c/9).max(2), (c/9).max(2))
      ctx.fillRect(px + c - c*2/5 - (c/9).max(2), py + c/7, (c/9).max(2), (c/9).max(2))
    else
      ctx.fillStyle = "#888899"
      ctx.fillRect(px + c*2/5, py + c/7 + (c/9).max(2)/2, (c/9).max(2), 1)
      ctx.fillRect(px + c - c*2/5 - (c/9).max(2), py + c/7 + (c/9).max(2)/2, (c/9).max(2), 1)

    if isMe && p.online then
      ctx.strokeStyle = "#ffffff"
      ctx.lineWidth = 1.0
      ctx.strokeRect(px + 1, py + 1, c - 2, c - 2)

    ctx.shadowBlur = 0

    if c >= 14 then
      ctx.fillStyle = if isMe then "#ffffff" else if p.online then "#aaaacc" else "#666688"
      val fontSize = Math.max(7, c / 4)
      ctx.font = s"bold ${fontSize}px monospace"
      ctx.textAlign = "center"
      val nameLine = if p.online then p.name else s"${p.name} (away)"
      ctx.fillText(nameLine, px + c / 2, py + c + fontSize)
      val ptsLine = s"${p.points} pts"
      ctx.font = s"${(fontSize * 0.85).toInt}px monospace"
      ctx.fillStyle = if isMe then "#aaffaa" else if p.online then "#7777aa" else "#555577"
      ctx.fillText(ptsLine, px + c / 2, py + c + fontSize * 2)

    ctx.restore()
