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

package spiny.platform.xilinx

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

import spiny._
import spiny.platform.xilinx.blackbox._

/** Gives GtpChannel somewhere to live for the splitting tests */
case class GtpChannelHarness(
  claimTx: Boolean,
  claimRx: Boolean,
  txConfig: Gtpe2TxConfig = Gtpe2TxConfig(135 MHz),
  rxConfig: Gtpe2RxConfig = Gtpe2RxConfig(135 MHz)
) extends Component {
  val io = new Bundle {
    val refClk = in(DiffPair())
  }
  val fabricClk = Bool()
  val buf = IBufDsGte2(io.refClk, fabricClk, True)

  val common = GtpCommon()
  common.io.gtRefClk0 := buf.io.O
  val pll = common.requestPll(Gtpe2PllConfig(refClkDiv = 1, fbDiv = 4, fbDiv45 = 5))
  pll.tieOff()

  val channel = GtpChannel()
  channel.io.clocking.pll0Clk := pll.outClk
  channel.io.clocking.pll0RefClk := pll.outRefClk
  channel.io.clocking.pll1Clk := False
  channel.io.clocking.pll1RefClk := False

  val tx = if (claimTx) Some(channel.requestTx(txConfig)) else None
  val rx = if (claimRx) Some(channel.requestRx(rxConfig)) else None
  tx.foreach(_.disable())
  rx.foreach(_.disable())
}

class GtpChannelSpec extends AnyFunSuite {
  def elaborate(
    claimTx: Boolean,
    claimRx: Boolean,
    txConfig: Gtpe2TxConfig = Gtpe2TxConfig(135 MHz),
    rxConfig: Gtpe2RxConfig = Gtpe2RxConfig(135 MHz)
  ): GtpChannelHarness =
    SpinalConfig(targetDirectory = ElaborationDir.path)
      .generateVerilog(GtpChannelHarness(claimTx, claimRx, txConfig, rxConfig))
      .toplevel

  test("GtpChannel should hand out the transmit half alone") {
    // elaborating at all says the unclaimed half is fully driven, since an
    // input the design forgot would fail with no driver
    val dut = elaborate(claimTx = true, claimRx = false)
    assert(dut.channel.tx.isDefined, "the transmit half should be claimed")
    assert(dut.channel.rx.isEmpty, "the receive half should still be free")
  }

  test("GtpChannel should hand out the receive half alone") {
    val dut = elaborate(claimTx = false, claimRx = true)
    assert(dut.channel.rx.isDefined, "the receive half should be claimed")
    assert(dut.channel.tx.isEmpty, "the transmit half should still be free")
  }

  test("GtpChannel should hand out both halves") {
    val dut = elaborate(claimTx = true, claimRx = true)
    assert(dut.channel.tx.isDefined && dut.channel.rx.isDefined,
      "both halves should be claimed")
  }

  test("GtpChannel should give each half its own config") {
    // distinct widths and dividers, so a swap between halves is visible
    val dut = elaborate(
      claimTx = true, claimRx = true,
      txConfig = Gtpe2TxConfig(135 MHz, dataWidth = 20, outDivider = 2),
      rxConfig = Gtpe2RxConfig(135 MHz, dataWidth = 40, outDivider = 8)
    )
    val primitive = dut.channel.primitive
    assert(primitive.txConfig.dataWidth == 20, "the transmit width")
    assert(primitive.rxConfig.dataWidth == 40, "the receive width")
    assert(primitive.txConfig.outDivider == 2, "the transmit divider")
    assert(primitive.rxConfig.outDivider == 8, "the receive divider")
  }

  test("GtpChannel should give each half its own CLK25_DIV") {
    // each half can select a different PLL, and so a different reference
    val dut = elaborate(
      claimTx = true, claimRx = true,
      txConfig = Gtpe2TxConfig(135 MHz),
      rxConfig = Gtpe2RxConfig(100 MHz)
    )
    assert(dut.channel.primitive.txConfig.clk25Div == 6,
      s"TX_CLK25_DIV was ${dut.channel.primitive.txConfig.clk25Div}")
    assert(dut.channel.primitive.rxConfig.clk25Div == 4,
      s"RX_CLK25_DIV was ${dut.channel.primitive.rxConfig.clk25Div}")
  }

  test("GtpChannel should give an unclaimed half a legal CLK25_DIV") {
    // a powered down half still has to satisfy the tools, so it borrows the
    // claimed half's reference clock
    val dut = elaborate(claimTx = true, claimRx = false,
      txConfig = Gtpe2TxConfig(100 MHz))
    assert(dut.channel.primitive.rxConfig.clk25Div == 4,
      s"RX_CLK25_DIV was ${dut.channel.primitive.rxConfig.clk25Div}")
  }

  test("GtpChannel should let each half pick its own PLL") {
    // the select is a runtime choice, so it stays a port all the way to the
    // primitive, one per half
    val dut = elaborate(claimTx = true, claimRx = true)
    val primitive = dut.channel.primitive
    assert(
      primitive.io.tx.clocking.sysClkSelect.getSingleDriver
        .exists(_ eq dut.tx.get.clocking.sysClkSelect),
      "the transmit select should come from the transmit claim")
    assert(
      primitive.io.rx.clocking.sysClkSelect.getSingleDriver
        .exists(_ eq dut.rx.get.clocking.sysClkSelect),
      "the receive select should come from the receive claim")
  }

  test("GtpChannel should reject a second claim on the same half") {
    assertThrows[AssertionError] {
      SpinalConfig(targetDirectory = ElaborationDir.path)
        .generateVerilog(new Component {
          val channel = GtpChannel()
          channel.requestTx(Gtpe2TxConfig(135 MHz))
          channel.requestTx(Gtpe2TxConfig(135 MHz))
        })
    }
  }

  test("GtpChannel should reject a claim after build") {
    assertThrows[AssertionError] {
      SpinalConfig(targetDirectory = ElaborationDir.path)
        .generateVerilog(new Component {
          val channel = GtpChannel()
          channel.requestTx(Gtpe2TxConfig(135 MHz))
          channel.build()
          channel.requestRx(Gtpe2RxConfig(135 MHz))
        })
    }
  }
}
