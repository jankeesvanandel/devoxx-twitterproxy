import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{HttpOriginRange, `Access-Control-Allow-Origin`}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.{ActorMaterializer, Materializer}
import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.regions.Regions
import com.amazonaws.services.dynamodbv2.{AmazonDynamoDB, AmazonDynamoDBClientBuilder}
import com.amazonaws.services.dynamodbv2.document._
import com.amazonaws.services.dynamodbv2.model.{AttributeValue, ScanRequest}
import com.ibm.watson.developer_cloud.natural_language_understanding.v1.model.AnalysisResults
import com.typesafe.config.{Config, ConfigFactory}
import spray.json.{DefaultJsonProtocol, JsonFormat}
import twitter4j._
import spray.json._

import scala.collection.JavaConverters._
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

  private var lastCacheClean: Long = System.currentTimeMillis() // Every 1 minute a cache cleanup
  private val cacheCleanIntervalMs: Long = 1000 * 60 // Every 1 minute a cache cleanup
  private val cacheTtlMs: Long = 1000 * 60 * 60 // 1 hour cache time

  def doCleanCache(now: Long, cacheTtlMs: Long): Unit

  def cleanCachePeriodically(): Unit = {
    val now = System.currentTimeMillis()
    if (lastCacheClean + cacheCleanIntervalMs < now) {
      doCleanCache(now, cacheTtlMs)
      lastCacheClean = now
    }
  }
}

class DynamodbCacheHandler(logger: LoggingAdapter) extends CacheHandler with ViewModels {

  private val awsAccessKeyId = sys.props.get("awsAccessKeyId")
  private val awsSecretAccessKey = sys.props.get("awsSecretAccessKey").get
  private val credentials: BasicAWSCredentials = new BasicAWSCredentials(awsAccessKeyId.get, awsSecretAccessKey)
  private val client: AmazonDynamoDB = AmazonDynamoDBClientBuilder.standard()
    .withRegion(Regions.EU_WEST_1)
    .withCredentials(new AWSStaticCredentialsProvider(credentials))
    .build()

  private val dynamoDB: DynamoDB = new DynamoDB(client)
  private val table: Table = dynamoDB.getTable("devoxx-twitterproxy")

  def updateCache(newTweet: Tweet): Unit = {
    try {
      val json = newTweet.toJson.compactPrint
      val item = new Item()
        .withPrimaryKey("timestamp", newTweet.timestamp)
        .withPrimaryKey("id", newTweet.id)
        .withString("tweet", json)
      val putItemOutcome = table.putItem(item)
      putItemOutcome.getPutItemResult
    } catch {
      case e: Exception => logger.error(s"e: $e")
    }
  }

  def getFrom(since: Long): List[Tweet] = {
    val scanRequest: ScanRequest = new ScanRequest()
      .withTableName("devoxx-twitterproxy")
      .withFilterExpression("#ts >= :ts")
      .withLimit(100)
      .withExpressionAttributeNames(Map("#ts" -> "timestamp").asJava)
      .withExpressionAttributeValues(Map(":ts" -> new AttributeValue().withN(since.toString)).asJava)

    val items = client.scan(scanRequest).getItems.asScala.toList
    items.map { item =>
      val tweetString = item.get("tweet").getS
      val jsValue = tweetString.parseJson
      jsValue.convertTo[Tweet]
    }.filter(_.timestamp >= since)
      .sortBy(t => (t.timestamp, t.id))
  }

  override def doCleanCache(now: Long, cacheTtlMs: Long): Unit = {
//    DeleteItemOutcome outcome = table.deleteItem("Id", 101);

//    cache = cache.filter(_.timestamp > now + cacheTtlMs)
  }
}

object SingletonCacheHandler extends CacheHandler {
  private var cache: List[Tweet] = Nil

  def updateCache(newTweet: Tweet): Unit = {
    cache = cache :+ newTweet

    cleanCachePeriodically()
  }

  def getFrom(since: Long): List[Tweet] = {
    cache.filter(_.timestamp >= since)
  }

  override def doCleanCache(now: Long, cacheTtlMs: Long): Unit = {
    cache = cache.filter(_.timestamp > now + cacheTtlMs)
  }
}

class SentimentHandler(cacheHandler: CacheHandler, logger: LoggingAdapter)(implicit executionContext: ExecutionContext) {

  import com.ibm.watson.developer_cloud.natural_language_understanding.v1.NaturalLanguageUnderstanding

  private val service = new NaturalLanguageUnderstanding("2018-03-16", sys.props.get("nluUsername").get, sys.props.get("nluPassword").get)

  private def getSentiment(tweet: Tweet): Try[AnalysisResults] = {
    import com.ibm.watson.developer_cloud.natural_language_understanding.v1.model.AnalyzeOptions
    import com.ibm.watson.developer_cloud.natural_language_understanding.v1.model.Features
    import com.ibm.watson.developer_cloud.natural_language_understanding.v1.model.EntitiesOptions
    val entitiesOptions = new EntitiesOptions.Builder().emotion(true).sentiment(true).limit(2).build
    val features = new Features.Builder().entities(entitiesOptions).build
    val options = new AnalyzeOptions.Builder().text(tweet.message).features(features).build
    Try(service.analyze(options).execute())
  }

  def addSentiment(newTweet: Tweet): Unit = {
    for {
      sentimentTry <- Future(getSentiment(newTweet))
    } yield {
      val tweetWithSentiment = sentimentTry match {
        case Success(sentiment) =>
          val avgSentiment = sentiment.getEntities.asScala.map(_.getSentiment.getScore.doubleValue()).sum
          newTweet.copy(sentiment = Some(avgSentiment match {
            case s if s < 0.5 => "NEGATIVE"
            case s if s < 0.7 => "NEUTRAL"
            case _ => "POSITIVE"
          }))
        case Failure(e) =>
          logger.warning(s"Cannot determine sentiment, because of: $e")
          newTweet
      }

      cacheHandler.updateCache(tweetWithSentiment)
    }

  }
}

class TwitterStreamer(sentimentHandler: SentimentHandler, logger: LoggingAdapter) {

  private val events = Array(Event("devoxx", "pl", "18"))

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
      pathPrefix("tweets" / LongNumber) { sinceId =>
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

//  sentimentHandler.addSentiment(Tweet(
//    1L,
//    1L,
//    "",
//    "",
//    "Devoxx is zo cool",
//    None, None
//  ))
}
