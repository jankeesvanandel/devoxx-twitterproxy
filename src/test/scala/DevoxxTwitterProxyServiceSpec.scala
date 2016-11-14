import akka.event.NoLogging
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.stream.scaladsl.Flow
import org.scalatest._

import scala.collection.mutable

class DevoxxTwitterProxyServiceSpec extends FlatSpec with Matchers with ScalatestRouteTest with DevoxxTwitterProxyService {
  override def testConfigSource = "akka.loglevel = WARNING"
  override def config = testConfig
  override val logger = NoLogging

  val tweet = Tweet(12345L, 12345L, "1234567890", "http://url/", "Hello world", None, None)
  val tweets = List(tweet)

  it should "respond to a GET request" in {
    Get(s"/tweets/devoxx/0") ~> routes ~> check {
      status shouldBe OK
      contentType shouldBe `application/json`
//      responseAs[List[Tweet]] shouldBe tweets
    }
  }
  override val cacheHandler: CacheHandler = SingletonCacheHandler
  override val sentimentHandler: SentimentHandler = new SentimentHandler(cacheHandler, logger)
  override val twitterStreamer: TwitterStreamer = new TwitterStreamer(sentimentHandler, logger)
}
