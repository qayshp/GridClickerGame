package game.server

import cats.effect.IO
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import upickle.default.*

case class SavedPosition(x: Int, y: Int) derives ReadWriter
case class SavePositionReq(userId: String, x: Int, y: Int) derives ReadWriter

object GameApi:
  private val apiBase = s"http://localhost:${sys.env.getOrElse("API_PORT", "8080")}"
  private val client  = HttpClient.newHttpClient()

  def loadPosition(userId: String): IO[Option[(Int, Int)]] =
    if userId.isEmpty then IO.pure(None)
    else IO.blocking {
      val req = HttpRequest.newBuilder()
        .uri(URI.create(s"$apiBase/api/game/user/$userId"))
        .GET()
        .build()
      val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
      if resp.statusCode() == 200 then
        val pos = read[SavedPosition](resp.body())
        Some((pos.x, pos.y))
      else None
    }.handleError(_ => None)

  def savePosition(userId: String, x: Int, y: Int): IO[Unit] =
    if userId.isEmpty then IO.unit
    else IO.blocking {
      val body = write(SavePositionReq(userId, x, y))
      val req = HttpRequest.newBuilder()
        .uri(URI.create(s"$apiBase/api/game/position"))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .header("Content-Type", "application/json")
        .build()
      client.send(req, HttpResponse.BodyHandlers.discarding())
      ()
    }.handleError(_ => ())
