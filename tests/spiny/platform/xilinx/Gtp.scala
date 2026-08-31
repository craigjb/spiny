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

class GtpPllSolverSpec extends AnyFunSuite {
  test("GtpPll should solve a reference clock exactly") {
    // x20, the multiplier the Slabware HDMI RX design uses
    val solved: Option[Gtpe2PllConfig] =
      GtpPll.solve(refClk = 135 MHz, vco = 2.7 GHz)
    assert(solved.isDefined, "2.7 GHz from 135 MHz should be reachable")
    val Some(Gtpe2PllConfig(refClkDiv, fbDiv, fbDiv45, _)) = solved
    assert(refClkDiv == 1, s"refClkDiv should be 1, was $refClkDiv")
    assert(fbDiv * fbDiv45 == 20,
      s"the multiplier should be 20, was ${fbDiv * fbDiv45}")
    // the solver must never settle for close
    val vco = BigDecimal(135e6) / refClkDiv * fbDiv * fbDiv45
    assert(vco == BigDecimal(2.7e9), s"the VCO should be exactly 2.7 GHz, was $vco")
  }

  test("GtpPll should solve the DisplayPort VCOs") {
    // HBR and HBR2 share a VCO, at OUTDIV 2 and 1 respectively
    val hbr = GtpPllConfig.lineRate(2.7 GHz, outDiv = 2)
    val hbr2 = GtpPllConfig.lineRate(5.4 GHz, outDiv = 1)
    assert(hbr == hbr2, s"HBR and HBR2 should want the same VCO, $hbr vs $hbr2")
    assert(GtpPll.solve(135 MHz, hbr.freq).isDefined,
      "2.7 GHz should be reachable from 135 MHz")

    // Every OUTDIV is a power of two, but HBR/RBR is 2.7/1.62 = 5/3, so no
    // single VCO serves both. That is why a rate change is a PLL change.
    val rbr = GtpPllConfig.lineRate(1.62 GHz, outDiv = 2)
    assert(rbr != hbr, "RBR should need a different VCO from HBR")
    assert(GtpPllConfig.lineRate(1.62 GHz, outDiv = 4) != hbr,
      "RBR at OUTDIV 4 should not reach HBR's VCO either")
    assert(GtpPll.solve(135 MHz, rbr.freq).isDefined,
      "1.62 GHz should be reachable from 135 MHz")
  }

  test("GtpPll should reject a VCO outside the range") {
    assert(GtpPll.solve(135 MHz, 1.35 GHz).isEmpty, "1.35 GHz is below the range")
    assert(GtpPll.solve(135 MHz, 4.0 GHz).isEmpty, "4 GHz is above the range")
  }

  test("GtpPll should reject a VCO the dividers cannot reach") {
    // in range, but 135 MHz * n where n is fbDiv * fbDiv45 never lands here
    assert(GtpPll.solve(135 MHz, 2.0 GHz).isEmpty,
      "2 GHz is not a whole multiple of any divider combination")
  }
}

/** Gives GtpCommon somewhere to live for the allocation tests */
case class GtpCommonHarness(
  claims: Seq[(GtpRefClk, GtpPllConfig, Option[Int])]
) extends Component {
  val io = new Bundle {
    val refClk = in(DiffPair())
    val outClk = out Vec(Bool(), claims.size)
  }
  val fabricClk = Bool()
  val buf = IBufDsGte2(io.refClk, fabricClk, True)

  val common = GtpCommon()
  common.io.gtRefClk0 := buf.io.O
  val plls = claims.map { case (refClk, config, index) =>
    common.requestPll(refClk, config, index)
  }
  common.build()

  plls.zipWithIndex.foreach { case (pll, i) =>
    pll.tieOff()
    io.outClk(i) := pll.io.outClk
  }
}

class GtpCommonSpec extends AnyFunSuite {
  val Ref = GtpRefClk(Gtpe2PllRefClk.GtRefClk0, 135 MHz)
  val Hbr = GtpPllConfig.Vco(2.7 GHz)
  val Rbr = GtpPllConfig.Vco(1.62 GHz)

  def elaborate(
    claims: Seq[(GtpRefClk, GtpPllConfig, Option[Int])]
  ): GtpCommonHarness = {
    SpinalConfig(
      targetDirectory = ElaborationDir.path,
      defaultClockDomainFrequency = FixedFrequency(100 MHz)
    ).generateVerilog(GtpCommonHarness(claims)).toplevel
  }

  test("GtpCommon should hand out one PLL") {
    val dut = elaborate(Seq((Ref, Hbr, None)))
    assert(dut.plls.head.index == 0, "the first automatic claim should take PLL0")
  }

  test("GtpCommon should hand out both PLLs") {
    val dut = elaborate(Seq((Ref, Hbr, None), (Ref, Rbr, None)))
    assert(dut.plls.map(_.index) == Seq(0, 1),
      s"claims should land on PLL0 and PLL1, got ${dut.plls.map(_.index)}")
    assert(dut.plls(0).dividers != dut.plls(1).dividers,
      "different VCOs should give different dividers")
  }

  test("GtpCommon should fit an automatic claim around an explicit one") {
    // The explicit claim takes PLL0, so the automatic one made before it has
    // to be pushed to PLL1. With the explicit claim on PLL1 instead, the
    // automatic claim would land on PLL0 whether or not it looked ahead.
    val dut = elaborate(Seq((Ref, Hbr, None), (Ref, Rbr, Some(0))))
    assert(dut.plls.map(_.index) == Seq(1, 0),
      s"the automatic claim should be pushed off PLL0, " +
        s"got ${dut.plls.map(_.index)}")
  }

  test("GtpCommon should solve each PLL against its own reference clock") {
    val slow = GtpRefClk(Gtpe2PllRefClk.GtRefClk0, 135 MHz)
    val fast = GtpRefClk(Gtpe2PllRefClk.GtRefClk1, 200 MHz)
    val dut = elaborate(Seq(
      (slow, GtpPllConfig.Vco(2.7 GHz), None),
      (fast, GtpPllConfig.Vco(2.0 GHz), None)
    ))

    // 135 MHz x20 and 200 MHz x10, so solving both against one reference
    // would give the wrong dividers for at least one of them
    val a = dut.plls(0).dividers
    val b = dut.plls(1).dividers
    assert(a.fbDiv * a.fbDiv45 == 20,
      s"135 MHz to 2.7 GHz needs x20, got ${a.fbDiv * a.fbDiv45}")
    assert(b.fbDiv * b.fbDiv45 == 10,
      s"200 MHz to 2.0 GHz needs x10, got ${b.fbDiv * b.fbDiv45}")

    // and each carries its own reference clock into the sim generic
    assert(a.simRefClkSelect == Gtpe2PllRefClk.GtRefClk0,
      s"PLL0 should select refclk 0, got ${a.simRefClkSelect}")
    assert(b.simRefClkSelect == Gtpe2PllRefClk.GtRefClk1,
      s"PLL1 should select refclk 1, got ${b.simRefClkSelect}")
  }

  test("GtpCommon should reject a third claim") {
    assertThrows[AssertionError] {
      elaborate(Seq((Ref, Hbr, None), (Ref, Rbr, None), (Ref, Hbr, None)))
    }
  }

  test("GtpCommon should reject two claims on the same index") {
    assertThrows[AssertionError] {
      elaborate(Seq((Ref, Hbr, Some(0)), (Ref, Rbr, Some(0))))
    }
  }

  test("GtpCommon should reject an out of range index") {
    assertThrows[AssertionError] {
      elaborate(Seq((Ref, Hbr, Some(2))))
    }
  }

  test("GtpCommon should reject a VCO it cannot reach") {
    assertThrows[SpinalExit] {
      elaborate(Seq((Ref, GtpPllConfig.Vco(2.0 GHz), None)))
    }
  }

  test("GtpCommon should pass explicit dividers through unsolved") {
    val dut = elaborate(Seq((Ref, GtpPllConfig.Dividers(2, 5, 5), None)))
    val d = dut.plls.head.dividers
    assert((d.refClkDiv, d.fbDiv, d.fbDiv45) == (2, 5, 5),
      s"dividers should be used as given, got $d")
  }
}
