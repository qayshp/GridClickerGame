package game.server

import cats.effect.IO
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import upickle.default.*

case class SavedPlayerData(x: Int, y: Int, points: Int) derives ReadWriter
case class SavePlayerDataReq(userId: String, x: Int, y: Int, points: Int) derives ReadWriter

object GameApi:
  private val apiBase = s"http://localhost:${sys.env.getOrElse("API_PORT", "8080")}"
  private val client  = HttpClient.newHttpClient()

  def loadPlayerData(userId: String): IO[Option[SavedPlayerData]] =
    if userId.isEmpty then IO.pure(None)
    else IO.blocking {
      val req = HttpRequest.newBuilder()
        .uri(URI.create(s"$apiBase/api/game/user/$userId"))
        .GET()
        .build()
      val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
      if resp.statusCode() == 200 then Some(read[SavedPlayerData](resp.body()))
      else None
    }.handleError(_ => None)

  def savePlayerData(userId: String, x: Int, y: Int, points: Int): IO[Unit] =
    if userId.isEmpty then IO.unit
    else IO.blocking {
      val body = write(SavePlayerDataReq(userId, x, y, points))
      val req = HttpRequest.newBuilder()
        .uri(URI.create(s"$apiBase/api/game/position"))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .header("Content-Type", "application/json")
        .build()
      client.send(req, HttpResponse.BodyHandlers.discarding())
      ()
    }.handleError(_ => ())
