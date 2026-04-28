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

package spiny.examples.hdmitest

import spinal.core._
import spinal.lib._

import spiny.ClockGen
import spiny.hdmi._
class HdmiTest extends Component {
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

  val videoMode = VideoMode.Dmt.Hdmi720p60Hz

  val clockGen = inputClkDomain on ClockGen()
  val pixelClk5XDomain = clockGen.request(
    videoMode.pixelClkFreq * 5,
    tolerance = videoMode.pixelClkTolerance
  )
  val pixelClkDomain = clockGen.requestDivided(pixelClk5XDomain, divisor = 5)
  clockGen.build()

  pixelClkDomain on {
    val timingGen = VideoTimingGen.static(videoMode)

    val videoEncoders = Seq.fill(3) {
       val encoder = TmdsVideoEncoder()
       encoder.io.resetDisparity := ~timingGen.io.videoActive
       encoder
    }
    videoEncoders(0).io.input := timingGen.io.x.asBits.resized
    videoEncoders(1).io.input := (
      timingGen.io.y.asBits(3 downto 0) ## timingGen.io.x.asBits(3 downto 0)
    )
    videoEncoders(2).io.input := timingGen.io.y.asBits.resized

    val ctrlEncoders = Seq.fill(3)(TmdsControlEncoder())
    ctrlEncoders(0).io.input := timingGen.io.vSync ## timingGen.io.hSync
    ctrlEncoders(1).io.input := B"00"
    ctrlEncoders(2).io.input := B"00"

    val encoded = videoEncoders.zip(ctrlEncoders).map {
      case (videoEnc, ctrlEnc) => Mux(
        Delay(timingGen.io.videoActive, Tmds.EncoderLatency),
        videoEnc.io.output,
        ctrlEnc.io.output
      )
    }

    val phy = XilinxSerDesHdmiPhy(pixelClk5XDomain)
    phy.io.enable := True
    phy.io.input.zip(encoded).foreach {
      case (input, data) => input := data
    }
    io.HDMI := phy.io.output
  }
}

object TopLevelVerilog extends App {
  val spinalReport = SpinalConfig(
    targetDirectory = "target/spinal",
    inlineRom = true
  ).generateVerilog(new HdmiTest())
}
