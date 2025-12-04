package uk.gov.hmrc.nonrep.attachment.app.metrics

import com.codahale.metrics.jvm.{GarbageCollectorMetricSet, MemoryUsageGaugeSet, ThreadStatesGaugeSet}
import com.codahale.metrics.{JvmAttributeGaugeSet, SharedMetricRegistries}
import fr.davit.pekko.http.metrics.prometheus.{Buckets, PrometheusRegistry, PrometheusSettings, Quantiles}
import io.prometheus.client.{CollectorRegistry, Histogram}
import io.prometheus.client.dropwizard.DropwizardExports
import io.prometheus.client.hotspot.DefaultExports
import uk.gov.hmrc.nonrep.attachment.server.Main.config

object Prometheus {

  private val prometheus: CollectorRegistry = CollectorRegistry.defaultRegistry

  val settings: PrometheusSettings =
    PrometheusSettings.default
      .withNamespace(config.appName)
      .withIncludePathDimension(true)
      .withIncludeMethodDimension(true)
      .withIncludeStatusDimension(true)
      .withDurationConfig(Buckets(.1, .2, .3, .5, .8, 1, 1.5, 2, 2.5, 3, 5, 8, 13, 21))
      .withReceivedBytesConfig(Quantiles(0.5, 0.75, 0.9, 0.95, 0.99))
      .withSentBytesConfig(PrometheusSettings.DefaultQuantiles)
      .withDefineError(_.status.isFailure)

  val registry: PrometheusRegistry = {
    DefaultExports.initialize()
    val registry = SharedMetricRegistries.getOrCreate(config.appName)
    registry.register("jvm.attribute", new JvmAttributeGaugeSet())
    registry.register("jvm.gc", new GarbageCollectorMetricSet())
    registry.register("jvm.memory", new MemoryUsageGaugeSet())
    registry.register("jvm.threads", new ThreadStatesGaugeSet())
    prometheus.register(new DropwizardExports(registry))
    PrometheusRegistry(prometheus, settings)
  }

  def attachmentSizeBucket(sizeBytes: Long): String = {
    val b = sizeBytes
    if b <= 10_240 then "lt_10KB"
    else if b <= 51_200 then "10KB_50KB"
    else if b <= 102_400 then "50KB_100KB"
    else if b <= 256_000 then "100KB_250KB"
    else if b <= 512_000 then "250KB_500KB"
    else if b <= 1_024_000 then "500KB_1MB"
    else if b <= 2_048_000 then "1MB_2MB"
    else if b <= 5_120_000 then "2MB_5MB"
    else if b <= 10_240_000 then "5MB_10MB"
    else if b <= 15_360_000 then "10MB_15MB"
    else if b <= 20_480_000 then "15MB_20MB"
    else "gt_20MB"
  }

  private val defaultHistogramBuckets =
    List[Double](.1, .2, .3, .4, .5, .6, .7, .8, .9, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 4.5, 5.0, 5.5, 6.0, 6.5, 7.0, 7.5, 8.0, 8.5, 9.0,
      9.5, 10.0, 20.0)

  val attachmentProcessingDuration: Histogram =
    Histogram
      .build()
      .name("attachment_processor_processing_time")
      .help("Time spent processing attachment in attachment-processor service")
      .labelNames("service")
      .buckets(defaultHistogramBuckets*)
      .register()
}
