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

case class HdmiTx(pixelClk5XDomain: ClockDomain) extends Component {
  val io = new Bundle {
    val timing = in(VideoTiming())
    val pixel = in(Pixel())
    val hdmi = out(HdmiLink())
  }

  val videoEncoders = (0 to 2).map { i =>
    val encoder = TmdsVideoEncoder()
    encoder.io.resetDisparity := !io.timing.videoActive
    encoder.io.input := io.pixel.channel(i).asBits
    encoder
  }

  val ctrlEncoders = Seq.fill(3)(TmdsControlEncoder())
  ctrlEncoders(0).io.input := io.timing.vSync ## io.timing.hSync
  ctrlEncoders(1).io.input := B"00"
  ctrlEncoders(2).io.input := B"00"

  val encoded = videoEncoders.zip(ctrlEncoders).map {
    case (videoEnc, ctrlEnc) => Mux(
      Delay(io.timing.videoActive, Tmds.EncoderLatency),
      videoEnc.io.output,
      ctrlEnc.io.output
    )
  }

  val phy = XilinxSerDesHdmiPhy(pixelClk5XDomain)
  phy.io.enable := True
  phy.io.input.zip(encoded).foreach {
    case (input, data) => input := data
  }
  io.hdmi := phy.io.output

}
