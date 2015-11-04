import akka.event.NoLogging
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.{HttpResponse, HttpRequest}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.stream.scaladsl.Flow
import org.scalatest._

class ServiceSpec extends FlatSpec with Matchers with ScalatestRouteTest with DevoxxTwitterProxyService {
  override def testConfigSource = "akka.loglevel = WARNING"
  override def config = testConfig
  override val logger = NoLogging

  val tweet = Tweet("jankeesvanandel", "1234567890", "Hello world")
  val tweets = List(tweet)

//  "Service" should "respond to single IP query" in {
//    Get(s"/tweets/devoxx") ~> routes ~> check {
//      status shouldBe OK
//      contentType shouldBe `application/json`
//      responseAs[List[Tweet]] shouldBe tweets
//    }
//  }

}
