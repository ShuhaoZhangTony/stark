package dbis.stark.spatial

import org.apache.spark.rdd.RDD
import com.vividsolutions.jts.geom.Geometry
import scala.reflect.ClassTag
import scala.collection.mutable.Map
import com.vividsolutions.jts.io.WKTReader
import com.vividsolutions.jts.geom.Envelope

import dbis.stark.STObject

/**
 * A grid partitioner that simply applies a grid to the data space.
 * 
 * The grid is applied from the lower left point (xmin, ymin) to the (xmax + 1, ymax + 1)
 * so that we only have grid cells over potentially filled space.  
 * 
 * @author hage
 * 
 * @param partitionsPerDimension The number of partitions per dimension. This results in ppD^dimension partitions
 * @param rdd The [[org.apache.spark.RDD]] to partition
 * @param dimensions The dimensionality of the input data 
 */
class SpatialGridPartitioner[G <: STObject : ClassTag, V: ClassTag](
    rdd: RDD[(G,V)],
    partitionsPerDimension: Int,
    withExtent: Boolean,
    _minX: Double,
    _maxX: Double,
    _minY: Double,
    _maxY: Double,
    dimensions: Int) extends SpatialPartitioner(_minX, _maxX, _minY, _maxY) {
  
  require(dimensions == 2, "Only 2 dimensions supported currently")
  
  def this(rdd: RDD[(G,V)],
      partitionsPerDimension: Int,
      withExtent: Boolean,
      minMax: (Double, Double, Double, Double),
      dimensions: Int) = 
    this(rdd, partitionsPerDimension, withExtent, minMax._1, minMax._2, minMax._3, minMax._4, dimensions)  
  
  def this(rdd: RDD[(G,V)],
      partitionsPerDimension: Int,
      withExtent: Boolean = false,
      dimensions: Int = 2) = 
    this(rdd, partitionsPerDimension, withExtent, SpatialPartitioner.getMinMax(rdd), dimensions)
  
  
  protected[this] val xLength = math.abs(maxX - minX) / partitionsPerDimension
  protected[this] val yLength = math.abs(maxY - minY) / partitionsPerDimension
  
//  new Array[Cell](numPartitions) //Map.empty[Int, Cell]
  private var partitions = {
    val arr = Array.tabulate(numPartitions){ i => getCellBounds(i) }
    
    if(withExtent) {
      rdd.foreach{ case (g,_) =>
        val center = g.getCentroid
      
        val id = getCellId(center.getX, center.getY)
        
        val env = g.getEnvelopeInternal
  		  val gExtent = NRectRange(NPoint(env.getMinX, env.getMinY), NPoint(env.getMaxX, env.getMaxY))
        
  		  arr(id) = Cell(arr(id).range, arr(id).extent.extend(gExtent))
      } 
    }
    
    arr
  }
  
  protected[spatial] def getCellBounds(id: Int): Cell = {
    
    require(id >= 0 && id < numPartitions, s"Invalid cell id (0 .. $numPartitions): $id")
    
    val dy = id / partitionsPerDimension
    val dx = id % partitionsPerDimension
    
    val llx = dx * xLength + minX
    val lly = dy * yLength + minY
    
    val urx = llx + xLength
    val ury = lly + yLength
      
    Cell(NRectRange(id, NPoint(llx, lly), NPoint(urx, ury)))
  }
  /**
   * Compute the cell id of a data point
   * 
   * @param point The point to compute the cell id for
   * @returns Returns the number (ID) of the cell the given point lies in
   */
  private def getCellId(p: NPoint): Int = getCellId(p(0), p(1))
    

  
  private def getCellId(_x: Double, _y: Double) = {
    //    require(p(0) >= minX && p(0) <= maxX || p(1) >= minY || p(1) <= maxY, s"$p out of range!")
      
    val x = (math.abs(_x - minX).toInt / xLength).toInt
    val y = (math.abs(_y - minY).toInt / yLength).toInt
    
    val cellId = y * partitionsPerDimension + x
    
    cellId
  }    
  
  
  override def partitionBounds(idx: Int) = getCellBounds(idx)//partitions(idx)
  
  // FIXME: this was null in cluster mode
  override def partitionExtent(idx: Int) = if(partitions(idx) != null) partitions(idx).extent else getCellBounds(idx).extent
  
  override def numPartitions: Int = Math.pow(partitionsPerDimension,dimensions).toInt

  /**
   * Compute the partition for an input key.
   * In fact, this is a Geometry for which we use its centroid for
   * the computation
   * 
   * @param key The key geometry to compute the partition for
   * @return The Index of the partition 
   */
  override def getPartition(key: Any): Int = {
    val g = key.asInstanceOf[G]
    
    val center = g.getCentroid
    
    val id = getCellId(center.getX, center.getY)
    
    require(id >= 0 && id < numPartitions, s"Cell ID out of bounds (0 .. $numPartitions): $id")
    
    id
  }
  
}