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

sealed trait SyncPolarity extends AreaObject {
  def sync(asserted: Bool): Bool
}

object POSITIVE extends SyncPolarity {
  override def sync(asserted: Bool): Bool = asserted
}

object NEGATIVE extends SyncPolarity {
  override def sync(asserted: Bool): Bool = ~asserted
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
  def vBlank: Int = vFrontPorch + vSync + vBackPorch
  def vTotal: Int = vActive + vBlank
  def hBlank: Int = hFrontPorch + hSync + hBackPorch
  def hTotal: Int = hActive + hBlank
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

case class StaticVideoTimingGen(mode: VideoMode) extends Component {
  val io = new Bundle() {
    val hSync = out(Bool())
    val vSync = out(Bool())
    val videoActive = out(Bool())
    val x = out(UInt(log2Up(mode.hActive) bits))
    val y = out(UInt(log2Up(mode.vActive) bits))
  }

  val hCount = CounterFreeRun(mode.hTotal)
  val vCount = Counter(mode.vTotal)
  when(hCount.willOverflow) {
    vCount.increment()
  }

  val active  = hCount < mode.hActive && vCount < mode.vActive

  val hSyncStart = mode.hActive + mode.hFrontPorch
  val hSyncEnd = hSyncStart + mode.hSync
  val hSync = hCount >= hSyncStart && hCount < hSyncEnd

  val vSyncStart = mode.vActive + mode.vFrontPorch
  val vSyncEnd = vSyncStart + mode.vSync
  val vSync = vCount >= vSyncStart && vCount < vSyncEnd

  io.videoActive := RegNext(active, init = False)
  io.hSync := RegNext(
    mode.hSyncPolarity.sync(hSync),
    init = mode.hSyncPolarity.sync(False)
  )
  io.vSync := RegNext(
    mode.vSyncPolarity.sync(vSync),
    init = mode.vSyncPolarity.sync(False)
  )
  io.x := RegNext(hCount.resized)
  io.y := RegNext(vCount.resized)
}
