enablePlugins(JavaAppPackaging)

name := "devoxx-twitterproxy"
organization := "com.devoxx"
version := "1.0"
scalaVersion := "2.11.12"

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

libraryDependencies ++= {
  val akkaV = "2.4.11"
  val scalaTestV = "2.2.6"
  val twitter4jStream = "4.0.5"
  Seq(
    "com.typesafe.akka" %% "akka-actor" % akkaV,
    "com.typesafe.akka" %% "akka-stream" % akkaV,
    "com.typesafe.akka" %% "akka-http-experimental" % akkaV,
    "com.typesafe.akka" %% "akka-http-spray-json-experimental" % akkaV,
    "com.typesafe.akka" %% "akka-http-testkit" % akkaV,

    "org.scalatest" %% "scalatest" % scalaTestV % "test",

    "org.twitter4j" % "twitter4j-core" % twitter4jStream,
    "org.twitter4j" % "twitter4j-stream" % twitter4jStream,

    "com.amazonaws" % "aws-java-sdk-dynamodb" % "1.11.328",

    "com.ibm.watson.developer_cloud" % "java-sdk" % "5.1.1"
  )
}

Revolver.settings
