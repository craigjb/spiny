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

package spiny.examples.dptest

import spinal.core._
import spinal.lib._
import spinal.lib.blackbox.xilinx.s7._

import spiny.displayport._

class DpTest() extends Component {
  val io = new Bundle() {
    val SYS_CLK = in(Bool())
    val LEDS = out(Bits(8 bits))
    val HPD = in(Bool())
    val AUX_P = inout(Analog(Bool))
    val AUX_N = inout(Analog(Bool))
    val UNUSED_P = in(Bool())
    val UNUSED_N = in(Bool())
  }

  noIoPrefix()

  val sysClkDomain = ClockDomain(
    clock = io.SYS_CLK,
    frequency = FixedFrequency(100 MHz),
    config = ClockDomainConfig(
      resetKind = BOOT
    )
  )

  sysClkDomain on {
    val auxPhy = AuxPhy()
    val auxIoBuf = IOBUFDS()
    auxIoBuf.I := auxPhy.io.aux.write
    auxIoBuf.T := !auxPhy.io.aux.writeEnable
    auxPhy.io.aux.read := auxIoBuf.O
    io.AUX_P := auxIoBuf.IO
    io.AUX_N := auxIoBuf.IOB

    // io.LEDS.setAsReg().init(B"8'0")
    io.LEDS := (B"8'0")

    val auxRequest = Seq(0x90, 0x00, 0x00, 0x00)

    auxPhy.io.txData.valid := False
    auxPhy.io.txData.fragment := B"8'0"
    auxPhy.io.txData.last := False
  }
}

object TopLevelVerilog extends App {
  val spinalReport = SpinalConfig(
    targetDirectory = "target/spinal",
  ).generateVerilog(new DpTest())
}
