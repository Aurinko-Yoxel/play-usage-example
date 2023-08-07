package controllers

import akka.stream.scaladsl.{Flow, Keep, Sink}
import akka.util.ByteString
import javax.inject.Inject
import play.api.Logging
import play.api.libs.json.JsValue
import play.api.libs.streams.Accumulator
import play.api.mvc.{Action, BaseController, BodyParser, ControllerComponents}
import scala.concurrent.ExecutionContext

/** */
class TrafficDemoController @Inject()(val controllerComponents: ControllerComponents)
  extends BaseController with Logging {

  implicit val ec: ExecutionContext = controllerComponents.executionContext

  def simpleJsonAction: Action[JsValue] = Action(parse.json) { request =>
    logger.info(s"Incoming body: ${ request.body }")
    Ok("Ok")
  }

  def countIncomingTrafficNaive: Action[JsValue] = Action(parse.json) { request =>
    val incomingLength = request.headers.get("Content-Length").getOrElse(0)
    logger.info(s"Incoming body ($incomingLength): ${ request.body }")

    Ok("Ok")
  }

  def countIncomingTrafficBetter: Action[Int] = Action(countingBodyParser) { request =>
    val incomingLength = request.body
    logger.info(s"Incoming length: $incomingLength bytes")

    Ok("Ok")
  }

  def countIncomingTrafficFinal: Action[(Int, JsValue)] =
    Action(combinedParser(countingBodyParser, parse.json)) { request =>
      val (incomingLength, jsonBody) = request.body
      logger.info(s"Incoming body($incomingLength): $jsonBody")

      Ok("Ok")
    }

  val countingBodyParser: BodyParser[Int] = BodyParser("countingBodyParser") { _ =>

    Accumulator {
      Sink
        .fold(0) { (total, bytes: ByteString) =>
          val chunkSize = bytes.length
          val newTotal = total + chunkSize
          logger.info(s"Received chunk of length: $chunkSize bytes, total so far: $newTotal bytes")
          newTotal
        }
        .mapMaterializedValue { _.map(Right(_)) }
    }
  }

  def combinedParser[A, B](aParser: BodyParser[A], bParser: BodyParser[B]): BodyParser[(A, B)] =
    BodyParser(s"CombinedParser: $aParser + $bParser") { request =>
      val sinkA = aParser(request).toSink
      val sinkB = bParser(request).toSink

      val sinkCombined = Flow[ByteString]
        .alsoToMat(sinkA)(Keep.right)
        .toMat(sinkB)(Keep.both)
        .mapMaterializedValue { case (aFuture, bFuture) =>
          // combine two Future[Either[_, _]] values into one:
          for {
            aEither <- aFuture
            bEither <- bFuture
          } yield for {
            a <- aEither
            b <- bEither
          } yield (a, b)
        }

      Accumulator(sinkCombined)
    }
}
