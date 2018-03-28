package edu.gmu.stc.vector.rdd

import com.vividsolutions.jts.geom.{Geometry, GeometryFactory, MultiPolygon}
import com.vividsolutions.jts.index.SpatialIndex
import edu.gmu.stc.vector.rdd.index.IndexOperator
import edu.gmu.stc.vector.shapefile.meta.ShapeFileMeta
import edu.gmu.stc.vector.shapefile.reader.GeometryReaderUtil
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FSDataInputStream, Path}
import org.apache.spark.SparkContext
import org.apache.spark.internal.Logging
import org.apache.spark.rdd.RDD
import org.datasyslab.geospark.enums.{GridType, IndexType}
import org.datasyslab.geospark.spatialPartitioning.SpatialPartitioner
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.wololo.geojson.{Feature, FeatureCollection}
import org.wololo.jts2geojson.GeoJSONWriter

import scala.collection.JavaConverters._
import scala.reflect.ClassTag

/**
  * Created by Fei Hu on 1/26/18.
  */
class GeometryRDD extends Logging{
  private var geometryRDD: RDD[Geometry] = _
  private var indexedGeometryRDD: RDD[SpatialIndex] = _
  private var partitioner: SpatialPartitioner = _

  def initialize(shapeFileMetaRDD: ShapeFileMetaRDD, hasAttribute: Boolean = false): Unit = {
    this.geometryRDD = shapeFileMetaRDD.getShapeFileMetaRDD.mapPartitions(itor => {
      val shapeFileMetaList = itor.toList
      if (hasAttribute) {
        GeometryReaderUtil.readGeometriesWithAttributes(shapeFileMetaList.asJava).asScala.toIterator
      } else {
        GeometryReaderUtil.readGeometries(shapeFileMetaList.asJava).asScala.toIterator
      }
    })

    this.partitioner = shapeFileMetaRDD.getPartitioner
  }

  def transforCRS(sourceEpsgCRSCode: String,
                  sourceLongitudeFirst: Boolean = true,
                  targetEpsgCRSCode: String,
                  targetLongitudeFirst: Boolean = true): Unit = {
    val sourceCRS = CRS.decode(sourceEpsgCRSCode, sourceLongitudeFirst)
    val targetCRS = CRS.decode(targetEpsgCRSCode, targetLongitudeFirst)
    val transform = CRS.findMathTransform(sourceCRS, targetCRS)
    this.geometryRDD = this.geometryRDD.map(geometry => JTS.transform(geometry, transform))
  }

  def partition(partition: SpatialPartitioner): Unit = {
    this.partitioner = partition
    this.geometryRDD = this.geometryRDD.flatMap(geometry => partition.placeObject(geometry).asScala)
      .partitionBy(partition).map(_._2)
  }

  def intersect(shapeFileMetaRDD1: ShapeFileMetaRDD, shapeFileMetaRDD2: ShapeFileMetaRDD, partitionNum: Int): Unit = {
    var joinRDD: RDD[(ShapeFileMeta, ShapeFileMeta)] = shapeFileMetaRDD1.spatialJoin(shapeFileMetaRDD2, partitionNum)
      .sortBy({case (shapeFileMeta1, shapeFileMeta2) => shapeFileMeta1.getShp_offset})
      .repartition(partitionNum)

    joinRDD = joinRDD.cache()

    this.geometryRDD = joinRDD.mapPartitions(IndexOperator.spatialIntersect)
  }

  def getGeometryRDD: RDD[Geometry] = this.geometryRDD

  def indexPartition(indexType: IndexType) = {
    val indexBuilder = new IndexOperator(indexType.toString)
    this.indexedGeometryRDD = this.geometryRDD.mapPartitions(indexBuilder.buildIndex)
  }

  def intersect(other: GeometryRDD): GeometryRDD = {
    val geometryRDD = new GeometryRDD
    geometryRDD.geometryRDD = this.indexedGeometryRDD.zipPartitions(other.geometryRDD)(IndexOperator.geoSpatialIntersection)
    geometryRDD.geometryRDD = geometryRDD.geometryRDD.filter(geometry => !geometry.isEmpty)
    geometryRDD
  }

  def spatialJoin(other: GeometryRDD): RDD[(Geometry, Iterable[Geometry])] = {
    val pairedRDD= this.indexedGeometryRDD.zipPartitions(other.geometryRDD)(IndexOperator.geoSpatialJoin)
    //TODO: is there any other efficient way
    pairedRDD.groupByKey()
  }

  def intersectV2(other: GeometryRDD, partitionNum: Int): GeometryRDD = {
    val geometryRDD = new GeometryRDD
    val pairedRDD= this.indexedGeometryRDD.zipPartitions(other.geometryRDD)(IndexOperator.geoSpatialJoin)

    geometryRDD.geometryRDD = pairedRDD
      .map({case (g1, g2) => {
        (g1.hashCode() + "_" + g2.hashCode(), (g1, g2))
      }})
      .reduceByKey((v1, v2) => v1)
        .map(tuple => tuple._2)
      .repartition(partitionNum)
      .map({case(g1, g2) => {
        g1.intersection(g2)
      }})
      .filter(g => !g.isEmpty)
      .map(g => (g.hashCode(), g))
      .reduceByKey({
        case (g1, g2) => g1
      }).map(_._2)

    geometryRDD
  }

  def cache(): Unit = {
    this.geometryRDD = this.geometryRDD.cache()
  }

  def uncache(blocking: Boolean = true): Unit = {
    this.geometryRDD.unpersist(blocking)
  }

  def saveAsGeoJSON(outputLocation: String): Unit = {
    this.geometryRDD.mapPartitions(iterator => {
      val geoJSONWriter = new GeoJSONWriter
      val featureList = iterator.map(geometry => {
        if (geometry.getUserData != null) {
          val userData = Map("UserData" -> geometry.getUserData)
          new Feature(geoJSONWriter.write(geometry), userData.asJava)
        } else {
          new Feature(geoJSONWriter.write(geometry), null)
        }
      }).toList

      val featureCollection = new FeatureCollection(featureList.toArray[Feature])
      List[String](featureCollection.toString).toIterator
    }).saveAsTextFile(outputLocation)
  }

  def getPartitioner: SpatialPartitioner = this.partitioner

  def saveAsShapefile(filepath: String, crs: String): Unit = {
    val geometries = this.geometryRDD.collect().toList.asJava
    GeometryReaderUtil.saveAsShapefile(filepath, geometries, crs)
  }
}

object GeometryRDD {
  def apply(sc: SparkContext, hadoopConfig: Configuration,
            tableName: String,
            gridTypeString: String, indexTypeString: String,
            partitionNum: Int,
            minX: Double,
            minY: Double,
            maxX: Double,
            maxY: Double,
            readAttributes: Boolean,
            isCache: Boolean): GeometryRDD = {

    val gridType = GridType.getGridType(gridTypeString)
    val indexType = IndexType.getIndexType(indexTypeString)

    val shapeFileMetaRDD = new ShapeFileMetaRDD(sc, hadoopConfig)
    shapeFileMetaRDD.initializeShapeFileMetaRDDAndPartitioner(sc, tableName,
      gridType, partitionNum, minX, minY, maxX, maxY)
    val geometryRDD = new GeometryRDD
    geometryRDD.initialize(shapeFileMetaRDD, readAttributes)
    geometryRDD.partition(shapeFileMetaRDD.getPartitioner)
    geometryRDD.indexPartition(indexType)

    if (isCache) {
      geometryRDD.cache()
    }

    geometryRDD
  }

  // use the partitioner from another geometryRDD to partition RDD
  def apply(sc: SparkContext, hadoopConfig: Configuration,
            tableName: String,
            partitionNum: Int,
            spatialPartitioner: SpatialPartitioner,
            minX: Double,
            minY: Double,
            maxX: Double,
            maxY: Double,
            readAttributes: Boolean,
            isCache: Boolean): GeometryRDD = {
    val shapeFileMetaRDD = new ShapeFileMetaRDD(sc, hadoopConfig)
    shapeFileMetaRDD.initializeShapeFileMetaRDDWithoutPartition(sc, tableName,
      partitionNum, minX, minY, maxX, maxY)
    val geometryRDD = new GeometryRDD
    geometryRDD.initialize(shapeFileMetaRDD, readAttributes)
    geometryRDD.partition(spatialPartitioner)
    if (isCache) {
      geometryRDD.cache()
    }

    geometryRDD
  }
}
