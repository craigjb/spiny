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

import spiny._
import spiny.platform.xilinx.blackbox._

/** Gives GtpCommon somewhere to live for the allocation tests */
case class GtpCommonHarness(claims: Seq[Gtpe2PllConfig]) extends Component {
  val io = new Bundle {
    val refClk = in(DiffPair())
    val outClk = out Vec(Bool(), claims.size)
  }
  val fabricClk = Bool()
  val buf = IBufDsGte2(io.refClk, fabricClk, True)

  val common = GtpCommon()
  common.io.gtRefClk0 := buf.io.O
  val plls = claims.map(common.requestPll)

  plls.zipWithIndex.foreach { case (pll, i) =>
    pll.tieOff()
    io.outClk(i) := pll.outClk
  }
}

class GtpCommonSpec extends AnyFunSuite {
  // x20 for a 2.7 GHz VCO, and x12 for 1.62 GHz, from a 135 MHz reference
  val Hbr = Gtpe2PllConfig(refClkDiv = 1, fbDiv = 4, fbDiv45 = 5)
  val Rbr = Gtpe2PllConfig(refClkDiv = 1, fbDiv = 3, fbDiv45 = 4)

  def elaborate(claims: Seq[Gtpe2PllConfig]): GtpCommonHarness =
    SpinalConfig(targetDirectory = ElaborationDir.path)
      .generateVerilog(GtpCommonHarness(claims))
      .toplevel

  test("GtpCommon should hand out a port for each claim") {
    val one = elaborate(Seq(Hbr))
    assert(one.plls.size == 1, "one claim should give one port")
    assert(one.common.plls(1).isEmpty, "the second PLL should still be free")

    val two = elaborate(Seq(Hbr, Rbr))
    assert(two.plls.size == 2, "two claims should give two ports")
    assert(two.plls.map(_.index) == Seq(0, 1),
      "the ports should know which PLL they are")
  }

  test("GtpCommon should give each PLL the config that claimed it") {
    // distinct multipliers, so a swap between slots is visible
    val dut = elaborate(Seq(Hbr, Rbr))
    assert(dut.common.primitive.pll0Config == Hbr, "PLL0 took the first claim")
    assert(dut.common.primitive.pll1Config == Rbr, "PLL1 took the second claim")
  }

  test("GtpCommon should use the dividers exactly as given") {
    // the allocator hands the config through untouched, it solves nothing
    val dut = elaborate(Seq(Rbr))
    assert(dut.common.primitive.pll0Config.refClkDiv == Rbr.refClkDiv)
    assert(dut.common.primitive.pll0Config.fbDiv == Rbr.fbDiv)
    assert(dut.common.primitive.pll0Config.fbDiv45 == Rbr.fbDiv45)
  }

  test("GtpCommon should leave a PLL nobody claimed at its defaults") {
    // an unclaimed PLL is powered down, but it still needs a legal set of
    // dividers for the tools to accept the primitive
    val dut = elaborate(Seq(Hbr))
    assert(dut.common.primitive.pll1Config == Gtpe2PllConfig.default())
  }

  test("GtpCommon should wire each PLL to the port that claimed it") {
    val dut = elaborate(Seq(Hbr, Rbr))
    val primitive = dut.common.primitive
    for ((port, slot) <- Seq((primitive.io.pll0, 0), (primitive.io.pll1, 1))) {
      val claim = dut.common.plls(slot).get
      assert(port.reset.getSingleDriver.exists(_ eq claim.reset),
        s"PLL$slot should take its reset from the port that claimed it")
      assert(claim.lock.getSingleDriver.exists(_ eq port.lock),
        s"PLL$slot should report its lock to the port that claimed it")
    }
  }

  test("GtpCommon should reject a third claim") {
    assertThrows[AssertionError] {
      elaborate(Seq(Hbr, Rbr, Hbr))
    }
  }

  test("GtpCommon should reject a claim after build") {
    assertThrows[AssertionError] {
      SpinalConfig(targetDirectory = ElaborationDir.path)
        .generateVerilog(new Component {
          val common = GtpCommon()
          common.requestPll(Hbr)
          common.build()
          common.requestPll(Rbr)
        })
    }
  }
}
