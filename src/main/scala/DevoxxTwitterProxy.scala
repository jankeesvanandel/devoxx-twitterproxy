import java.util

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{HttpOriginRange, `Access-Control-Allow-Origin`}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.{ActorMaterializer, Materializer}
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Regions
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient
import com.amazonaws.services.dynamodbv2.document.spec.ScanSpec
import com.amazonaws.services.dynamodbv2.document._
import com.ibm.watson.developer_cloud.alchemy.v1.AlchemyLanguage
import com.typesafe.config.{Config, ConfigFactory}
import spray.json.{DefaultJsonProtocol, JsonFormat}
import twitter4j._
import spray.json._

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}

case class Tweet(
  id: Long,
  timestamp: Long,
  from: String,
  profileImageUrl: String,
  message: String,
  imageUrl: Option[String],
  sentiment: Option[String] = None
)

case class ErrorMessage(message: String)

sealed trait ViewModels extends DefaultJsonProtocol {

  implicit val jfTweet: JsonFormat[Tweet] = jsonFormat7(Tweet.apply)

}

case class Event(name: String, country: String, year: String) {
  private val longYear = "20" + year
  def allPermutations: Array[String] = {
    Array(
      name,
      name + year,
      name + longYear,
      name + country,
      name + country + year,
      name + country + longYear
    )
  }
}

trait CacheHandler {
  def updateCache(newTweet: Tweet): Unit
  def getFrom(sinceId: Long): List[Tweet]
}

class DynamodbCacheHandler(logger: LoggingAdapter) extends CacheHandler with ViewModels {

  val credentials: BasicAWSCredentials = new BasicAWSCredentials("AKIAJYK3CWWXDDD327SA","Kcp5pvYLxyYFtiYp/OfJODrROll9gc9KDUhFKcNI")
  private val client: AmazonDynamoDBClient = new AmazonDynamoDBClient(credentials)
    .withRegion(Regions.EU_WEST_1)

  private val dynamoDB: DynamoDB = new DynamoDB(client)
  private val table: Table = dynamoDB.getTable("devoxx-twitterproxy")

  def updateCache(newTweet: Tweet): Unit = {
    try {
      val json = newTweet.toJson.compactPrint
      logger.info(s"Saving: $json")
      val item = new Item()
        .withPrimaryKey("timestamp", newTweet.timestamp)
        .withPrimaryKey("id", newTweet.id)
        .withString("tweet", json)
      val putItemOutcome = table.putItem(item)
      logger.info(s"putItemOutcome: $putItemOutcome")
      val putItemResult = putItemOutcome.getPutItemResult
      logger.info(s"putItemResult: $putItemResult")
    } catch {
      case e: Exception => logger.error(s"e: $e")
    }
  }

  def getFrom(since: Long): List[Tweet] = {
    val spec: ScanSpec = new ScanSpec()

    val items: ItemCollection[ScanOutcome] = table.scan(spec)

    println("All tweets")

    val iterator: util.Iterator[Item] = items.iterator()
    val buffer: ListBuffer[Tweet] = ListBuffer.empty
    while (iterator.hasNext) {
      val next = iterator.next()
      val tweetString = next.getString("tweet")
      val jsValue = tweetString.parseJson
      val tweet = jsValue.convertTo[Tweet]
      println(s"Tweet: $tweet")
      buffer.append(tweet)
    }

    buffer.toList
  }
}

object SingletonCacheHandler extends CacheHandler {
  private var cache: List[Tweet] = Nil
  private val cacheTimeMs: Long = 10000 // 10 seconds cache time

  def updateCache(newTweet: Tweet): Unit = {
    cache = cache :+ newTweet
  }

  def getFrom(since: Long): List[Tweet] = {
    cache.dropWhile(_.timestamp < since)
  }
}

class SentimentHandler(cacheHandler: CacheHandler, logger: LoggingAdapter)(implicit executionContext: ExecutionContext) {

  private val service: AlchemyLanguage = new AlchemyLanguage()
  service.setApiKey(sys.props.getOrElse("alchemyApiKey", sys.error("Missing property alchemyApiKey")))

  def addSentiment(newTweet: Tweet): Unit = {
    val params: util.Map[String, AnyRef] = Map(
      AlchemyLanguage.TEXT -> newTweet.message
    ).asJava.asInstanceOf[util.Map[String, AnyRef]]

    for {
      sentimentTry <- Future(Try(service.getSentiment(params).execute()))
    } yield {
      val tweetWithSentiment = sentimentTry match {
        case Success(sentiment) =>
          newTweet.copy(sentiment = Some(sentiment.getSentiment.getType.toString))
        case Failure(e) =>
          logger.warning(s"Cannot determine sentiment, because of: $e")
          newTweet
      }

      cacheHandler.updateCache(tweetWithSentiment)
    }

  }
}

class TwitterStreamer(sentimentHandler: SentimentHandler, logger: LoggingAdapter) {

  private val events = Array(Event("devoxx", "be", "16"))

  private val twitterFilters: Array[String] = events.flatMap(_.allPermutations)

  private class CacheStatusListener extends StatusListener {

    private def getImageUrl(tweet: Status): Option[String] = {
      val imageUrl = if (tweet.getMediaEntities != null) {
        tweet.getMediaEntities.headOption.flatMap(me => Option(me.getMediaURL))
      } else {
        None
      }
      imageUrl
    }

    override def onStatus(status: Status): Unit = {
      if (status.getUser != null) {
        val imageUrl = getImageUrl(status)
        val newTweet = Tweet(
          status.getId,
          status.getCreatedAt.getTime,
          status.getUser.getName,
          status.getUser.getProfileImageURL,
          status.getText,
          imageUrl)

        sentimentHandler.addSentiment(newTweet)
      }
    }
    override def onDeletionNotice(statusDeletionNotice: StatusDeletionNotice): Unit = {
      logger.info(s"onDeletionNotice: $statusDeletionNotice")
    }
    override def onTrackLimitationNotice(numberOfLimitedStatuses: Int): Unit = {
      logger.info(s"onTrackLimitationNotice: $numberOfLimitedStatuses")
    }
    override def onException(ex: Exception): Unit = {
      logger.error(s"onException: $ex")
    }
    override def onStallWarning(warning: StallWarning): Unit = {
      logger.error(s"onStallWarning: $warning")
    }
    override def onScrubGeo(userId: Long, upToStatusId: Long): Unit = {
      logger.error(s"onScrubGeo: $userId, $upToStatusId")
    }
  }

  private val twitterStream = new TwitterStreamFactory().getInstance()
  twitterStream.addListener(new CacheStatusListener)
  twitterStream.filter(twitterFilters: _*)

}

trait DevoxxTwitterProxyService extends ViewModels {

  implicit val system: ActorSystem
  implicit def executor: ExecutionContextExecutor
  implicit val materializer: Materializer

  def config: Config = ConfigFactory.load()

  def logger = Logging(system, getClass)

  val cacheHandler: CacheHandler
  val sentimentHandler: SentimentHandler
  val twitterStreamer: TwitterStreamer

  val routes: Route = {
    logRequestResult("devoxx-twitterproxy") {
      pathPrefix("tweets" / ".*{1,20}".r) { event =>
        path(LongNumber) { sinceId =>
          get {
            respondWithHeader(`Access-Control-Allow-Origin`.forRange(HttpOriginRange.*)) {
              complete {
                StatusCodes.OK -> cacheHandler.getFrom(sinceId)
              }
            }
          }
        }
      }
    }
  }
}

object DevoxxTwitterProxy extends App with DevoxxTwitterProxyService {

  override implicit val system: ActorSystem = ActorSystem()
  override implicit val executor: ExecutionContextExecutor = system.dispatcher
  override implicit val materializer: Materializer = ActorMaterializer()

  override val cacheHandler: CacheHandler = new DynamodbCacheHandler(logger)
  override val sentimentHandler: SentimentHandler = new SentimentHandler(cacheHandler, logger)
  override val twitterStreamer: TwitterStreamer = new TwitterStreamer(sentimentHandler, logger)

  Http().bindAndHandle(routes,
    config.getString("http.interface"),
    config.getInt("http.port"))
}
