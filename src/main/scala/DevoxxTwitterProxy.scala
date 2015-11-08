import java.io.{InputStreamReader, BufferedReader}

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.headers.{`Access-Control-Allow-Origin`, HttpOriginRange}
import akka.http.scaladsl.{HttpExt, Http}
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.{ActorMaterializer, Materializer}
import com.typesafe.config.{Config, ConfigFactory}
import spray.json.DefaultJsonProtocol
import twitter4j._
import twitter4j.auth.{AccessToken, RequestToken}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContextExecutor
import scala.util.{Failure, Success, Try}
import spray.json._
import DefaultJsonProtocol._

case class Tweet(id: Long, from: String, profileImageUrl: String, message: String)
case class ErrorMessage(message: String)

trait DevoxxTwitterProxyService extends App with DefaultJsonProtocol {
  implicit val system = ActorSystem()
  implicit val executor = system.dispatcher
  implicit val materializer = ActorMaterializer()

  implicit val tweetFormat = jsonFormat4(Tweet.apply)

  val twitter = Try(new TwitterFactory().getInstance())

  var cache: (Long, List[Tweet]) = (0, Nil)
  val cacheTime: Long = 10000 // 10 seconds cache time

  def fetchWithCache(sinceId: Long): Try[List[Tweet]] = {
    if (cache._1 + cacheTime <= System.currentTimeMillis()) {
      val tweets = fetchTweetsForDevoxx(sinceId)
      tweets.map{ tweetsList =>
        cache = (System.currentTimeMillis(), tweetsList)
      }

      tweets
    } else {
      Success(cache._2)
    }
  }

  def fetchTweetsForDevoxx(sinceId: Long): Try[List[Tweet]] = {
    twitter.map { t =>
      val query = new Query("#devoxx OR #devoxx15 OR #devoxx2015 OR @devoxx")
      query.setCount(100)
      query.setSinceId(sinceId)
      t.search(query).getTweets.asScala.toList
    }.map { tweets =>
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
      pathPrefix("tweets" / "devoxx" / LongNumber) { sinceId =>
        get {
          respondWithHeader(`Access-Control-Allow-Origin`.forRange(HttpOriginRange.*)) {
            complete {
              fetchWithCache(sinceId) match {
                case Success(tweets) => StatusCodes.OK -> tweets.toJson.compactPrint
                case Failure(exception) => StatusCodes.BadRequest -> exception.getMessage
              }
            }
          }
        }
      }
    }
  }

  def config = ConfigFactory.load()
  def logger = Logging(system, getClass)

}

object DevoxxTwitterProxy extends DevoxxTwitterProxyService {

  Http().bindAndHandle(routes, config.getString("http.interface"), config.getInt("http.port"))
}
