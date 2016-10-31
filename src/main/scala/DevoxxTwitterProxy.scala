import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{HttpOriginRange, `Access-Control-Allow-Origin`}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.{ActorMaterializer, Materializer}
import com.typesafe.config.{Config, ConfigFactory}
import spray.json.{DefaultJsonProtocol, JsonFormat}
import twitter4j._

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.ExecutionContextExecutor

sealed trait ViewModels extends DefaultJsonProtocol {

  case class Tweet(
    id: Long,
    timestamp: Long,
    from: String,
    profileImageUrl: String,
    message: String,
    imageUrl: Option[String]
  )

  case class ErrorMessage(message: String)

  implicit val jfTweet: JsonFormat[Tweet] = jsonFormat6(Tweet.apply)

}

trait DevoxxTwitterProxyService extends ViewModels {

  implicit val system: ActorSystem
  implicit def executor: ExecutionContextExecutor
  implicit val materializer: Materializer

  def config = ConfigFactory.load()

  def logger = Logging(system, getClass)

  private val twitter = new TwitterFactory().getInstance()

  var cache: mutable.Map[String, (Long, List[Tweet])]
  val cacheTime: Long

  // Fetch tweets, from cache if possible.
  private def fetchWithCache(event: String, sinceId: Long): List[Tweet] = {
    val eventCache = cache(event)
    if (shouldLoadNewTweets(sinceId, eventCache)) {
      logger.debug("Retrieving tweets from Twitter")
      val tweets = fetchTweets(event, sinceId)

      val newTweetsCache = mergeNewTweetsWithCache(eventCache, tweets, sinceId)

      logger.debug(s"Putting ${tweets.size} in cache")
      cache.put(event, (System.currentTimeMillis(), newTweetsCache))

      newTweetsCache
    } else {
      logger.debug("Retrieving tweets from cache")
      eventCache._2
    }
  }

  private def shouldLoadNewTweets(sinceId: Long, eventCache: (Long, List[Tweet])): Boolean = {
    val cachedTime = eventCache._1
    sinceId == 0 ||
      sinceId < cachedTime ||
      cachedTime + cacheTime <= System.currentTimeMillis()
  }

  private def mergeNewTweetsWithCache(eventCache: (Long, List[Tweet]), newTweets: List[Tweet], sinceId: Long): List[Tweet] = {
    val cachedTweetsId = eventCache._1
    val cachedTweets = eventCache._2
    val oneHourBack = System.currentTimeMillis() - 1000 * 60 * 60

    // Drop all tweets older than one hour
    val lastIdInCache = cachedTweets.lastOption.map(_.id).getOrElse(0L)
    val lastTimestampInCache = cachedTweets.lastOption.map(_.timestamp).getOrElse(0L)

    //
    val cleanedUpCache = if (sinceId != 0) {
      cachedTweets.dropWhile(_.timestamp < oneHourBack)
    } else {
      Nil
    }
    val newTweetsCleanedUp = newTweets
      .dropWhile(_.timestamp < oneHourBack)
      .dropWhile(_.timestamp < lastTimestampInCache)

    val indexOfLastTweetInCache = newTweetsCleanedUp.indexWhere(_.id == lastIdInCache)
    val newTweetsCache = cleanedUpCache ++ newTweetsCleanedUp.drop(indexOfLastTweetInCache + 1)
    newTweetsCache
  }

  private def fetchTweets(event: String, sinceId: Long): List[Tweet] = {
    val yearLong = DateTime(System.currentTimeMillis()).year
    val yearShort = yearLong - 2000
    val query = new Query(s"#$event OR #$event$yearShort OR #$event$yearLong OR @$event")
    query.setCount(100)
    query.setSinceId(sinceId)
    twitter.search(query).getTweets.asScala.toList
        .filter(_.getUser != null)
        .map { tweet =>
      val imageUrl = getImageUrl(tweet)
      Tweet(
        tweet.getId,
        tweet.getCreatedAt.getTime,
        tweet.getUser.getName,
        tweet.getUser.getProfileImageURL,
        tweet.getText,
        imageUrl
      )
    }.sortBy(_.timestamp)
  }

  private def getImageUrl(tweet: Status): Option[String] = {
    val imageUrl = if (tweet.getMediaEntities != null) {
      tweet.getMediaEntities.headOption.flatMap(me => Option(me.getMediaURL))
    } else {
      None
    }
    imageUrl
  }

  val routes: Route = {
    logRequestResult("devoxx-twitterproxy") {
      pathPrefix("tweets" / ".*{1,20}".r) { event =>
        path(LongNumber) { sinceId =>
          get {
            respondWithHeader(`Access-Control-Allow-Origin`.forRange(HttpOriginRange.*)) {
              complete {
                StatusCodes.OK -> fetchWithCache(event, sinceId)
              }
            }
          }
        }
      }
    }
  }
}

object DevoxxTwitterProxy extends App with DevoxxTwitterProxyService {

  var cache: mutable.Map[String, (Long, List[Tweet])] = mutable.Map.empty.withDefaultValue((0L, Nil))
  val cacheTime: Long = 10 // 10 seconds cache time

  override implicit val system: ActorSystem = ActorSystem()
  override implicit val executor: ExecutionContextExecutor = system.dispatcher
  override implicit val materializer: Materializer = ActorMaterializer()

  Http().bindAndHandle(routes,
    config.getString("http.interface"),
    config.getInt("http.port"))
}
