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
import spinal.lib.blackbox.xilinx.s7.OBUFDS

import spiny.DiffPair
import spiny.platform.xilinx._

case class HdmiLink() extends Bundle {
  val clk = DiffPair()
  val data = Vec(DiffPair(), 3)
}

/** HDMI TX PHY using Xilinx OSERDESE2
 *
 *  serialClk must be 5X pixel clock (component's clock domain)
 */
case class XilinxSerDesHdmiPhy(serialClk: ClockDomain) extends Component {
  val io = new Bundle {
    val enable = in(Bool())
    val input = in(Vec(TmdsChar, 3))
    val output = out(HdmiLink())
  }

  val clkChannel = XilinxSerDesHdmiChannel(serialClk)
  clkChannel.io.enable := io.enable
  clkChannel.io.input := B"1111100000"
  io.output.clk := clkChannel.io.output

  val dataChannels = (0 to 2).map(i => {
    val channel = XilinxSerDesHdmiChannel(serialClk)
    channel.io.enable := io.enable
    channel.io.input := io.input(i)
    io.output.data(i) := channel.io.output
    channel
  })
}

/** Converts one channel of TMDS characters into serial output
 *
 *  serialClk must be 5X pixel clock (component's clock domain)
 */
case class XilinxSerDesHdmiChannel(serialClk: ClockDomain) extends Component {
  val io = new Bundle {
    val enable = in(Bool())
    val input = in(TmdsChar)
    val output = out(DiffPair())
  }

  val pixelClkFreq = ClockDomain.current.frequency.getValue
  val serialClkFreq = serialClk.frequency.getValue
  assert(serialClkFreq == pixelClkFreq * 5.0, "serialClk must be 5X pixel clk")

  // cascaded OSERDESE2 blocks for 10-bit parallel to serial
  val masterSerial = OSERDESE2(
    dataWidth = 10,
    dataRateOQ = DataRate.DDR,
    serDesMode = SerDesMode.MASTER,
    triStateWidth = 1
  )
  masterSerial.CLK := serialClk.readClockWire
  masterSerial.CLKDIV := ClockDomain.current.readClockWire
  masterSerial.RST := ClockDomain.isResetActive
  masterSerial.OCE := io.enable
  masterSerial.TCE := False
  for (i <- 0 to 7) {
    masterSerial.D(i) := io.input(i)
  }
  for (i <- 0 to 3) {
    masterSerial.T(i) := False
  }

  val slaveSerial = OSERDESE2(
    dataWidth = 10,
    dataRateOQ = DataRate.DDR,
    serDesMode = SerDesMode.SLAVE,
    triStateWidth = 1
  )
  slaveSerial.CLK := serialClk.readClockWire
  slaveSerial.CLKDIV := ClockDomain.current.readClockWire
  slaveSerial.RST := ClockDomain.isResetActive
  slaveSerial.OCE := io.enable
  slaveSerial.TCE := False
  slaveSerial.D1 := False
  slaveSerial.D2 := False
  slaveSerial.D3 := io.input(8)
  slaveSerial.D4 := io.input(9)
  for (i <- 4 to 7) {
    slaveSerial.D(i) := False
  }
  for (i <- 0 to 3) {
    slaveSerial.T(i) := False
  }

  masterSerial.SHIFTIN1 := slaveSerial.SHIFTOUT1
  masterSerial.SHIFTIN2 := slaveSerial.SHIFTOUT2

  val outputBuffer = OBUFDS()
  outputBuffer.I := masterSerial.OQ
  io.output.p := outputBuffer.O
  io.output.n := outputBuffer.OB
}
