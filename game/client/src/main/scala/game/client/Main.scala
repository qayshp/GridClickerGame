package game.client

import org.scalajs.dom
import org.scalajs.dom.*
import org.scalajs.dom.html.Canvas
import game.shared.*
import upickle.default.*
import scala.scalajs.js

case class EatAnim(x: Int, y: Int, startMs: Double)

object Main:
  val GRID_W   = 30
  val GRID_H   = 20
  val MAX_CELL = 28
  val MIN_CELL = 8
  val ANIM_DUR = 500.0   // ms

  var cell: Int = MAX_CELL
  var players: Map[String, Player] = Map.empty
  var food: List[FoodPos]          = Nil
  var prevFood: Set[(Int, Int)]     = Set.empty
  var eatAnims: List[EatAnim]       = Nil
  var animating: Boolean            = false
  var myId: String  = ""
  var ws: WebSocket = null
  var connected     = false

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

    def startGuest(): Unit =
      val loginBtn = dom.document.getElementById("login-btn")
      if loginBtn != null then loginBtn.asInstanceOf[html.Anchor].style.display = "inline-block"
      connectGame("Guest", "", canvas, ctx, status)

    val xhr = new dom.XMLHttpRequest()
    xhr.open("GET", "/api/auth/user")
    xhr.withCredentials = true
    xhr.onload = (_: dom.Event) =>
      if xhr.status == 200 then
        val data = js.JSON.parse(xhr.responseText).asInstanceOf[js.Dynamic]
        val user = data.user
        if user == null || js.isUndefined(user) then
          startGuest()
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
        startGuest()
    xhr.onerror = (_: dom.Event) => startGuest()
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
      val state   = read[ServerState](e.data.toString)
      val newFoodSet = state.food.map(f => (f.x, f.y)).toSet

      // Detect eaten pellets: was in prev food, gone now, player is standing there
      val eaten    = prevFood -- newFoodSet
      val onlinePosSet = state.players.values.filter(_.online).map(p => (p.x, p.y)).toSet
      val now      = dom.window.performance.now()
      val newAnims = eaten.filter(onlinePosSet.contains).map(pos => EatAnim(pos._1, pos._2, now)).toList

      players  = state.players
      food     = state.food
      prevFood = newFoodSet
      if newAnims.nonEmpty then
        eatAnims = eatAnims.filter(a => now - a.startMs < ANIM_DUR) ++ newAnims
        kickAnims(ctx, canvas)

      players.get(myId).foreach { me =>
        val pts = s"${me.points} pts"
        status.textContent =
          if userId.isEmpty then s"Guest  •  $pts  •  login to save"
          else s"Playing as: $playerName  •  $pts"
      }
      renderFrame(ctx, canvas)

    ws.onclose = (_: Event) =>
      connected = false
      status.textContent = "Disconnected. Refresh to reconnect."

    ws.onerror = (_: Event) =>
      status.textContent = "Connection error."


  def send(msg: ClientMsg): Unit =
    if ws != null && ws.readyState == WebSocket.OPEN then
      ws.send(write(msg))

  // ---------- animation loop ----------

  def kickAnims(ctx: CanvasRenderingContext2D, canvas: Canvas): Unit =
    if !animating && eatAnims.nonEmpty then
      animating = true
      dom.window.requestAnimationFrame((_: Double) => tickAnims(ctx, canvas))

  def tickAnims(ctx: CanvasRenderingContext2D, canvas: Canvas): Unit =
    val now = dom.window.performance.now()
    eatAnims = eatAnims.filter(a => now - a.startMs < ANIM_DUR)
    renderFrame(ctx, canvas)
    if eatAnims.nonEmpty then
      dom.window.requestAnimationFrame((_: Double) => tickAnims(ctx, canvas))
    else
      animating = false

  // ---------- rendering ----------

  def renderFrame(ctx: CanvasRenderingContext2D, canvas: Canvas): Unit =
    val w = canvas.width
    val h = canvas.height
    val c = cell

    ctx.fillStyle = "#0d0d1a"
    ctx.fillRect(0, 0, w, h)

    ctx.strokeStyle = "#1a1a36"
    ctx.lineWidth = 1
    for x <- 0 to GRID_W do
      ctx.beginPath(); ctx.moveTo(x * c, 0); ctx.lineTo(x * c, h); ctx.stroke()
    for y <- 0 to GRID_H do
      ctx.beginPath(); ctx.moveTo(0, y * c); ctx.lineTo(w, y * c); ctx.stroke()

    for f <- food do drawFood(ctx, f, c)
    for (_, p) <- players do drawPlayer(ctx, p, p.id == myId, c)

    val now = dom.window.performance.now()
    for a <- eatAnims do drawEatAnim(ctx, a, c, now)

  def drawEatAnim(ctx: CanvasRenderingContext2D, a: EatAnim, c: Int, now: Double): Unit =
    val t      = ((now - a.startMs) / ANIM_DUR).min(1.0)   // 0 → 1
    val cx     = a.x * c + c / 2.0
    val cy     = a.y * c + c / 2.0
    val eased  = 1.0 - Math.pow(1.0 - t, 2)                // ease-out quad
    val alpha  = (1.0 - t) * 0.9

    ctx.save()
    ctx.globalAlpha = alpha

    // Outer expanding ring
    val outerR = eased * c * 0.9
    ctx.strokeStyle = "#ffe84d"
    ctx.lineWidth   = (3 * (1.0 - t)).max(0.5)
    ctx.shadowColor = "#ffe84d"
    ctx.shadowBlur  = 10
    ctx.beginPath()
    ctx.arc(cx, cy, outerR, 0, 2 * Math.PI)
    ctx.stroke()

    // Inner ring (smaller, faster)
    val innerR = eased * c * 0.45
    ctx.strokeStyle = "#ffffff"
    ctx.lineWidth   = (2 * (1.0 - t)).max(0.3)
    ctx.shadowBlur  = 0
    ctx.beginPath()
    ctx.arc(cx, cy, innerR, 0, 2 * Math.PI)
    ctx.stroke()

    // 6 sparkle dots flying outward
    ctx.fillStyle  = "#ffe84d"
    ctx.shadowColor = "#ffe84d"
    ctx.shadowBlur  = 6
    val dotR = (c / 10).max(2).toDouble
    for i <- 0 until 6 do
      val angle  = (i * Math.PI / 3.0) + t * 0.8
      val dist   = eased * c * 0.75
      val dx     = cx + Math.cos(angle) * dist
      val dy     = cy + Math.sin(angle) * dist
      ctx.beginPath()
      ctx.arc(dx, dy, dotR * (1.0 - t * 0.6), 0, 2 * Math.PI)
      ctx.fill()

    ctx.restore()

  def drawFood(ctx: CanvasRenderingContext2D, f: FoodPos, c: Int): Unit =
    val fx   = f.x * c
    val fy   = f.y * c
    val size = (c / 3).max(4)
    val off  = (c - size) / 2
    ctx.save()
    ctx.shadowColor = "#ffe84d"
    ctx.shadowBlur  = 12
    ctx.fillStyle   = "#ffe84d"
    ctx.fillRect(fx + off, fy + off, size, size)
    val dot = (size / 3).max(1)
    ctx.fillStyle = "#fff9c4"
    ctx.fillRect(fx + off + dot, fy + off + dot, dot, dot)
    ctx.restore()

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
