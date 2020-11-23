import java.util.concurrent.TimeUnit

import akka.Done
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.{Cancellable, CoordinatedShutdown}
import akka.http.scaladsl._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.{Accept, RawHeader}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, MediaTypes}
import akka.kafka.scaladsl.{Consumer, Producer}
import akka.kafka.{ConsumerSettings, ProducerSettings, Subscriptions}
import akka.stream.alpakka.json.scaladsl.JsonReader
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.util.ByteString
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}

import scala.concurrent.Future
import scala.concurrent.duration._

object Main extends App {
  implicit val actorSystem: ActorSystem[Nothing] = ActorSystem[Nothing](Behaviors.empty, "alpakka-samples")

  import actorSystem.executionContext

  val uri = "https://pro-api.coinmarketcap.com/v1/cryptocurrency/listings/latest"
  val apiKey = sys.env("CMC_PRO_API_KEY")

  val httpRequest = HttpRequest(uri = uri)
    .withHeaders(
      Accept(MediaTypes.`application/json`),
      RawHeader("X-CMC_PRO_API_KEY", apiKey)
    )

  def extractEntityData(response: HttpResponse): Source[ByteString, _] =
    response match {
      case HttpResponse(OK, _, entity, _) => entity.dataBytes
      case notOkResponse =>
        Source.failed(new RuntimeException(s"illegal response $notOkResponse"))
    }

  val bootstrapServers: String = "localhost:9092"

  val kafkaProducerSettings = ProducerSettings(actorSystem.toClassic, new StringSerializer, new StringSerializer)
    .withBootstrapServers(bootstrapServers)

  val (ticks, future): (Cancellable, Future[Done]) =
    Source
      .tick(1.seconds, 7.seconds, httpRequest)
      .mapAsync(1)(Http()(actorSystem.toClassic).singleRequest(_))
      .flatMapConcat(extractEntityData)
      .via(JsonReader.select("$.data[*]"))
      .map(_.utf8String)
      .map(new ProducerRecord[String, String]("topic1", _))
      .toMat(Producer.plainSink(kafkaProducerSettings))(Keep.both)
      .run()

  val cs: CoordinatedShutdown = CoordinatedShutdown(actorSystem)
  cs.addTask(CoordinatedShutdown.PhaseServiceStop, "shut-down-client-http-pool")(() =>
    Http()(actorSystem.toClassic).shutdownAllConnectionPools().map(_ => Done)
  )

  val kafkaConsumerSettings = ConsumerSettings(actorSystem.toClassic, new StringDeserializer, new StringDeserializer)
    .withBootstrapServers(bootstrapServers)
    .withGroupId("topic1")
    .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

  val control = Consumer
    .atMostOnceSource(kafkaConsumerSettings, Subscriptions.topics("topic1"))
    .map(_.value)
    .toMat(Sink.foreach(println))(Keep.both)
    .mapMaterializedValue(Consumer.DrainingControl.apply[Done])
    .run()

  TimeUnit.SECONDS.sleep(59)
  ticks.cancel()

  for {
    _ <- future
    _ <- control.drainAndShutdown()
  } {
    cs.run(CoordinatedShutdown.UnknownReason)
  }
}
