import java.util

import com.ibm.watson.developer_cloud.alchemy.v1.AlchemyLanguage
import com.ibm.watson.developer_cloud.alchemy.v1.model.LanguageSelection

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import scala.collection.JavaConverters._

/**
  * Created by jankeesvanandel on 09/11/2016.
  */
object SentimentTest extends App {

  import scala.concurrent.ExecutionContext.Implicits.global

  private val alchemyApiKeys = ""
  private val services: List[AlchemyLanguage] = alchemyApiKeys.split(',').toList.map { key =>
    val service = new AlchemyLanguage()
    service.setApiKey(key)
    service
  }
  private var currentService: Int = 0

  private def takeService(): AlchemyLanguage = {
    val newValue = currentService + 1
    if (newValue >= services.length) {
      currentService = 0
    } else {
      currentService = newValue
    }

    services(currentService)
  }

  def addSentiment(newTweet: Tweet): Unit = {
    def tryGetSentiment(languages: List[LanguageSelection]): Option[String] = {
      if (languages.isEmpty) {
        println("Empty")
        None
      } else {
        val params: util.Map[String, AnyRef] = Map(
          AlchemyLanguage.TEXT -> newTweet.message
        ).asJava.asInstanceOf[util.Map[String, AnyRef]]
        println("taking service...")
        val service = takeService()
        println(s"service: $service")
        val sentiment = Try(service.getSentiment(params).execute())
        println("yield")
        sentiment match {
          case Success(sentiment) =>
            Some(s"Result: ${sentiment.getSentiment.getType.toString}")
          case Failure(e) =>
            println(s"Cannot determine sentiment, because of: $e")
            None
        }
      }
    }

    val languages = List(LanguageSelection.DETECT)

    val sentiment = tryGetSentiment(languages)
    sentiment match {
      case Some(s) =>
        println(s"Sentiment: $s")
      case None =>
        println("No result")
    }

  }
  println("Starting...")
  val tweet = Tweet(12345L, 12345L, "1234567890", "http://url/", "ceci est un test", None, None)
  addSentiment(tweet)
  println("Done")
}
