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
  val ANIM_DUR = 500.0

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

    // Wire up the shop buttons
    val pfBtn = dom.document.getElementById("upgrade-pathfinder-btn")
    if pfBtn != null then
      pfBtn.addEventListener("click", (_: dom.Event) => send(ClientMsg.BuyUpgrade("pathfinder")))
    val mgBtn = dom.document.getElementById("upgrade-magnet-btn")
    if mgBtn != null then
      mgBtn.addEventListener("click", (_: dom.Event) => send(ClientMsg.BuyUpgrade("magnet")))
    val spBtn = dom.document.getElementById("upgrade-sprint-btn")
    if spBtn != null then
      spBtn.addEventListener("click", (_: dom.Event) => send(ClientMsg.BuyUpgrade("sprint")))
    val bnBtn = dom.document.getElementById("upgrade-bounty-btn")
    if bnBtn != null then
      bnBtn.addEventListener("click", (_: dom.Event) => send(ClientMsg.BuyUpgrade("bounty")))
    val auBtn = dom.document.getElementById("upgrade-aura-btn")
    if auBtn != null then
      auBtn.addEventListener("click", (_: dom.Event) => send(ClientMsg.BuyUpgrade("aura")))

    ws.onopen = (_: Event) =>
      connected = true
      myId = if userId.nonEmpty then userId
             else s"p-${(js.Math.random() * 0xFFFFFFF).toInt.toHexString}"
      send(ClientMsg.Join(playerName, myId, userId))
      val shopPanel = dom.document.getElementById("shop-panel")
      if shopPanel != null then
        shopPanel.asInstanceOf[html.Div].style.display = "flex"

    ws.onmessage = (e: MessageEvent) =>
      val state      = read[ServerState](e.data.toString)
      val newFoodSet = state.food.map(f => (f.x, f.y)).toSet
      val eaten      = prevFood -- newFoodSet
      val now        = dom.window.performance.now()
      val newAnims   = eaten.map(pos => EatAnim(pos._1, pos._2, now)).toList

      players  = state.players
      food     = state.food
      prevFood = newFoodSet

      if newAnims.nonEmpty then
        eatAnims = eatAnims.filter(a => now - a.startMs < ANIM_DUR) ++ newAnims
        kickAnims(ctx, canvas)

      updateLeaderboard(state.players)
      players.get(myId).foreach { me =>
        val pts = s"${me.points} pts"
        status.textContent =
          if userId.isEmpty then s"Guest  •  $pts  •  login to save"
          else s"Playing as: $playerName  •  $pts"
        updateShopUI(me)
      }

      renderFrame(ctx, canvas)

    ws.onclose = (_: Event) =>
      connected = false
      status.textContent = "Disconnected. Refresh to reconnect."

    ws.onerror = (_: Event) =>
      status.textContent = "Connection error."

  def updateShopUI(me: Player): Unit =
    def setBtn(id: String, label: String, desc: String, cost: Int, upgradeId: String): Unit =
      val el = dom.document.getElementById(id)
      if el == null then return
      val b = el.asInstanceOf[html.Button]
      val d = s"""<span class="btn-desc">$desc</span>"""
      if me.upgrades.contains(upgradeId) then
        b.innerHTML = s"""$label &nbsp;<span class="btn-owned">(owned)</span>$d"""
        b.disabled = true
        b.setAttribute("data-state", "owned")
      else if me.points >= cost then
        b.innerHTML = s"$label &mdash; $cost pts$d"
        b.disabled = false
        b.setAttribute("data-state", "")
      else
        b.innerHTML = s"""$label &mdash; $cost pts &nbsp;<span class="btn-need">need ${cost - me.points} more</span>$d"""
        b.disabled = true
        b.setAttribute("data-state", "")

    setBtn("upgrade-pathfinder-btn", "Pathfinder ★", "Moves toward nearest food",           5,  "pathfinder")
    setBtn("upgrade-sprint-btn",     "Sprint ⚡",    "2 squares/tick, eats both",            15, "sprint")
    setBtn("upgrade-bounty-btn",     "Bounty ✦",    "Food pays 10 pts instead of 5",        20, "bounty")
    setBtn("upgrade-magnet-btn",     "Magnet ◆",    "Pulls a pellet 1 step closer/tick",    25, "magnet")
    setBtn("upgrade-aura-btn",       "Aura ◉",      "Auto-eats adjacent food after moving", 40, "aura")

  def updateLeaderboard(ps: Map[String, Player]): Unit =
    val el = dom.document.getElementById("leaderboard")
    if el == null then return
    val sorted = ps.values.toList.sortWith { (a, b) =>
      if a.online != b.online then a.online
      else a.points > b.points
    }
    val chips = sorted.map { p =>
      val you  = if p.id == myId then """ <span class="lb-you">(you)</span>""" else ""
      val dot  = if p.online then """<span class="lb-dot on">●</span>""" else """<span class="lb-dot off">○</span>"""
      val cls  = if p.online then "" else " lb-offline"
      s"""<span class="lb-chip$cls">$dot ${p.name}$you <span class="lb-score">${p.points}</span></span>"""
    }.mkString
    el.innerHTML = s"""<span class="lb-label">Players</span>$chips"""

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
    val t     = ((now - a.startMs) / ANIM_DUR).min(1.0)
    val cx    = a.x * c + c / 2.0
    val cy    = a.y * c + c / 2.0
    val eased = 1.0 - Math.pow(1.0 - t, 2)
    val alpha = (1.0 - t) * 0.9

    ctx.save()
    ctx.globalAlpha = alpha

    val outerR = eased * c * 0.9
    ctx.strokeStyle = "#ffe84d"
    ctx.lineWidth   = (3 * (1.0 - t)).max(0.5)
    ctx.shadowColor = "#ffe84d"
    ctx.shadowBlur  = 10
    ctx.beginPath(); ctx.arc(cx, cy, outerR, 0, 2 * Math.PI); ctx.stroke()

    val innerR = eased * c * 0.45
    ctx.strokeStyle = "#ffffff"
    ctx.lineWidth   = (2 * (1.0 - t)).max(0.3)
    ctx.shadowBlur  = 0
    ctx.beginPath(); ctx.arc(cx, cy, innerR, 0, 2 * Math.PI); ctx.stroke()

    ctx.fillStyle   = "#ffe84d"
    ctx.shadowColor = "#ffe84d"
    ctx.shadowBlur  = 6
    val dotR = (c / 10).max(2).toDouble
    for i <- 0 until 6 do
      val angle = (i * Math.PI / 3.0) + t * 0.8
      val dist  = eased * c * 0.75
      val dx    = cx + Math.cos(angle) * dist
      val dy    = cy + Math.sin(angle) * dist
      ctx.beginPath(); ctx.arc(dx, dy, dotR * (1.0 - t * 0.6), 0, 2 * Math.PI); ctx.fill()

    ctx.restore()

  def drawFood(ctx: CanvasRenderingContext2D, f: FoodPos, c: Int): Unit =
    val fx   = f.x * c
    val fy   = f.y * c
    val size = (c / 3).max(4)
    val off  = (c - size) / 2
    ctx.save()
    ctx.shadowColor = "#ffe84d"; ctx.shadowBlur = 12
    ctx.fillStyle   = "#ffe84d"
    ctx.fillRect(fx + off, fy + off, size, size)
    val dot = (size / 3).max(1)
    ctx.fillStyle = "#fff9c4"
    ctx.fillRect(fx + off + dot, fy + off + dot, dot, dot)
    ctx.restore()

  def drawPlayer(ctx: CanvasRenderingContext2D, p: Player, isMe: Boolean, c: Int): Unit =
    val px = p.x * c
    val py = p.y * c
    val hasPf = p.upgrades.contains("pathfinder")

    ctx.save()

    // Aura ring (drawn first, behind everything)
    val hasAu = p.upgrades.contains("aura")
    if hasAu && p.online then
      ctx.globalAlpha = if !p.online then 0.1 else 0.28
      ctx.strokeStyle = "#88ff88"
      ctx.lineWidth   = 2
      ctx.strokeRect(px - 1, py - 1, c + 2, c + 2)
      ctx.globalAlpha = 1.0

    if !p.online then ctx.globalAlpha = 0.35

    if isMe && p.online then
      ctx.shadowColor = p.color; ctx.shadowBlur = 10
    else ctx.shadowBlur = 0

    // Body
    ctx.fillStyle = if p.online then p.color else "#666688"
    ctx.fillRect(px + c/5, py + c*3/8, c*3/5, c*5/8 - 2)
    ctx.fillRect(px + c*3/10, py + c/10, c*2/5, c*3/8)

    // Eyes
    if p.online then
      ctx.fillStyle = "#ffffff"
      ctx.fillRect(px + c*2/5, py + c/7, (c/9).max(2), (c/9).max(2))
      ctx.fillRect(px + c - c*2/5 - (c/9).max(2), py + c/7, (c/9).max(2), (c/9).max(2))
    else
      ctx.fillStyle = "#888899"
      ctx.fillRect(px + c*2/5, py + c/7 + (c/9).max(2)/2, (c/9).max(2), 1)
      ctx.fillRect(px + c - c*2/5 - (c/9).max(2), py + c/7 + (c/9).max(2)/2, (c/9).max(2), 1)

    // My-player border
    if isMe && p.online then
      ctx.strokeStyle = "#ffffff"; ctx.lineWidth = 1.0
      ctx.strokeRect(px + 1, py + 1, c - 2, c - 2)

    ctx.shadowBlur = 0

    // Corner badges
    val hasMg = p.upgrades.contains("magnet")
    val hasSp = p.upgrades.contains("sprint")
    val hasBn = p.upgrades.contains("bounty")
    if (hasPf || hasMg || hasSp || hasBn || hasAu) && p.online && c >= 14 then
      val badgeSize = Math.max(8, c / 3)
      ctx.font = s"${badgeSize}px monospace"
      ctx.shadowBlur = 8
      if hasPf then                          // top-right: gold ★
        ctx.textAlign = "right"
        ctx.shadowColor = "#ffe84d"; ctx.fillStyle = "#ffe84d"
        ctx.fillText("★", px + c - 1, py + badgeSize + 1)
      if hasMg then                          // top-left: cyan ◆
        ctx.textAlign = "left"
        ctx.shadowColor = "#44ddff"; ctx.fillStyle = "#44ddff"
        ctx.fillText("◆", px + 1, py + badgeSize + 1)
      if hasSp then                          // bottom-right: orange ⚡
        ctx.textAlign = "right"
        ctx.shadowColor = "#ff6622"; ctx.fillStyle = "#ff6622"
        ctx.fillText("⚡", px + c - 1, py + c - 2)
      if hasBn then                          // bottom-left: purple ✦
        ctx.textAlign = "left"
        ctx.shadowColor = "#bb44ff"; ctx.fillStyle = "#bb44ff"
        ctx.fillText("✦", px + 1, py + c - 2)
      ctx.shadowBlur = 0

    // Name + pts labels
    if c >= 14 then
      ctx.fillStyle = if isMe then "#ffffff" else if p.online then "#aaaacc" else "#666688"
      val fontSize = Math.max(7, c / 4)
      ctx.font = s"bold ${fontSize}px monospace"
      ctx.textAlign = "center"
      val nameLine = if p.online then p.name else s"${p.name} (away)"
      ctx.fillText(nameLine, px + c / 2, py + c + fontSize)
      ctx.font = s"${(fontSize * 0.85).toInt}px monospace"
      ctx.fillStyle = if isMe then "#aaffaa" else if p.online then "#7777aa" else "#555577"
      ctx.fillText(s"${p.points} pts", px + c / 2, py + c + fontSize * 2)

    ctx.restore()
