package geotrellis.contrib.vlm.gdal

import geotrellis.raster._
import org.gdal.gdal.gdal
import org.gdal.gdal.Dataset
import org.gdal.gdalconst.gdalconstConstants


// All of the logic in this file was taken from:
// https://github.com/geotrellis/geotrellis-gdal/blob/master/gdal/src/main/scala/geotrellis/gdal/Gdal.scala

private[gdal] class GDALException(code: Int, msg: String)
    extends RuntimeException(s"GDAL ERROR $code: $msg")

private[gdal] object GDALException {
  def lastError(): GDALException =
    new GDALException(gdal.GetLastErrorNo, gdal.GetLastErrorMsg)
}

object GDAL {
  gdal.AllRegister()

  def deriveGTCellType(datatype: GDALDataType): CellType =
    datatype match {
      case TypeUnknown => DoubleConstantNoDataCellType
      case ByteConstantNoDataCellType => ShortConstantNoDataCellType
      case TypeUInt16 => IntConstantNoDataCellType
      case IntConstantNoDataCellType16 => ShortConstantNoDataCellType
      case TypeUInt32 => FloatConstantNoDataCellType
      case IntConstantNoDataCellType32 => IntConstantNoDataCellType
      case FloatConstantNoDataCellType32 => FloatConstantNoDataCellType
      case FloatConstantNoDataCellType64 => DoubleConstantNoDataCellType
      case (TypeCInt16 | TypeCInt32 | TypeCFloat32 | TypeCFloat64) =>
        throw new Exception("Complex datatypes are not supported")
    }

  def open(path: String): Dataset = {
    val ds = gdal.Open(path, gdalconstConstants.GA_ReadOnly)
    if(ds == null) {
      throw GDALException.lastError()
    }
    ds
  }
}
