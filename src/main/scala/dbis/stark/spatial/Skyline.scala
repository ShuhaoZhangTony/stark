package dbis.stark.spatial

import dbis.stark.STObject

import scala.collection.mutable.ListBuffer

/**
  * Created by hage on 12.04.17.
  */
object Skyline extends  Serializable{

  def centroidDominates(l: STObject, r: STObject): Boolean = {

    val sDistL = l.getGeo.getCentroid.getX
    val tDistL = l.getGeo.getCentroid.getY

    val sDistR = r.getGeo.getCentroid.getX
    val tDistR = r.getGeo.getCentroid.getY


    (tDistL < tDistR && sDistL <= sDistR) || (sDistL < sDistR && tDistL <= tDistR)
  }
}

class Skyline[PayloadType](var skylinePoints: List[(STObject, PayloadType)] = List.empty,
                          dominates: (STObject, STObject) => Boolean) extends Serializable with Cloneable {
  type T = (STObject, PayloadType)

  def insert(tuple: T): Unit = {
    if(!skylinePoints.exists{ case (sp,_) => dominates(sp, tuple._1)}) {
      this.skylinePoints = skylinePoints.filterNot{ case (sp,_) => dominates(tuple._1, sp)} ++ List(tuple)
    }
  }

  def iterator: Iterator[(STObject, PayloadType)] = skylinePoints.iterator

  override def clone(): Skyline[PayloadType] = {
    val l = ListBuffer.empty[(STObject, PayloadType)]
    skylinePoints.foreach(l += _)
    new Skyline(l.toList, dominates)
  }

  def merge(other: Skyline[PayloadType]): Skyline[PayloadType] = {

    val s = this.clone()
    other.iterator.foreach(p => s.insert(p))
    s
  }
}
