package game.server

import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import game.shared.*
import upickle.default.*
import java.util.UUID

object Server extends IOApp:
  def run(args: List[String]): IO[ExitCode] =
    val port = sys.env.get("SCALA_PORT").flatMap(_.toIntOption).getOrElse(8081)
    for
      state <- GameState.make
      _ <- IO.println(s"Starting Scala WebSocket server on port $port")
      _ <- state.startTickLoop().start
      _ <- EmberServerBuilder.default[IO]
        .withHost(ipv4"0.0.0.0")
        .withPort(Port.fromInt(port).get)
        .withHttpWebSocketApp(wsb => routes(state, wsb).orNotFound)
        .build
        .useForever
    yield ExitCode.Success

  def routes(state: GameState, wsb: WebSocketBuilder2[IO]): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case GET -> Root / "ws" =>
        val connId = UUID.randomUUID().toString.take(8)

        for
          queue <- state.connect(connId)

          send = Stream.fromQueueNoneTerminated(queue)
            .map(WebSocketFrame.Text(_))

          receive: Pipe[IO, WebSocketFrame, Unit] = _.foreach:
            case WebSocketFrame.Text(text, _) =>
              IO.fromEither(scala.util.Try(read[ClientMsg](text)).toEither)
                .flatMap(state.handle(connId, _))
                .handleErrorWith(err => IO.println(s"[WS] Error: $err"))
            case WebSocketFrame.Close(_) =>
              state.disconnect(connId)
            case _ => IO.unit

          resp <- wsb
            .withOnClose(state.disconnect(connId))
            .build(send, receive)
        yield resp
