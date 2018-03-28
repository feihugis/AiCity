package edu.gmu.vector.landscape

import com.vividsolutions.jts.geom
import com.vividsolutions.jts.geom.{Geometry, LineString, MultiPolygon, Polygon}
import org.apache.spark.internal.Logging

import scala.math._

/**
  * Created by Fei Hu on 3/20/18.
  * Reference
  * 1. Kim, J.H., Gu, D., Sohn, W., Kil, S.H., Kim, H. and Lee, D.K., 2016. Neighborhood landscape
  *    spatial patterns and land surface temperature: an empirical study on single-family residential
  *    areas in Austin, Texas. International journal of environmental research and public health,
  *    13(9), p.880.
  */
object ComputeLandscape extends Logging{

  def computeCoverPercent(geoCover: Geometry, geoFeatureList: Iterable[Geometry]): Double = {
    val featureSum = geoFeatureList.foldLeft[Double](0.0)
      {case (sum, geoFeature) => {
        geoFeature match {
          case polygon: Polygon => sum + polygon.getArea
          case mulPolygon: MultiPolygon => sum + mulPolygon.getArea
          case lineString: LineString => sum + lineString.getLength
          case _ => {
            logError("Do not support this kind of geometry type: " + geoFeature.getClass)
            0.0
          }
        }
      }}

    if (geoFeatureList.head.isInstanceOf[LineString]) {
      featureSum / geoCover.getLength
    } else {
      featureSum / geoCover.getArea
    }

  }

  def countFeatureNum(geoCover: Geometry, geoFeatureList: Iterable[Geometry]): Int = geoFeatureList.size

  def computeMeanPatchSize(geoCover: Geometry, geoFeatureList: Iterable[Geometry]): Double = {
    val areaSum = geoFeatureList.foldLeft[Double](0.0)((sum, geoFeature) => sum + geoFeature.getArea)
    areaSum / geoFeatureList.size
  }

  def computeMeanShapeIndex(geoCover: Geometry, geoFeatureList: Iterable[Geometry]): Double = {
    val shapeIndexSum = geoFeatureList.foldLeft[Double](0.0){
      case (sum, geoFeature) => {
        val shapeIndex = 0.25 * geoFeature.getLength / sqrt(geoFeature.getArea)
        sum + shapeIndex
      }
    }
    shapeIndexSum / geoFeatureList.size
  }

  def computeMeanNearestNeighborDistance(geoCover: Geometry, geoFeatureList: Iterable[Geometry]): Double = {
    val features = geoFeatureList.toArray
    val max_val = 9999999.999
    val distances: Array[Double] = Array.fill[Double](features.length)(max_val)

    if (features.length == 0) {
      Double.NaN
    }
    else if (features.length == 1) {
      features.head.getCentroid.distance(geoCover)
    }
    else {
      for (i <- features.indices) {
        for (j <- i + 1 until features.length) {
          val dist = features(i).distance(features(j))
          if (distances(i) > dist) distances(i) = dist
          if (distances(j) > dist) distances(j) = dist
        }
      }
      distances.sum/distances.length
    }
  }

  def computePatchCohesionIndex(geoCover: Geometry, geoFeatureList: Iterable[Geometry]): Double = {

    val (areaSum, perimeterSum, perimeterAreaSum) = geoFeatureList.foldLeft[(Double, Double, Double)]((0.0, 0.0, 0.0)) {
      case ((areaSum_, perimeterSum_, perimeterAreaSum_), geoFeature) => {
        val perimeter = geoFeature.getLength
        val area = geoFeature.getArea

        (areaSum_ + area, perimeterSum_ + perimeter, perimeterAreaSum_ + perimeter * sqrt(area))
      }
    }

    //println("perimeterSum: ", perimeterSum, " perimeterAreaSum: " + perimeterAreaSum)

    (1 - perimeterSum / perimeterAreaSum) / (1 - 1/sqrt(geoCover.getArea))
  }

  def computeRoadPercent(geoCover: Geometry, geoFeatureList: Iterable[Geometry]): Double = {
    val featureSum = geoFeatureList.foldLeft[Double](0.0)
      {case (sum, geoFeature) => {
        sum + geoFeature.getLength
      }}

    featureSum / geoCover.getLength
  }
}
