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

package spiny.hdmi

import spinal.core._
import spinal.lib._

sealed trait SyncPolarity extends ImplicitArea[Bool]

object POSITIVE extends SyncPolarity {
  override def implicitValue: Bool = True
}

object NEGATIVE extends SyncPolarity {
  override def implicitValue: Bool = False
}

case class VideoMode(
  pixelClkFreq: HertzNumber,
  hActive: Int,
  hFrontPorch: Int,
  hSync: Int,
  hBackPorch: Int,
  hSyncPolarity: SyncPolarity,
  vActive: Int,
  vFrontPorch: Int,
  vSync: Int,
  vBackPorch: Int,
  vSyncPolarity: SyncPolarity,
  pixelClkTolerance: Double = 0.005
) {
  def hBlank: Int = hFrontPorch + hSync + hBackPorch
  def hTotal: Int = hActive + hBlank
  def hLast: Int = hTotal - 1
  def hSyncStart: Int = hActive + hFrontPorch
  def hSyncEnd: Int = hActive + hFrontPorch + hSync
  def vBlank: Int = vFrontPorch + vSync + vBackPorch
  def vTotal: Int = vActive + vBlank
  def vLast: Int = vTotal - 1
  def vSyncStart: Int = vActive + vFrontPorch
  def vSyncEnd: Int = vActive + vFrontPorch + vSync
}

object VideoMode {
  object Dmt {
    val Hdmi720p60Hz = VideoMode(
      pixelClkFreq = 74.25 MHz,
      hActive = 1280,
      hFrontPorch = 110,
      hSync = 40,
      hBackPorch = 220,
      hSyncPolarity = POSITIVE,
      vActive = 720,
      vFrontPorch = 5,
      vSync = 5,
      vBackPorch = 20,
      vSyncPolarity = POSITIVE
    )
  }

  object CvtRb {
    val Hdmi720p60Hz = VideoMode(
      pixelClkFreq = 64 MHz,
      hActive = 1280,
      hFrontPorch = 48,
      hSync = 32,
      hBackPorch = 80,
      hSyncPolarity = POSITIVE,
      vActive = 720,
      vFrontPorch = 3,
      vSync = 5,
      vBackPorch = 13,
      vSyncPolarity = NEGATIVE
    )
  }
}

case class VideoTiming() extends Bundle {
  val hSync = Bool()
  val vSync = Bool()
  val videoActive = Bool()
}

object VideoTimingGen {
  def apply(highestMode: VideoMode): VideoTimingGen = 
    VideoTimingGen(hMax = highestMode.hTotal, vMax = highestMode.vTotal)

  def static(mode: VideoMode): VideoTimingGen = {
    val timingGen = VideoTimingGen(hMax = mode.hTotal, vMax = mode.vTotal)

    timingGen.io.videoMode.hActive := mode.hActive
    timingGen.io.videoMode.hSyncStart := mode.hSyncStart
    timingGen.io.videoMode.hSyncEnd := mode.hSyncEnd
    timingGen.io.videoMode.hLast := mode.hLast
    timingGen.io.videoMode.hSyncPolarity := mode.hSyncPolarity

    timingGen.io.videoMode.vActive := mode.vActive
    timingGen.io.videoMode.vSyncStart := mode.vSyncStart
    timingGen.io.videoMode.vSyncEnd := mode.vSyncEnd
    timingGen.io.videoMode.vLast := mode.vLast
    timingGen.io.videoMode.vSyncPolarity := mode.vSyncPolarity

    timingGen
  }
}

case class VideoTimingGen(hMax: Int, vMax: Int) extends Component {
  val HCount = HardType(UInt(log2Up(hMax) bits))
  val VCount = HardType(UInt(log2Up(vMax) bits))

  val io = new Bundle() {
    val videoMode = new Bundle {
      val hActive = in(HCount())
      val hSyncStart = in(HCount())
      val hSyncEnd = in(HCount())
      val hLast = in(HCount())
      val hSyncPolarity = in(Bool())
      val vActive = in(VCount())
      val vSyncStart = in(VCount())
      val vSyncEnd = in(VCount())
      val vLast = in(VCount())
      val vSyncPolarity = in(Bool())
    }

    val timing = out(VideoTiming())
    val x = out(HCount())
    val y = out(VCount())
  }

  val hCount = CounterFreeRun(hMax)
  val hDone = hCount === io.videoMode.hLast
  when(hDone) {
    hCount.clear()
  }

  val vCount = Counter(vMax)
  when(hDone) {
    when(vCount === io.videoMode.vLast) {
      vCount.clear()
    } otherwise {
      vCount.increment()
    }
  }

  val inActive = (hCount < io.videoMode.hActive &&
                  vCount < io.videoMode.vActive)
  val inHSync = (hCount >= io.videoMode.hSyncStart &&
                 hCount < io.videoMode.hSyncEnd)
  val inVSync = (vCount >= io.videoMode.vSyncStart &&
                 vCount < io.videoMode.vSyncEnd)

  io.timing.videoActive := RegNext(inActive, init = False)
  io.timing.hSync := RegNext(
    Mux(io.videoMode.hSyncPolarity, inHSync, ~inHSync),
    init = False
  )
  io.timing.vSync := RegNext(
    Mux(io.videoMode.vSyncPolarity, inVSync, ~inVSync),
    init = False
  )
  io.x := RegNext(hCount.value)
  io.y := RegNext(vCount.value)
}
