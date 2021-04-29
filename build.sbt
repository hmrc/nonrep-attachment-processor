enablePlugins(GitVersioning)

val akkaHttpVersion = "10.2.4"
val akkaVersion = "2.6.14"
val awsSdkVersion = "2.16.+"
val logbackVersion = "1.2.3"
val metricsVersion = "1.5.1"
val prometheusClientsVersion = "0.10.0"
val circeVersion = "0.12.3"

val projectName = "attachment-processor"

lazy val createVersionFile = taskKey[Unit]("Create version file")
createVersionFile := {
  import java.nio.file.{Paths, Files}
  import java.nio.charset.StandardCharsets
  Files.write(Paths.get("version.txt"), version.value.getBytes(StandardCharsets.UTF_8))
}

lazy val IntegrationTest = config("it") extend Test

lazy val root = (project in file(".")).
  configs(IntegrationTest).
  enablePlugins(BuildInfoPlugin).
  settings(
    Defaults.itSettings,
    inThisBuild(List(
      organization := "uk.gov",
      git.useGitDescribe := true,
      scalaVersion := "2.13.5"
    )),
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "uk.gov.hmrc.nonrep.attachment",
    name := projectName,

    resolvers ++= Seq(
      Resolver.bintrayRepo("lonelyplanet", "maven"),
      Resolver.bintrayRepo("hmrc", "releases")
    ),

    libraryDependencies ++= Seq(
      // Akka
      "com.typesafe.akka"    %% "akka-http"            % akkaHttpVersion,
      "com.typesafe.akka"    %% "akka-http-xml"        % akkaHttpVersion,
      "com.typesafe.akka"    %% "akka-http-spray-json" % akkaHttpVersion,
      "com.typesafe.akka"    %% "akka-actor-typed"     % akkaVersion,
      "com.typesafe.akka"    %% "akka-stream"          % akkaVersion,

      "de.heikoseeberger" % "akka-http-circe_2.13" % "1.36.0",

      // AWS
      "software.amazon.awssdk" % "s3"      % awsSdkVersion,
      "software.amazon.awssdk" % "glacier" % awsSdkVersion,

      // Logging
      "ch.qos.logback"       %  "logback-classic"          % logbackVersion,
      "ch.qos.logback"       %  "logback-core"             % logbackVersion,
      "com.typesafe.akka"    %% "akka-slf4j"               % akkaVersion,
      "org.slf4j"            %  "slf4j-api"                % "1.7.30",
      "net.logstash.logback" %  "logstash-logback-encoder" % "6.6",

      // Metrics
      "fr.davit"             %% "akka-http-metrics-prometheus" % metricsVersion,
      "io.prometheus"        %  "simpleclient_common"          % prometheusClientsVersion,
      "io.prometheus"        %  "simpleclient_dropwizard"      % prometheusClientsVersion,
      "io.prometheus"        %  "simpleclient_hotspot"         % prometheusClientsVersion,

      // Test dependencies
      "com.typesafe.akka"    %% "akka-http-testkit"        % akkaHttpVersion % Test,
      "com.typesafe.akka"    %% "akka-actor-testkit-typed" % akkaVersion     % Test,
      "com.typesafe.akka"    %% "akka-stream-testkit"      % akkaVersion     % Test,
      "org.scalatest"        %% "scalatest"                % "3.2.7"         % Test,
      "org.scalamock"        %% "scalamock"                % "5.1.0"         % Test
    ),

    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core",
      "io.circe" %% "circe-generic",
      "io.circe" %% "circe-parser"
    ).map(_ % circeVersion),

    assembly / assemblyJarName := s"$projectName.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
      case PathList("META-INF", "BCKEY.DSA") => MergeStrategy.discard
      case "reference.conf" => MergeStrategy.concat
      case _ => MergeStrategy.first
    }

  )

Compile / scalacOptions ++= Seq("-deprecation", "-feature")
Test / testOptions += Tests.Argument("-oF")
Test / fork := true
Test / envVars := Map("WORKING_DIR" -> "/tmp/unit-tests")
