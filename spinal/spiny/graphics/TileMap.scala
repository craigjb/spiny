/*                           /$$                                             **
**                          |__/                                             **
**        /$$$$$$$  /$$$$$$  /$$ /$$$$$$$  /$$   /$$                         **
**       /$$_____/ /$$__  $$| $$| $$__  $$| $$  | $$                         **
**      |  $$$$$$ | $$  \ $$| $$| $$  \ $$| $$  | $$   (c) Craig J Bishop    **
**       \____  $$| $$  | $$| $$| $$  | $$| $$  | $$   All rights reserved   **
**       /$$$$$$$/| $$$$$$$/| $$| $$  | $$|  $$$$$$$                         **
**      |_______/ | $$____/ |__/|__/  |__/ \____  $$   MIT License           **
**                | $$                     /$$  | $$                         **
**                | $$                    |  $$$$$$/                         **
**                |__/                     \______/                          **
**                                                                           **
** Permission is hereby granted, free of charge, to any person obtaining a   ** 
** copy of this software and associated documentation files (the             **
** "Software"), to deal in the Software without restriction, including       **
** without limitation the rights to use, copy, modify, merge, publish,       **
** distribute, sublicense, and/or sell copies of the Software, and to permit **
** persons to whom the Software is furnished to do so, subject to the        **
** following conditions:                                                     **
**                                                                           **
** The above copyright notice and this permission notice shall be included   **
** in all copies or substantial portions of the Software.                    **
**                                                                           **
** THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS   **
** OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF                **
** MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN **
** NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,  **
** DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR     **
** OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE **
** USE OR OTHER DEALINGS IN THE SOFTWARE.                                    */

package spiny.graphics

import spinal.core._
import spinal.lib._

case class TileMapConfig(
  tileSize: Int,
  tileSetSize: Int,
  mapWidth: Int,
  mapHeight: Int,
  viewportWidth: Int,
  viewportHeight: Int
) {
  assert(isPow2(tileSize), "tileSize must be power of 2")
  assert(isPow2(tileSetSize), "tileSetSize must be power of 2")
  assert(isPow2(mapWidth), "tileMapWidth must be power of 2")
  assert(isPow2(mapHeight), "tileMapHeight must be power of 2")

  def mapWidthPixels = mapWidth * tileSize
  def mapHeightPixels = mapHeight * tileSize

  def tileIndexBits = log2Up(tileSetSize)
  def tileBits = log2Up(tileSize)

  val Tile = HardType(UInt(tileIndexBits bits))
  def tile(i: Int) = U(i, tileIndexBits bits)

}

case class TileMapPosition(config: TileMapConfig) extends Bundle {
  val x = UInt(log2Up(config.viewportWidth) bits)
  val y = UInt(log2Up(config.viewportHeight) bits)
  val scrollX = SInt((log2Up(config.mapWidthPixels) + 1) bits)
  val scrollY = SInt((log2Up(config.mapHeightPixels) + 1) bits)
}

object TileMap {
  val Latency = 2
}

case class TileMap[T <: Data](
  config: TileMapConfig,
  pixelFormat: HardType[T]
) extends Component {
  val io = new Bundle {
    val position = in(Flow(TileMapPosition(config)))
    val pixels = out(Flow(pixelFormat()))
  }

  val tileMemSize = config.tileSetSize * config.tileSize * config.tileSize
  val tileMem = Mem(pixelFormat(), tileMemSize)

  val mapMemSize = config.mapWidth * config.mapHeight
  val mapMem = Mem(config.Tile, mapMemSize)

  // ==================================
  //  STAGE 0 
  // ==================================
  val mapPixelX = io.position.x.intoSInt +^ io.position.scrollX
  val mapPixelY = io.position.y.intoSInt +^ io.position.scrollY
  val mapX = mapPixelX >> config.tileBits
  val tilePixelX = mapPixelX(config.tileBits - 1 downto 0).asUInt
  val mapY = mapPixelY >> config.tileBits
  val tilePixelY = mapPixelY(config.tileBits - 1 downto 0).asUInt
  val mapAddr = (mapY(log2Up(config.mapHeight) - 1 downto 0) ##
                 mapX(log2Up(config.mapWidth) - 1 downto 0)).asUInt
  val inBounds = mapX >= 0 && mapY >= 0

  // ==================================
  //  STAGE 1 
  // ==================================
  val tileValid = RegNext(inBounds && io.position.valid)
  val tilePixelAddr = RegNext(tilePixelY ## tilePixelX)
  val tileIndex = mapMem.readSync(mapAddr)
  val tileAddr = (tileIndex ## tilePixelAddr).asUInt

  // ==================================
  //  STAGE 2
  // ==================================
  io.pixels.valid := RegNext(tileValid)
  io.pixels.payload := tileMem.readSync(tileAddr)
}
