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

package spiny.platform.xilinx.blackbox

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

import spiny._

class Gtpe2PllConfigSpec extends AnyFunSuite {
  test("Gtpe2PllConfig should solve a reference clock exactly") {
    // x20, the multiplier the Slabware HDMI RX design uses
    val solved: Option[Gtpe2PllConfig] =
      Gtpe2PllConfig.solve(refClk = 135 MHz, vco = 2.7 GHz)
    assert(solved.isDefined, "2.7 GHz from 135 MHz should be reachable")
    val Some(Gtpe2PllConfig(refClkDiv, fbDiv, fbDiv45, _)) = solved
    assert(refClkDiv == 1, s"refClkDiv should be 1, was $refClkDiv")
    assert(fbDiv * fbDiv45 == 20,
      s"the multiplier should be 20, was ${fbDiv * fbDiv45}")
    // the solver must never settle for close
    val vco = BigDecimal(135e6) / refClkDiv * fbDiv * fbDiv45
    assert(vco == BigDecimal(2.7e9), s"the VCO should be exactly 2.7 GHz, was $vco")
  }

  test("Gtpe2PllConfig should solve the DisplayPort VCOs") {
    // HBR and HBR2 share a VCO, at OUTDIV 2 and 1 respectively
    assert(Gtpe2PllConfig.solve(135 MHz, 2.7 GHz).isDefined,
      "2.7 GHz should be reachable from 135 MHz")

    // Every OUTDIV is a power of two, but HBR/RBR is 2.7/1.62 = 5/3, so no
    // single VCO serves both. That is why a rate change is a PLL change.
    val hbr = Gtpe2PllConfig.solve(135 MHz, 2.7 GHz).get
    val rbr = Gtpe2PllConfig.solve(135 MHz, 1.62 GHz)
    assert(rbr.isDefined, "1.62 GHz should be reachable from 135 MHz")
    assert(rbr.get != hbr, "RBR should need different dividers from HBR")
    // RBR's other option, a 3.24 GHz VCO at OUTDIV 4, is out of reach here
    assert(Gtpe2PllConfig.solve(135 MHz, 3.24 GHz).isEmpty,
      "3.24 GHz is not a whole multiple of any divider combination")
  }

  test("Gtpe2PllConfig should reject a VCO outside the range") {
    assert(Gtpe2PllConfig.solve(135 MHz, 1.35 GHz).isEmpty,
      "1.35 GHz is below the range")
    assert(Gtpe2PllConfig.solve(135 MHz, 4.0 GHz).isEmpty,
      "4 GHz is above the range")
  }

  test("Gtpe2PllConfig should reject a VCO the dividers cannot reach") {
    // in range, but 135 MHz * n where n is fbDiv * fbDiv45 never lands here
    assert(Gtpe2PllConfig.solve(135 MHz, 2.0 GHz).isEmpty,
      "2 GHz is not a whole multiple of any divider combination")
  }
}

/** The data port helpers per Table 3-1 */
class Gtpe2DataPortSpec extends AnyFunSuite {
  /** Symbol count and width, which only exist inside an elaboration */
  def shape(dataWidth: Int, bypassed: Boolean): (Int, Int, Int, Int) = {
    var t = (0, 0)
    var r = (0, 0)
    SpinalConfig(targetDirectory = ElaborationDir.path)
      .generateVerilog(new Component {
        // directionless, since these bundles normally live in a blackbox io
        val txIo = Gtpe2TxIo(Gtpe2TxConfig(135 MHz, dataWidth = dataWidth))
          .setAsDirectionLess()
        val rxIo = Gtpe2RxIo(Gtpe2RxConfig(135 MHz, dataWidth = dataWidth))
          .setAsDirectionLess()
        rxIo.rawData := 0
        rxIo.decoder8b10b.charIsK := 0
        rxIo.decoder8b10b.disparityErr := 0
        val txData = txIo.data(bypassed)
        val rxData = rxIo.data(bypassed)
        t = (txData.length, txData(0).getWidth)
        r = (rxData.length, rxData(0).getWidth)
        val used = out(Bits(r._2 bits))
        used := rxData(0)
        setDefinitionName(s"probe_${dataWidth}_$bypassed")
      })
    (t._1, t._2, r._1, r._2)
  }

  test("an 8b10b encoded port carries 8 bit characters") {
    assert(shape(20, false) == (2, 8, 2, 8), s"20 bit was ${shape(20, false)}")
    assert(shape(40, false) == (4, 8, 4, 8), s"40 bit was ${shape(40, false)}")
  }

  test("a 8b10b bypassed port carries whole 10 bit symbols") {
    assert(shape(20, true) == (2, 10, 2, 10), s"20 bit was ${shape(20, true)}")
    assert(shape(40, true) == (4, 10, 4, 10), s"40 bit was ${shape(40, true)}")
  }

  test("a 8b10b bypassed width with no encoding overhead stays 8 bit") {
    // 16 and 32 are legal bypassed, they just carry plain bytes
    assert(shape(16, true) == (2, 8, 2, 8), s"16 bit was ${shape(16, true)}")
    assert(shape(32, true) == (4, 8, 4, 8), s"32 bit was ${shape(32, true)}")
  }

  test("the 8b10b encoder rejects a width not in UG482 Table 3-1") {
    assertThrows[Throwable](shape(16, false))
    assertThrows[Throwable](shape(32, false))
  }
}

class Gtpe2TxConfigSpec extends AnyFunSuite {
  test("Gtpe2TxConfig should keep the TX buffer settings consistent") {
    // TXBUF_EN, TX_XCLK_SEL and TXSYNC_OVRD are one decision, not three
    val buffered = Gtpe2TxConfig(135 MHz, bufferEnabled = true)
    assert(buffered.bufEnable == "TRUE", s"was ${buffered.bufEnable}")
    assert(buffered.xclkSelect == "TXOUT", s"was ${buffered.xclkSelect}")
    assert(!buffered.syncOverride, "a buffered transmitter needs no override")

    val bypassed = Gtpe2TxConfig(135 MHz, bufferEnabled = false)
    assert(bypassed.bufEnable == "FALSE", s"was ${bypassed.bufEnable}")
    assert(bypassed.xclkSelect == "TXUSR", s"was ${bypassed.xclkSelect}")
    assert(bypassed.syncOverride, "a bypassed transmitter needs the override")
  }
}
