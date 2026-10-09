/*
 * Copyright 2023 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.nonrep.attachment.wiremockstubs

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder.allRequests
import org.apache.pekko.event.slf4j.Logger
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Suite}

import scala.jdk.CollectionConverters.CollectionHasAsScala

trait WireMockSupport extends BeforeAndAfterAll with BeforeAndAfterEach {
  this: Suite =>

  private val logger = Logger(this.getClass.getName)

  lazy val wireMockHost: String  =
    // this has to match the configuration in `internalServiceHostPatterns`
    "localhost"

  lazy val wireMockPort: Int = 9008

  lazy val wireMockRootDirectory: String =
    // wiremock doesn't look in the classpath, it uses src/test/resources by default.
    // since play projects use the non-standard `test/resources` we should attempt to identify the path
    // note, it may require `Test / fork := true` in sbt (or just override explicitly)
    System.getProperty("java.class.path").split(":").head

  lazy val wireMockServer: WireMockServer =
    new WireMockServer(
      wireMockConfig()
        .port(wireMockPort)
        .withRootDirectory(wireMockRootDirectory)
    )

  lazy val wireMockUrl: String =
    s"http://$wireMockHost:$wireMockPort"

  /** If true (default) it will clear the wireMock settings before each test */
  lazy val resetWireMockMappings: Boolean = true
  lazy val resetWireMockRequests: Boolean = true

  def startWireMock(): Unit = {
    println("starting wiremock")
    if (!wireMockServer.isRunning) {
      wireMockServer.start()
      println("started wiremock")
      // this initialises static access to `WireMock` rather than calling functions on the wireMockServer instance itself
      WireMock.configureFor(wireMockHost, wireMockServer.port())
      logger.info(s"Started WireMock server on host: $wireMockHost, port: ${wireMockServer.port()}, rootDirectory: $wireMockRootDirectory")
    }
  }

  def stopWireMock(): Unit =
    if (wireMockServer.isRunning) {
      wireMockServer.stop()
      logger.info(s"Stopped WireMock server on host: $wireMockHost, port: $wireMockPort")
    }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    startWireMock()
  }

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    if (resetWireMockMappings)
      wireMockServer.resetMappings()
    if (resetWireMockRequests)
      wireMockServer.resetRequests()
  }

  override protected def afterAll(): Unit = {
    stopWireMock()
    super.afterAll()
  }
}