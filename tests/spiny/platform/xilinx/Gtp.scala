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

  def elaborate(claims: Seq[Gtpe2PllConfig]): GtpCommonHarness = {
    SpinalConfig(
      targetDirectory = ElaborationDir.path,
      defaultClockDomainFrequency = FixedFrequency(100 MHz)
    ).generateVerilog(GtpCommonHarness(claims)).toplevel
  }

  test("GtpCommon should hand out one PLL") {
    val dut = elaborate(Seq(Hbr))
    assert(dut.plls.size == 1, "one claim should give one port")
  }

  test("GtpCommon should hand out both PLLs") {
    val dut = elaborate(Seq(Hbr, Rbr))
    assert(dut.plls.size == 2, "two claims should give two ports")
  }

  /** The generated Verilog is the only place slot assignment is visible now */
  def generated(): String = {
    scala.io.Source.fromFile(s"${ElaborationDir.path}/GtpCommonHarness.v").mkString
  }

  test("GtpCommon should give each PLL its own config") {
    // distinct multipliers, so a swap between slots is visible
    elaborate(Seq(Hbr, Rbr))
    val v = generated()
    assert(v.contains(".PLL0_FBDIV") && v.contains(".PLL1_FBDIV"),
      "both PLLs should be configured")
    def divider(pll: Int, name: String): String =
      raw"""\.PLL${pll}_${name}\s*\(\s*(\d+)""".r
        .findFirstMatchIn(v).map(_.group(1)).getOrElse("missing")
    assert(divider(0, "FBDIV") == "4" && divider(0, "FBDIV_45") == "5",
      s"PLL0 should be x20, got ${divider(0, "FBDIV")}/${divider(0, "FBDIV_45")}")
    assert(divider(1, "FBDIV") == "3" && divider(1, "FBDIV_45") == "4",
      s"PLL1 should be x12, got ${divider(1, "FBDIV")}/${divider(1, "FBDIV_45")}")
  }

  test("GtpCommon should power down an unclaimed PLL") {
    elaborate(Seq(Hbr))
    val v = generated()
    assert(raw"\.PLL1PD\s*\(\s*1'b1".r.findFirstIn(v).isDefined,
      "the unclaimed PLL1 should be powered down")
    assert(raw"\.PLL0PD\s*\(\s*pll_0_powerDown".r.findFirstIn(v).isDefined,
      "the claimed PLL0 should be driven from its boundary port")
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
