package geotrellis.contrib.vlm.gdal

import geotrellis.contrib.vlm.RasterSource

import geotrellis.vector._
import geotrellis.raster._
import geotrellis.raster.reproject._
import geotrellis.raster.resample.{ResampleMethod, NearestNeighbor}
import geotrellis.proj4._
import geotrellis.raster.io.geotiff.MultibandGeoTiff
import geotrellis.raster.io.geotiff.reader.GeoTiffReader

import org.gdal.gdal._
import org.gdal.osr.SpatialReference


case class WarpGDALRasterSource(
  uri: String,
  crs: CRS,
  resampleMethod: ResampleMethod = NearestNeighbor,
  errorThreshold: Double = 0.125
) extends RasterSource {
  private lazy val spatialReference: SpatialReference = {
    val spatialReference = new SpatialReference()
    spatialReference.ImportFromProj4(crs.toProj4String)
    spatialReference
  }

  @transient private lazy val vrt: Dataset = {
    val baseDataset: Dataset = GDAL.open(uri)

    val dataset =
      gdal.AutoCreateWarpedVRT(
        baseDataset,
        null,
        spatialReference.ExportToWkt(),
        GDAL.deriveGDALResampleMethod(resampleMethod),
        errorThreshold
      )
    baseDataset.delete()
    dataset
  }

  private lazy val colsLong: Long = vrt.getRasterXSize
  private lazy val rowsLong: Long = vrt.getRasterYSize

  require(
    colsLong * rowsLong <= Int.MaxValue,
    s"Cannot read this raster, cols * rows is greater than the maximum array index: ${colsLong * rowsLong}"
  )

  def cols: Int = colsLong.toInt
  def rows: Int = rowsLong.toInt

  private lazy val geoTransform: Array[Double] = vrt.GetGeoTransform

  private lazy val xmin: Double = geoTransform(0)
  private lazy val ymin: Double = geoTransform(3) + geoTransform(5) * rows
  private lazy val xmax: Double = geoTransform(0) + geoTransform(1) * cols
  private lazy val ymax: Double = geoTransform(3)

  lazy val extent = Extent(xmin, ymin, xmax, ymax)
  override lazy val rasterExtent = RasterExtent(extent, cols, rows)

  lazy val bandCount: Int = vrt.getRasterCount

  private lazy val datatype: GDALDataType = {
    val band = vrt.GetRasterBand(1)
    band.getDataType()
  }

  private lazy val reader: GDALReader = GDALReader(vrt)

  lazy val cellType: CellType = GDAL.deriveGTCellType(datatype)

  def read(windows: Traversable[RasterExtent]): Iterator[Raster[MultibandTile]] = {
    val bounds: Map[GridBounds, RasterExtent] =
      windows.map { case targetRasterExtent =>
        val affine =
          Array[Double](
            targetRasterExtent.extent.xmin,
            targetRasterExtent.cellwidth,
            0,
            targetRasterExtent.extent.ymax,
            0,
            -targetRasterExtent.cellheight
          )

        val (colMax, rowMin) = (Array.ofDim[Double](1), Array.ofDim[Double](1))

        gdal.ApplyGeoTransform(
          affine,
          targetRasterExtent.gridBounds.colMax,
          targetRasterExtent.gridBounds.rowMin,
          colMax,
          rowMin
        )

        val extent = Extent(affine(0), rowMin.head, colMax.head, affine(3))
        val gridBounds = rasterExtent.gridBoundsFor(extent)

        (gridBounds, targetRasterExtent)
      }.toMap

    bounds.map { case (gb, re) =>
      val initialTile = reader.read(gb)

      val tile =
        if (initialTile.cols != re.cols || initialTile.rows != re.rows) {
          val updatedTiles = initialTile.bands.map { band =>
            val protoTile = band.prototype(re.cols, re.rows)

            protoTile.update(re.cols - gb.width, re.rows - gb.height, band)
            protoTile
          }

          MultibandTile(updatedTiles)
        } else
          initialTile

        Raster(tile, re.extent)
    }.toIterator
  }

  def withCRS(
    targetCRS: CRS,
    resampleMethod: ResampleMethod = NearestNeighbor
  ): WarpGDALRasterSource =
    WarpGDALRasterSource(uri, targetCRS, resampleMethod)
}