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
  rxConfig: Gtpe2RxConfig = Gtpe2RxConfig(135 MHz),
  txPll: Option[Int] = None,
  rxPll: Option[Int] = None
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
  // disable() drives every input, so a later staticSysClk overrides just the select
  tx.foreach { port =>
    port.disable()
    txPll.foreach(pll => port.clocking.staticSysClk(pll, pll))
  }
  rx.foreach { port =>
    port.disable()
    rxPll.foreach(pll => port.clocking.staticSysClk(pll, pll))
  }
}

class GtpChannelSpec extends AnyFunSuite {
  def elaborate(
    claimTx: Boolean,
    claimRx: Boolean,
    txConfig: Gtpe2TxConfig = Gtpe2TxConfig(135 MHz),
    rxConfig: Gtpe2RxConfig = Gtpe2RxConfig(135 MHz),
    txPll: Option[Int] = None,
    rxPll: Option[Int] = None
  ): String = {
    SpinalConfig(
      targetDirectory = ElaborationDir.path,
      defaultClockDomainFrequency = FixedFrequency(100 MHz)
    ).generateVerilog(
      GtpChannelHarness(claimTx, claimRx, txConfig, rxConfig, txPll, rxPll)
    )
    scala.io.Source
      .fromFile(s"${ElaborationDir.path}/GtpChannelHarness.v")
      .mkString
  }

  /** The harness drives the boundary too, so only GtpChannel's own body counts */
  def channelModule(verilog: String): String = {
    val start = verilog.indexOf("module GtpChannel ")
    assert(start >= 0, "the generated Verilog should contain a GtpChannel module")
    verilog.substring(start, verilog.indexOf("endmodule", start))
  }

  /** An unclaimed half reaches the primitive as a constant, a claimed one as a port */
  def poweredDown(verilog: String, port: String): Boolean =
    raw"\.$port\s*\(\s*2'b11".r.findFirstIn(channelModule(verilog)).isDefined

  test("GtpChannel should hand out the transmit half alone") {
    val v = elaborate(claimTx = true, claimRx = false)
    assert(poweredDown(v, "RXPD"), "the unclaimed receive half should be powered down")
    assert(!poweredDown(v, "TXPD"), "the claimed transmit half should not be")
  }

  test("GtpChannel should hand out the receive half alone") {
    val v = elaborate(claimTx = false, claimRx = true)
    assert(poweredDown(v, "TXPD"), "the unclaimed transmit half should be powered down")
    assert(!poweredDown(v, "RXPD"), "the claimed receive half should not be")
  }

  test("GtpChannel should hand out both halves") {
    val v = elaborate(claimTx = true, claimRx = true)
    assert(!poweredDown(v, "TXPD") && !poweredDown(v, "RXPD"),
      "neither claimed half should be powered down")
  }

  test("GtpChannel should give each half its own config") {
    // distinct widths and dividers, so a swap between halves is visible
    val v = elaborate(
      claimTx = true, claimRx = true,
      txConfig = Gtpe2TxConfig(135 MHz, dataWidth = 20, outDivider = 2),
      rxConfig = Gtpe2RxConfig(135 MHz, dataWidth = 40, outDivider = 8)
    )
    def generic(name: String): String =
      raw"\.$name\s*\(\s*(\d+)".r.findFirstMatchIn(v).map(_.group(1)).getOrElse("missing")
    assert(generic("TX_DATA_WIDTH") == "20", s"TX width was ${generic("TX_DATA_WIDTH")}")
    assert(generic("RX_DATA_WIDTH") == "40", s"RX width was ${generic("RX_DATA_WIDTH")}")
    assert(generic("TXOUT_DIV") == "2", s"TXOUT_DIV was ${generic("TXOUT_DIV")}")
    assert(generic("RXOUT_DIV") == "8", s"RXOUT_DIV was ${generic("RXOUT_DIV")}")
  }

  test("GtpChannel should give each half its own CLK25_DIV") {
    // each half can select a different PLL, and so a different reference
    val v = elaborate(
      claimTx = true, claimRx = true,
      txConfig = Gtpe2TxConfig(135 MHz),
      rxConfig = Gtpe2RxConfig(100 MHz)
    )
    def generic(name: String): String =
      raw"\.$name\s*\(\s*(\d+)".r.findFirstMatchIn(v).map(_.group(1)).getOrElse("missing")
    assert(generic("TX_CLK25_DIV") == "6", s"TX_CLK25_DIV was ${generic("TX_CLK25_DIV")}")
    assert(generic("RX_CLK25_DIV") == "4", s"RX_CLK25_DIV was ${generic("RX_CLK25_DIV")}")
  }

  /** The select is assigned whole then per bit, so read it back from the function */
  def selectBits(verilog: String, name: String): String = {
    val body = raw"(?s)zz_channel_${name}\(input dummy\);(.*?)endfunction".r
      .findFirstMatchIn(verilog).map(_.group(1)).getOrElse("")
    def bit(i: Int) =
      raw"\[$i\] = 1'b(\d)".r.findFirstMatchIn(body).map(_.group(1)).getOrElse("?")
    s"${bit(1)}${bit(0)}"
  }

  test("GtpChannel should let each half pick its own PLL") {
    // the mix-and-match case: a transmitter on PLL1, a receiver on PLL0
    val v = elaborate(
      claimTx = true, claimRx = true,
      txPll = Some(1), rxPll = Some(0)
    )
    assert(selectBits(v, "TXSYSCLKSEL") == "11",
      "the transmit half should select PLL1 for both PMA and TXOUTCLK, " +
        s"got ${selectBits(v, "TXSYSCLKSEL")}")
    assert(selectBits(v, "RXSYSCLKSEL") == "00",
      "the receive half should select PLL0 for both PMA and RXOUTCLK, " +
        s"got ${selectBits(v, "RXSYSCLKSEL")}")
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
