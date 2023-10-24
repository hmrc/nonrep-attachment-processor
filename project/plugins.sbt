addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.15.0")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.10.0")
addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "1.0.0")
// sbt-scoverage is not set to latest due to binary incompatibility with akka_http_xml
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.9.3")
