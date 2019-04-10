package vectorpipe.vectortile

import org.apache.spark.sql.functions
import org.apache.spark.sql.functions.{col, isnull, lit}
import org.apache.spark.sql.types._
import org.locationtech.geomesa.spark.jts._
import org.locationtech.jts.{geom => jts}

import vectorpipe.TestEnvironment
import vectorpipe._
import vectorpipe.{internal => vp}

import org.scalatest._

class PipelineSpec extends FunSpec with TestEnvironment with Matchers {
  import ss.implicits._

  ss.withJTS
  val orcFile = getClass.getResource("/isle-of-man-latest.osm.orc").getPath
  val df = ss.read.orc(orcFile)

  describe("Vectortile Pipelines") {
    val nodes = vp.preprocessNodes(df, None)
      .filter(functions.not(isnull('lat)))
      .withColumn("geometry", st_makePoint('lon, 'lat))
      .drop("lat", "lon")
      .withColumn("weight", lit(1))

    it("should generate a single zoom level") {
      val pipeline = TestPipeline("geometry", new java.net.URI("file:///tmp/iom-tiles"), 16)
      VectorPipe(nodes, pipeline, "points", VectorPipe.Options.forZoom(8))
    }

    it("should generate multiple zoom levels") {
      val pipeline = TestPipeline("geometry", new java.net.URI("file:///tmp/iom-tiles-pyramid"), 16)
      VectorPipe(nodes, pipeline, "points", VectorPipe.Options.forZoomRange(6, 8))
    }

  }

}