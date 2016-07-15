import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{HttpOriginRange, `Access-Control-Allow-Origin`}
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import spray.json.{DefaultJsonProtocol, _}
import twitter4j._

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.{Failure, Success, Try}

case class Tweet(id: Long, from: String, profileImageUrl: String, message: String)
case class ErrorMessage(message: String)

trait DevoxxTwitterProxyService extends App with DefaultJsonProtocol {

  // Implicits
  implicit val system = ActorSystem()
  implicit val executor = system.dispatcher
  implicit val materializer = ActorMaterializer()

  def config = ConfigFactory.load()
  def logger = Logging(system, getClass)

  implicit val tweetFormat = jsonFormat4(Tweet.apply)

  private val twitter = Try(new TwitterFactory().getInstance())

  // Useless comment
  var cache: mutable.Map[String, (Long, List[Tweet])]
  val cacheTime: Long

  // Fetch tweets, from cache if possible.
  def fetchWithCache(event: String, sinceId: Long): Try[List[Tweet]] = {
    val eventCache = cache(event)
    if (eventCache._1 + cacheTime <= System.currentTimeMillis()) {
      val tweets = fetchTweets(event, sinceId)
      logger.debug("Retrieving tweets from Twitter")
      tweets.map { tweetsList =>
        logger.debug(s"Putting ${tweetsList.size} in cache")
        cache.put(event, (System.currentTimeMillis(), tweetsList))
        tweetsList
      }
    } else {
      logger.debug("Retrieving tweets from cache")
      Success(eventCache._2)
    }
  }

  private def fetchTweets(event: String, sinceId: Long): Try[List[Tweet]] = {
    twitter.map { t =>
      val yearLong = DateTime(System.currentTimeMillis()).year
      val yearShort = yearLong - 2000
      val query = new Query(s"#$event OR #$event$yearShort OR #$event$yearLong OR @$event")
      query.setCount(100)
      query.setSinceId(sinceId)
      t.search(query).getTweets.asScala.toList
    }.map { (tweets: List[Status]) =>
      tweets.map { tweet =>
        Tweet(
          tweet.getId,
          tweet.getUser.getName,
          tweet.getUser.getProfileImageURL,
          tweet.getText)
      }
    }
  }

  val routes = {
    logRequestResult("akka-http-microservice") {
      pathPrefix("tweets" / ".*{1,20}".r) { event =>
        path(LongNumber) { sinceId =>
          get {
            respondWithHeader(`Access-Control-Allow-Origin`.forRange(HttpOriginRange.*)) {
              complete {
                fetchWithCache(event, sinceId) match {
                  case Success(tweets) => StatusCodes.OK -> tweets.toJson.compactPrint
                  case Failure(exception) => StatusCodes.BadRequest -> exception.getMessage
                }
              }
            }
          }
        }
      }
    }
  }

}

object DevoxxTwitterProxy extends DevoxxTwitterProxyService {

  var cache: mutable.Map[String, (Long, List[Tweet])] = mutable.Map.empty.withDefaultValue((0L, Nil))
  val cacheTime: Long = 10000 // 10 seconds cache time

  Http().bindAndHandle(routes, config.getString("http.interface"), config.getInt("http.port"))
}
