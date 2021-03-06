package vectorpipe.osm.cmd

import java.net.URI

import cats.implicits._
import com.monovore.decline._
import org.apache.log4j.{Level, Logger}
import org.apache.spark._
import org.apache.spark.sql._
import org.apache.spark.sql.functions._
import vectorpipe.osm.internal._
import vectorpipe.osm.internal.functions._

import org.locationtech.geomesa.spark.jts._


/*
 * Usage example:
 *
 * sbt "assembly"
 *
 * spark-submit \
 *   --class vectorpipe.osm.cmd.ExtractMultiPolygons \
 *   target/scala-2.11/vectorpipe-assembly-0.2.2.jar \
 *   --orc /tmp/rhode-island.orc \
 *   --cache /tmp/.orc \
 *   --out /tmp/rhode-island-geoms
 */

object ExtractMultiPolygons extends CommandApp(
  name = "extract-multipolygons",
  header = "Extract MultiPolygons from an ORC file",
  main = {

    /* CLI option handling */
    val orcO = Opts.option[String]("orc", help = "Location of the .orc file to process")
    val outGeomsO = Opts.option[String]("out", help = "Location of the ORC file to write containing geometries")

    (orcO, outGeomsO).mapN { (orc, outGeoms) =>
      /* Settings compatible with both local and EMR execution */
      val conf = new SparkConf()
        .setIfMissing("spark.master", "local[*]")
        .setAppName("extract-multipolygons")
        .set("spark.serializer", classOf[org.apache.spark.serializer.KryoSerializer].getName)
        .set("spark.kryo.registrator", classOf[geotrellis.spark.io.kryo.KryoRegistrator].getName)
        .set("spark.sql.orc.impl", "native")

      implicit val ss: SparkSession = SparkSession.builder
        .config(conf)
        .enableHiveSupport
        .getOrCreate
        .withJTS

      import ss.implicits._

      // quiet Spark
      Logger.getRootLogger.setLevel(Level.WARN)

      val df = ss.read.orc(orc)

      // DOWN: get all versions of raw elements

      // get all multipolygon relations
      val relations = ProcessOSM.preprocessRelations(df).where(isMultiPolygon('tags))

      // get all ways referenced by relations
      val wayIds = relations
        .select(explode('members).as('member))
        .where($"member.type" === ProcessOSM.WayType)
        .select($"member.ref".as("id"))
        .distinct

      // get all nodes referenced by referenced ways
      val ways = df.where('type === "way")

      val referencedWays = ways
        .join(wayIds, Seq("id"))

      // create a lookup table for node → ways (using only the ways we'd previously identified)
      val nodesToWays = ProcessOSM.preprocessWays(referencedWays)
        .select(explode('nds).as('id), 'id.as('wayId), 'version, 'timestamp, 'validUntil)

      // extract the referenced nodes from the lookup table
      val nodeIds = nodesToWays
        .select('id)
        .distinct

      val referencedNodes = ProcessOSM.preprocessNodes(df)
        .join(nodeIds, Seq("id"))

      // UP: assemble all versions + minor versions

      // assemble way geometries

      val wayGeoms =
        ProcessOSM.reconstructWayGeometries(referencedWays, referencedNodes, Some(nodesToWays))

      val relationGeoms = ProcessOSM.reconstructRelationGeometries(relations, wayGeoms)

      relationGeoms
        .where('geom.isNotNull)
        .withColumn("wkt", ST_AsText('geom))
        .drop('geom)
        .orderBy('id, 'version, 'updated)
        .repartition(1)
        .write
        .mode(SaveMode.Overwrite)
        .orc(outGeoms)

      ss.stop()

      println("Done.")
    }
  }
)
