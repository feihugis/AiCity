/**
 * FILE: ChoroplethmapTest.java
 * PATH: org.datasyslab.geosparkviz.ChoroplethmapTest.java
 * Copyright (c) 2015-2017 GeoSpark Development Team
 * All rights reserved.
 */
package org.datasyslab.geosparkviz;

import java.awt.Color;
import java.io.InputStream;
import java.io.Serializable;
import java.util.Properties;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.storage.StorageLevel;
import org.datasyslab.geosparkviz.core.ImageGenerator;
import org.datasyslab.geosparkviz.core.RasterOverlayOperator;
import org.datasyslab.geosparkviz.core.VectorOverlayOperator;
import org.datasyslab.geosparkviz.extension.visualizationEffect.ChoroplethMap;
import org.datasyslab.geosparkviz.extension.visualizationEffect.ScatterPlot;
import org.datasyslab.geosparkviz.utils.ImageType;
import org.datasyslab.geospark.enums.FileDataSplitter;
import org.datasyslab.geospark.enums.GridType;
import org.datasyslab.geospark.enums.IndexType;
import org.datasyslab.geospark.spatialOperator.JoinQuery;
import org.datasyslab.geospark.spatialRDD.PointRDD;
import org.datasyslab.geospark.spatialRDD.PolygonRDD;
import org.datasyslab.geospark.spatialRDD.RectangleRDD;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Polygon;

// TODO: Auto-generated Javadoc
/**
 * The Class ChoroplethmapTest.
 */
public class ChoroplethmapTest extends GeoSparkVizTestBase{
    
	/**
	 * Sets the up before class.
	 *
	 * @throws Exception the exception
	 */
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		initialize(ChoroplethmapTest.class.getSimpleName());
	}

	/**
	 * Tear down.
	 *
	 * @throws Exception the exception
	 */
	@AfterClass
	public static void tearDown() throws Exception {
		sparkContext.stop();
	}

	/**
	 * Test rectangle RDD visualization.
	 *
	 * @throws Exception the exception
	 */
	@Test
	public void testRectangleRDDVisualization() throws Exception {
		PointRDD spatialRDD = new PointRDD(sparkContext, PointInputLocation, PointOffset, PointSplitter, false, PointNumPartitions, StorageLevel.MEMORY_ONLY());
		RectangleRDD queryRDD = new RectangleRDD(sparkContext, RectangleInputLocation, RectangleSplitter, false, RectangleNumPartitions, StorageLevel.MEMORY_ONLY());
		spatialRDD.spatialPartitioning(GridType.RTREE);
		queryRDD.spatialPartitioning(spatialRDD.grids);
  		spatialRDD.buildIndex(IndexType.RTREE,true);
  		JavaPairRDD<Polygon,Long> joinResult = JoinQuery.SpatialJoinQueryCountByKey(spatialRDD,queryRDD,true,true);
  		
  		ChoroplethMap visualizationOperator = new ChoroplethMap(1000,600,USMainLandBoundary,false);
		visualizationOperator.CustomizeColor(255, 255, 255, 255, Color.RED, true);
		visualizationOperator.Visualize(sparkContext, joinResult);
		
		ScatterPlot frontImage = new ScatterPlot(1000,600,USMainLandBoundary,false);
		frontImage.CustomizeColor(0, 0, 0, 255, Color.GREEN, true);
		frontImage.Visualize(sparkContext, queryRDD);

		
		RasterOverlayOperator overlayOperator = new RasterOverlayOperator(visualizationOperator.rasterImage);
		overlayOperator.JoinImage(frontImage.rasterImage);
		
		ImageGenerator imageGenerator = new ImageGenerator();
		imageGenerator.SaveRasterImageAsLocalFile(overlayOperator.backRasterImage,"./target/choroplethmap/RectangleRDD-combined", ImageType.PNG);
	}
	
	/**
	 * Test polygon RDD visualization.
	 *
	 * @throws Exception the exception
	 */
	@Test
	public void testPolygonRDDVisualization() throws Exception {
		//UserSuppliedPolygonMapper userSuppliedPolygonMapper = new UserSuppliedPolygonMapper();
		PointRDD spatialRDD = new PointRDD(sparkContext, PointInputLocation, PointOffset, PointSplitter, false, PointNumPartitions, StorageLevel.MEMORY_ONLY());
		PolygonRDD queryRDD = new PolygonRDD(sparkContext, PolygonInputLocation,  PolygonSplitter, false, PolygonNumPartitions, StorageLevel.MEMORY_ONLY());
		spatialRDD.spatialPartitioning(GridType.RTREE);
		queryRDD.spatialPartitioning(spatialRDD.grids);
  		spatialRDD.buildIndex(IndexType.RTREE,true);
		JavaPairRDD<Polygon,Long> joinResult = JoinQuery.SpatialJoinQueryCountByKey(spatialRDD,queryRDD,true,true);

		ChoroplethMap visualizationOperator = new ChoroplethMap(1000,600,USMainLandBoundary,false);
		visualizationOperator.CustomizeColor(255, 255, 255, 255, Color.RED, true);
		visualizationOperator.Visualize(sparkContext, joinResult);
		
		ScatterPlot frontImage = new ScatterPlot(1000,600,USMainLandBoundary,false);
		frontImage.CustomizeColor(0, 0, 0, 255, Color.GREEN, true);
		frontImage.Visualize(sparkContext, queryRDD);

		RasterOverlayOperator rasterOverlayOperator = new RasterOverlayOperator(visualizationOperator.rasterImage);
		rasterOverlayOperator.JoinImage(frontImage.rasterImage);
		
		ImageGenerator imageGenerator = new ImageGenerator();
		imageGenerator.SaveRasterImageAsLocalFile(rasterOverlayOperator.backRasterImage, "./target/choroplethmap/PolygonRDD-combined", ImageType.GIF);
		
		visualizationOperator = new ChoroplethMap(1000,600,USMainLandBoundary,false,true);
		visualizationOperator.CustomizeColor(255, 255, 255, 255, Color.RED, true);
		visualizationOperator.Visualize(sparkContext, joinResult);
		
		imageGenerator.SaveVectorImageAsLocalFile(visualizationOperator.vectorImage, "./target/choroplethmap/PolygonRDD-combined-1", ImageType.SVG);
		
		frontImage = new ScatterPlot(1000,600,USMainLandBoundary,false,true);
		frontImage.CustomizeColor(0, 0, 0, 255, Color.GREEN, true);
		frontImage.Visualize(sparkContext, queryRDD);
		
		imageGenerator.SaveVectorImageAsLocalFile(frontImage.vectorImage, "./target/choroplethmap/PolygonRDD-combined-2", ImageType.SVG);

		VectorOverlayOperator vectorOverlayOperator = new VectorOverlayOperator(visualizationOperator.vectorImage);
		vectorOverlayOperator.JoinImage(frontImage.vectorImage);
		
		imageGenerator = new ImageGenerator();
		imageGenerator.SaveVectorImageAsLocalFile(vectorOverlayOperator.backVectorImage, "./target/choroplethmap/PolygonRDD-combined", ImageType.SVG);
	}

}
