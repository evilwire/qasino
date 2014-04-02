package mediamath.metrics

import org.scalatest._
import com.codahale.metrics._
import com.fasterxml.jackson.module.scala._
import com.fasterxml.jackson.databind.ObjectMapper
import collection._
import scala.collection.immutable.ListMap
import mediamath.metrics.QasinoReporter.AuthenticationException

class QasinoReporterSpec extends FlatSpec with Matchers {

  val QASINO_HOST_PROPERTY = "test_qasino_host"
  val QASINO_HOST_OPTION:Option[String]= scala.util.Properties.propOrNone(QASINO_HOST_PROPERTY)

  val QASINO_USER_PROPERTY = "test_qasino_user"
  val QASINO_USER = scala.util.Properties.propOrElse(QASINO_USER_PROPERTY, "eng")

  val QASINO_PASSWORD_PROPERTY = "test_qasino_password"
  val QASINO_PASSWORD = scala.util.Properties.propOrElse(QASINO_PASSWORD_PROPERTY, "1qa2ws3e")

  def createBuilderForTestHost(registry:MetricRegistry): Option[QasinoReporter.Builder] = QASINO_HOST_OPTION map { qasinoHost =>
    QasinoReporter.forRegistry(registry)
      .withHost(qasinoHost)
      .withSecure()
      .withUsername(QASINO_USER)
      .withPassword(QASINO_PASSWORD)
      .withPersist()
  }

	// name sanitization checks
	val sep = QasinoReporter.registryNameSeparator
	"A sanitized registry name" should "replace non-alphanumeric characters with underscores" in {
		QasinoReporter.sanitizeString("testing.abc") should be ("testing" + sep + "abc")
	}
	it should "change all uppercase characters to lowercase" in {
		QasinoReporter.sanitizeString("tEsT") should be ("test")
	}

	"The builder" should "throw an IllegalArgumentException if two metrics are built with names that are the same after sanitation" in {
		val counter1 = new Counter
		val counter1name = "testing_abc"
		val counter2 = new Counter
		val counter2name = "testing.abc"
		val metrics = new MetricRegistry
		metrics.register(MetricRegistry.name(counter1name), counter1)
		metrics.register(MetricRegistry.name(counter2name), counter2)
    val reporter = QasinoReporter.forRegistry(metrics).build()

    an [IllegalArgumentException] should be thrownBy {
      reporter.sanitizeRegistry(metrics)
		}
	}

	it should "throw an IllegalArgumentException if a suffix (column name) begins with a non-alpha character" in {
		val counter1 = new Counter
		val counter1name = "testing_123"
		val metrics = new MetricRegistry
		metrics.register(MetricRegistry.name(counter1name), counter1)
    val reporter = QasinoReporter.forRegistry(metrics).withGroupings(Set("testing")).build()

    an [IllegalArgumentException] should be thrownBy {
      reporter.sanitizeRegistry(metrics)
		}
	}

	{
		// JSON validation
		val mapper = new ObjectMapper()
		mapper.registerModule(DefaultScalaModule)
		val counter1 = new Counter
		val counter1name = "testing_abc"
		val counter2 = new Counter
		counter2.inc(100)
		val counter2name = "testing_def"
		val metrics = new MetricRegistry
		metrics.register(MetricRegistry.name(counter1name), counter1)
		metrics.register(MetricRegistry.name(counter2name), counter2)

		"The JSON generated by two separated metrics" should "be reported separately" in {
			val groupPrefix = "nothing_in_common"
			val reporter = QasinoReporter.forRegistry(metrics).withGroupings(Set(groupPrefix)).build()
			val jsonStrSeq = reporter.getJson(ListMap[String, Metric](counter1name -> counter1, counter2name -> counter2))
			jsonStrSeq.size should be (2)
		}

    "The JSON generated by two metrics with the same group" should "be reported together" in {
			val groupPrefix = "testing"
			val reporter = QasinoReporter.forRegistry(metrics).withGroupings(Set(groupPrefix)).build()
			val jsonStrSeq = reporter.getJson(ListMap[String, Metric](counter1name -> counter1, counter2name -> counter2))
			jsonStrSeq.size should be (1)
		}

		// Check JSON values
		{
			class IntGauge(value: Int) extends Gauge[Int] {
				def getValue = value
			}

			metrics.register("testing_gauge", new IntGauge(100))
			metrics.register("testing_meter", new Meter())
			metrics.register("testing_timer", new Timer())
			val groupPrefix = "testing"
			val reporter = QasinoReporter.forRegistry(metrics).withGroupings(Set(groupPrefix)).build()
			val jsonStrSeq = reporter.getJson(reporter.combineMetricsToMap())
			val dataMap = mapper.readValue(jsonStrSeq(0), classOf[Map[String, Any]])
			val tableDataMap = dataMap.getOrElse("table", {}).asInstanceOf[Map[String, Any]]

			"The op value" should "be add_table_data" in {
				"add_table_data" should equal { dataMap("op") }
			}

			"The tablename value" should "be " + groupPrefix in {
				groupPrefix should equal { tableDataMap("tablename") }
			}

			val correctColumnNames = List(
        "host",
        "gauge_value",
        "abc_count",
        "def_count",
        "meter_count",
        "meter_mean_rate",
        "meter_m1_rate",
        "meter_m5_rate",
        "meter_m15_rate",
        "meter_rate_unit",
        "timer_count",
        "timer_max",
        "timer_mean",
        "timer_min",
        "timer_stddev",
        "timer_p50",
        "timer_p75",
        "timer_p95",
        "timer_p98",
        "timer_p99",
        "timer_p999",
        "timer_mean_rate",
        "timer_m1_rate",
        "timer_m5_rate",
        "timer_m15_rate",
        "timer_rate_unit",
        "timer_duration_unit"
      )

			"The column_names" should "be " + correctColumnNames in {
				tableDataMap("column_names") should be (correctColumnNames)
			}

      val correctColumnTypes = List(
        "text", // host
        "text", // gauge_value
        "integer", // abc_count
        "integer", // def_count
        "integer", // meter_count
        "real", // meter_mean_rate
        "real", // meter_m1_rate
        "real", // meter_m5_rate
        "real", // meter_m15_rate
        "text", // meter_rate_unit
        "integer", // timer_count
        "real", // timer_max
        "real", // timer_mean
        "real", // timer_min
        "real", // timer_stddev
        "real", // timer_p50
        "real", // timer_p75
        "real", // timer_p95
        "real", // timer_p98
        "real", // timer_p99
        "real", // timer_p999
        "real", // timer_mean_rate
        "real", // timer_m1_rate
        "real", // timer_m5_rate
        "real", // timer_m15_rate
        "text", // timer_rate_unit
        "text"  // timer_duration_unit
      )
			"The column_types" should "be " + correctColumnTypes in {
				tableDataMap("column_types") should be (correctColumnTypes)
			}
		}
	}

  {
    val badPassword = "bogusPassWord"

    class StringGauge(value: String) extends Gauge[String] {
      def getValue = value
    }
    class LongGauge(value: Long) extends Gauge[Long] {
      def getValue = value
    }

    val metrics = new MetricRegistry
    val markTime = System.currentTimeMillis
    println(s"Timestamp: $markTime")
    metrics.register("bar.stringGauge", new StringGauge("i'm a little teapot"))
    metrics.register("bar.longGauge", new LongGauge(markTime))
    val counter1 = new Counter
    counter1.inc(100)
    metrics.register("bar.counters.counter1", counter1)
    val counter2 = new Counter
    counter2.inc(200)
    metrics.register("bar.counters.counter2", counter2)
    val counterShouldBeDeleted = new Counter
    counterShouldBeDeleted.inc(100)
    metrics.register("bar.counters.counterShouldBeDeleted", counter1)

    for ( builder <- createBuilderForTestHost(metrics) ) {
      it should "authenticate using proper username and password" in {
        val reporter = builder
          .withGroupings(Set("bar", "bar.counters"))
          .build()

        // Add another counter after registering the metrics
        val counter3 = new Counter
        counter3.inc(300)
        metrics.register("bar.counters.counter3", counter3)
        metrics.remove("bar.counters.counterShouldBeDeleted")

        noException should be thrownBy {
          reporter.reportThrowExceptions()
        }
      }

      it should "fail to authenticate if a bad password is used" in {
        val reporter = builder
          .withGroupings(Set("bar", "bar.counters"))
          .withPassword(badPassword)
          .build()

        an [AuthenticationException] should be thrownBy {
          reporter.reportThrowExceptions()
        }
      }
    }
  }
}
