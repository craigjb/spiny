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

import java.io.File
import java.io.OutputStream
import org.rogach.scallop._
import scala.sys.process._

import spinal.core._
import spinal.core.sim._
import spinal.lib._

import spiny._
import spiny.Utils._
import spiny.graphics._
import spiny.hdmi._

object GraphicsDemo {
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

case class GraphicsDemo() extends Component {
  import GraphicsDemo._

  val io = new Bundle {
    val timing = out(VideoTiming())
    val pixel = out(RgbPixel())
  }

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
  io.pixel := palette.readSync(tilePixel)
  io.timing := Delay(
    timingGen.io.timing,
    cycleCount = TileMap.Latency + 1,
    init = VideoTiming.inactive()
  )
}

class Graphics2D extends Component {
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

  val clockGen = inputClkDomain on ClockGen(bandwidth = ClockGenBandwidth.High)
  val pixelClk5XDomain = clockGen.request(
    GraphicsDemo.DisplayMode.pixelClkFreq * 5,
    tolerance = GraphicsDemo.DisplayMode.pixelClkTolerance
  )
  val pixelClkDomain = clockGen.requestDivided(pixelClk5XDomain, divisor = 5)
  clockGen.build()

  pixelClkDomain on {
    val demo = GraphicsDemo()
    val hdmiTx = HdmiTx(pixelClk5XDomain)
    hdmiTx.io.timing := demo.io.timing
    hdmiTx.io.pixel := demo.io.pixel
    io.HDMI := hdmiTx.io.hdmi
  }
}

object TopLevelVerilog extends App {
  val spinalReport = SpinalConfig(
    targetDirectory = "target/spinal",
    inlineRom = true
  ).generateVerilog(new Graphics2D())
}

class VideoDump(videoMode: VideoMode, outputPath: String) {
  var stdInStream: OutputStream = null
  val processIo = new ProcessIO(
    in => stdInStream = in, 
    out => scala.io.Source.fromInputStream(out).getLines().foreach(println),
    err => scala.io.Source.fromInputStream(err).getLines().foreach(println)
  )

  val ffmpegCmd = Seq(
    "ffmpeg",
    "-y",                  // Overwrite output file
    "-f", "rawvideo",      // Raw binary data in
    "-vcodec", "rawvideo",
    "-s", s"${videoMode.hActive}x${videoMode.vActive}",
    "-pix_fmt", "rgb24",   // 3 bytes per pixel (R, G, B)
    "-r", videoMode.frameRate.toDouble.round.toString,
    "-i", "-",             // Read from stdin
    "-c:v", "libx264",
    "-preset", "medium",
    "-pix_fmt", "yuv420p",  // Standard pixel format for mp4 players
    outputPath
  )
  println(s"ffmpeg command: ${ffmpegCmd.mkString(" ")}")

  val process = Process(ffmpegCmd).run(processIo)
  // wait for the ProcessIO thread to capture the stdin stream
  while(stdInStream == null) Thread.sleep(10)

  def writeFrame(buffer: Array[Byte]) {
    stdInStream.write(buffer)
  }

  def close() {
    println("Flushing ffmpeg stdin")
    stdInStream.flush()
    println("Closing ffmpeg stdin")
    stdInStream.close()
    println("Exiting ffmpeg")
    process.exitValue()
    println("Done with ffmpeg")
  }
}

object TopLevelSim extends App {
  import org.rogach.scallop._

  object Conf extends ScallopConf(args) {
    val frames = opt[Int](default = Some(60))
    val outputVideoPath = opt[File](
      default = Some(new File("simWorkspace/GraphicsDemo/sim.mp4"))
    )
    val withWave = toggle("wave", default = Some(false))
    verify()
  }
  println(f"[Graphics2D] TopLevelSim.Conf: ${Conf.summary}")

  val videoDump = new VideoDump(
    videoMode = GraphicsDemo.DisplayMode,
    outputPath = Conf.outputVideoPath().getAbsolutePath()
  )

  val simConfig = if(Conf.withWave()) {
    SimConfig.withWave
  } else {
    SimConfig
  }

  simConfig
    .withConfig(SpinalConfig(
      defaultClockDomainFrequency = FixedFrequency(
        GraphicsDemo.DisplayMode.pixelClkFreq)))
    .allOptimisation
    .compile(GraphicsDemo())
    .doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus()

      val frameWidth = GraphicsDemo.DisplayMode.hActive
      val frameHeight = GraphicsDemo.DisplayMode.vActive
      val frameBuffer = new Array[Byte](frameWidth * frameHeight * 3)
      var framesCaptured = 0
      var pixelIndex = 0
      var flushed = false
      while(framesCaptured < Conf.frames()) {
        cd.waitSampling()
        if(dut.io.timing.videoActive.toBoolean) {
          flushed = false
          frameBuffer(pixelIndex) = dut.io.pixel.r.toInt.toByte
          frameBuffer(pixelIndex + 1) = dut.io.pixel.g.toInt.toByte
          frameBuffer(pixelIndex + 2) = dut.io.pixel.b.toInt.toByte
          pixelIndex += 3
        } else if(!flushed && dut.io.timing.vSyncActive.toBoolean) {
          videoDump.writeFrame(frameBuffer)
          flushed = true
          framesCaptured += 1
          pixelIndex = 0
          println(s"Captured frame ${framesCaptured} of ${Conf.frames()}")
          cd.waitActiveEdgeWhere(!dut.io.timing.vSyncActive.toBoolean)
        }
      }
      println("Simulation complete")
      videoDump.close()
    }
}
