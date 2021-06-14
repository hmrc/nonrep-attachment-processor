package uk.gov.hmrc.nonrep.attachment.metrics

import com.codahale.metrics.jvm.{GarbageCollectorMetricSet, MemoryUsageGaugeSet, ThreadStatesGaugeSet}
import com.codahale.metrics.{JvmAttributeGaugeSet, SharedMetricRegistries}
import fr.davit.akka.http.metrics.prometheus.{Buckets, PrometheusRegistry, PrometheusSettings, Quantiles}
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.dropwizard.DropwizardExports
import io.prometheus.client.hotspot.DefaultExports
import uk.gov.hmrc.nonrep.attachment.server.Main.config

object Prometheus {

  val prometheus = CollectorRegistry.defaultRegistry

  val settings = PrometheusSettings.default.
    withNamespace(config.appName).
    withIncludePathDimension(true).
    withIncludeMethodDimension(true).
    withIncludeStatusDimension(true).
    withDurationConfig(Buckets(.1, .2, .3, .5, .8, 1, 1.5, 2, 2.5, 3, 5, 8, 13, 21)).
    withReceivedBytesConfig(Quantiles(0.5, 0.75, 0.9, 0.95, 0.99)).
    withSentBytesConfig(PrometheusSettings.DefaultQuantiles).
    withDefineError(_.status.isFailure)

  val registry = {
    DefaultExports.initialize()
    val registry = SharedMetricRegistries.getOrCreate(config.appName)
    registry.register("jvm.attribute", new JvmAttributeGaugeSet())
    registry.register("jvm.gc", new GarbageCollectorMetricSet())
    registry.register("jvm.memory", new MemoryUsageGaugeSet())
    registry.register("jvm.threads", new ThreadStatesGaugeSet())
    prometheus.register(new DropwizardExports(registry))
    PrometheusRegistry(prometheus, settings)
  }
}