import sbt.Def
import sbtassembly.AssemblyPlugin.autoImport.assembly

enablePlugins(SbtGitVersioning)


val awsSdkVersion = "2.17.+"
val logbackVersion = "1.2.3"
val metricsVersion = "1.0.0"
val jvmMetricsVersion = "3.0.2"

val pekkoVersion = "1.0.2"
val pekkoHttpVersion = "1.0.1"
val prometheusClientsVersion = "0.16.0"

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
      majorVersion := 0,
      scalaVersion := "2.13.8"
    )),
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "uk.gov.hmrc.nonrep",
    name := projectName,

    resolvers ++= Seq(
      Resolver.bintrayRepo("lonelyplanet", "maven"),
      Resolver.bintrayRepo("hmrc", "releases")
    ),

    libraryDependencies ++= Seq(
      // Pekko
      "org.apache.pekko"    %% "pekko-http"            % pekkoHttpVersion,
      "org.apache.pekko"    %% "pekko-http-xml"        % pekkoHttpVersion,
      "org.apache.pekko"    %% "pekko-http-spray-json" % pekkoHttpVersion,
      "org.apache.pekko"    %% "pekko-actor-typed"     % pekkoVersion,
      "org.apache.pekko"    %% "pekko-stream"          % pekkoVersion,

      "org.apache.pekko" %% "pekko-connectors-sqs" % pekkoVersion,
      "org.apache.pekko" %% "pekko-connectors-s3" % pekkoVersion,

      "org.scala-lang.modules" %% "scala-java8-compat"  % "1.0.2",

      // AWS
      "software.amazon.awssdk" % "s3"      % awsSdkVersion,
      "software.amazon.awssdk" % "glacier" % awsSdkVersion,

      // Logging
      "ch.qos.logback"       %  "logback-classic"          % logbackVersion,
      "ch.qos.logback"       %  "logback-core"             % logbackVersion,
      "org.apache.pekko"    %% "pekko-slf4j"               % pekkoVersion,
      "org.slf4j"            %  "slf4j-api"                % "1.7.30",
      "net.logstash.logback" %  "logstash-logback-encoder" % "6.6",

      // Metrics
      "fr.davit"             %% "pekko-http-metrics-prometheus" % metricsVersion,
      "com.codahale.metrics" %  "metrics-jvm"                  % jvmMetricsVersion,
      "io.prometheus"        %  "simpleclient_common"          % prometheusClientsVersion,
      "io.prometheus"        %  "simpleclient_dropwizard"      % prometheusClientsVersion,
      "io.prometheus"        %  "simpleclient_hotspot"         % prometheusClientsVersion,

      // Test dependencies
      "org.apache.pekko"    %% "pekko-http-testkit"        % pekkoHttpVersion % Test,
      "org.apache.pekko"    %% "pekko-actor-testkit-typed" % pekkoVersion     % Test,
      "org.apache.pekko"    %% "pekko-stream-testkit"      % pekkoVersion     % Test,
      "org.scalatest"        %% "scalatest"                % "3.2.11"        % Test,
      "org.mockito"          %% "mockito-scala-scalatest"  % "1.17.0"        % Test
    ),

    assembly / assemblyJarName := s"$projectName.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
      case PathList("META-INF", "BCKEY.DSA") => MergeStrategy.discard
      case "reference.conf" => MergeStrategy.concat
      case _ => MergeStrategy.first
    },
    assembly / test := Def.sequential(
      Test / test,
      IntegrationTest / test
    ).value
  )

Compile / scalacOptions ++= Seq("-deprecation", "-feature")
Test / testOptions += Tests.Argument("-oF")
Test / fork := true
Test / envVars := Map("WORKING_DIR" -> "/tmp/unit-tests")
