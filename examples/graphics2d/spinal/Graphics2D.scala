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

package spiny.examples.graphics2d

import spinal.core._
import spinal.lib._

import spiny.ClockGen
import spiny.Utils.readBytesFromFile
import spiny.graphics._
import spiny.hdmi._

object Graphics2D {
  val DisplayMode = VideoMode.CvtRb.CvtRb540p60Hz

  val TileSize = 16
  val TileSetData = readBytesFromFile("data/tileset.bin")
  assert(TileSetData.length % (TileSize * TileSize) == 0)
  val TileSetSize = TileSetData.length / TileSize / TileSize

  val MapWidth = 128
  val MapHeight = 128
  assert(TileSetSize <= 256)

  val MapData = {
    val rng = new scala.util.Random(42)
    (0 until (MapWidth * MapHeight))
      .map(_ => rng.nextInt(TileSetSize))
      .toSeq
  }
  val MapConfig = TileMapConfig(
    tileSize = TileSize,
    tileSetSize = TileSetSize,
    mapWidth = MapWidth,
    mapHeight = MapHeight,
    viewportWidth = DisplayMode.hActive >> 1,
    viewportHeight = DisplayMode.vActive >> 1
  )

  val PaletteData = readBytesFromFile("data/palette.bin")
    .grouped(3)
    .map(rgb => (rgb(0), rgb(1), rgb(2)))
    .toSeq
}

class Graphics2D extends Component {
  import Graphics2D._

  val io = new Bundle {
    val SYS_CLK = in(Bool())
    val CPU_RESET_N = in(Bool())
    val HDMI = out(HdmiLink())
  }
  noIoPrefix()

  val inputClkDomain = ClockDomain(
    clock = io.SYS_CLK,
    reset = !io.CPU_RESET_N,
    config = ClockDomainConfig(resetActiveLevel = HIGH),
    frequency = FixedFrequency(100 MHz)
  )

  val clockGen = inputClkDomain on ClockGen()
  val pixelClk5XDomain = clockGen.request(
    DisplayMode.pixelClkFreq * 5,
    tolerance = DisplayMode.pixelClkTolerance
  )
  val pixelClkDomain = clockGen.requestDivided(pixelClk5XDomain, divisor = 5)
  clockGen.build()

  pixelClkDomain on {
    val timingGen = VideoTimingGen.static(DisplayMode)
    val vSyncPulse = timingGen.io.timing.vSyncActive.rise()

    val tileMapPos = TileMapPosition(MapConfig)
    tileMapPos.x := (timingGen.io.x >> 1).resized
    tileMapPos.y := (timingGen.io.y >> 1).resized
    tileMapPos.scrollX := Counter(
      MapConfig.mapWidthPixels,
      inc = vSyncPulse
    ).intoSInt.resized
    tileMapPos.scrollY := Counter(
      MapConfig.mapHeightPixels,
      inc = vSyncPulse
    ).intoSInt.resized

    val tileMap = TileMap(MapConfig, PalettePixel(8 bits))
    tileMap.io.position.valid := timingGen.io.timing.videoActive
    tileMap.io.position.payload := tileMapPos
    tileMap.tileMem.init(TileSetData.map(c => PalettePixel(c, 8 bits)))
    tileMap.mapMem.init(MapData.map(i => U(i, log2Up(TileSetSize) bits)))

    val tilePixel = Mux(
      tileMap.io.pixels.valid,
      tileMap.io.pixels.payload,
      PalettePixel(0, 8 bits)
    )
    val palette = Mem(PaletteData.map { case (r, g, b) => RgbPixel(r, g, b) })
    val rgbPixel = palette.readSync(tilePixel)

    val timing = Delay(timingGen.io.timing, TileMap.Latency + 1)

    val hdmiTx = HdmiTx(pixelClk5XDomain)
    hdmiTx.io.timing := timing
    hdmiTx.io.pixel := rgbPixel
    io.HDMI := hdmiTx.io.hdmi
  }
}

object TopLevelVerilog extends App {
  val spinalReport = SpinalConfig(
    targetDirectory = "target/spinal",
    inlineRom = true
  ).generateVerilog(new Graphics2D())
}
