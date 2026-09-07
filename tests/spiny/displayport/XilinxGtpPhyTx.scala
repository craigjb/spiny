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


package spiny.displayport

import org.scalatest.funsuite.AnyFunSuite

import spinal.core._
import spinal.core.sim._
import spinal.lib._

import spiny._
import spiny.platform.xilinx._
import spiny.platform.xilinx.blackbox._

class XilinxGtpPhyTxSpec extends AnyFunSuite {
  val RefClk = 135 MHz
  val Hbr = 2.7 GHz
  val Rbr = 1.62 GHz

  def phy(lineRate: HertzNumber = Hbr) = XilinxGtpPhyTx(
    pllIndex = 0,
    refClkFreq = RefClk,
    initialLineRate = lineRate,
    refClkSelect = Gtpe2PllRefClk.GtRefClk0,
    swingLevels = XilinxGtpPhyTx.DefaultSwingLevels,
    preEmphasisLevels = XilinxGtpPhyTx.DefaultPreEmphasisLevels
  )

  def ports(dut: XilinxGtpPhyTx): XilinxGtpPhyTxPorts =
    dut.io.control.phy.asInstanceOf[XilinxGtpPhyTxPorts]

  def withPhy(name: String, lineRate: HertzNumber = Hbr)(
    body: (XilinxGtpPhyTx, XilinxGtpPhyTxPorts) => Unit
  ): Unit = {
    SpinySimConfig.fixedClock(name, 100 MHz)
      .addRtl("tests/verilog/BUFG.v")
      .compile(phy(lineRate))
      .doSim { dut =>
        val phyPorts = ports(dut)
        dut.io.control.enable #= false
        dut.io.control.pattern #= MainLinkPattern.Quiet
        dut.io.control.drive(0).swing #= 0
        dut.io.control.drive(0).preEmphasis #= 0
        phyPorts.pllReset #= true
        phyPorts.gtTxReset #= true
        phyPorts.usrClkReady #= false
        dut.io.pll.lock #= false
        dut.io.pll.refClkLost #= false
        dut.io.pll.fbClkLost #= false
        dut.io.tx.resetDone #= false
        dut.io.tx.buffer.status #= 0
        dut.io.tx.fabricClockOutput.outClk #= false
        dut.clockDomain.forkStimulus()
        dut.clockDomain.waitSampling()
        body(dut, phyPorts)
      }
  }

  /** Runs TXOUTCLK, which part of XilinxGtpPhyTx lives on */
  def startOutClk(dut: XilinxGtpPhyTx): Unit = fork {
    while (true) {
      dut.io.tx.fabricClockOutput.outClk #= false
      sleep(3)
      dut.io.tx.fabricClockOutput.outClk #= true
      sleep(3)
    }
  }

  test("XilinxGtpPhyTx should hold the transceiver in reset until enabled") {
    withPhy("PhyTx_disabled") { (dut, phy) =>
      phy.pllReset #= false
      phy.gtTxReset #= false
      phy.usrClkReady #= true
      dut.clockDomain.waitSampling(4)
      assert(dut.io.pll.reset.toBoolean, "the PLL should stay in reset")
      assert(dut.io.tx.reset.toBoolean, "the transmitter should stay in reset")
      assert(!dut.io.tx.clocking.usrReady.toBoolean, "TXUSERRDY should stay low")
    }
  }

  test("XilinxGtpPhyTx should follow the reset sequence firmware drives") {
    withPhy("PhyTx_sequence") { (dut, phy) =>
      dut.io.control.enable #= true
      dut.clockDomain.waitSampling(2)
      assert(dut.io.pll.reset.toBoolean && dut.io.tx.reset.toBoolean,
        "both should still be held until firmware lets go")

      phy.pllReset #= false
      dut.clockDomain.waitSampling(2)
      assert(!dut.io.pll.reset.toBoolean, "the PLL should be released")
      assert(dut.io.tx.reset.toBoolean, "the transmitter should still be held")

      phy.gtTxReset #= false
      phy.usrClkReady #= true
      dut.clockDomain.waitSampling(2)
      assert(!dut.io.tx.reset.toBoolean, "the transmitter should be released")
      assert(dut.io.tx.clocking.usrReady.toBoolean, "TXUSERRDY should follow")
    }
  }

  test("XilinxGtpPhyTx should report what the transceiver is doing") {
    withPhy("PhyTx_status") { (dut, phy) =>
      dut.io.pll.lock #= true
      dut.io.tx.resetDone #= true
      dut.io.pll.refClkLost #= true
      dut.clockDomain.waitSampling(6)
      assert(phy.pllLocked.toBoolean, "PLL lock should be reported")
      assert(phy.resetDone.toBoolean, "TXRESETDONE should be reported")
      assert(phy.refClkLost.toBoolean, "a lost reference should be reported")
      assert(dut.io.control.ready.toBoolean,
        "the link layer should see ready once the reset is done")
    }
  }

  test("XilinxGtpPhyTx should latch TXOUTCLK and clear it on reset") {
    withPhy("PhyTx_outclk") { (dut, phy) =>
      // the toggle the latch watches powers up at an unknown value, so the
      // transmitter is held in reset until that has crossed over
      dut.clockDomain.waitSampling(6)
      phy.gtTxReset #= false
      dut.clockDomain.waitSampling(4)
      assert(!phy.outClkDetected.toBoolean, "a stopped TXOUTCLK is not detected")

      startOutClk(dut)
      dut.clockDomain.waitSampling(20)
      assert(phy.outClkDetected.toBoolean, "a running TXOUTCLK should be detected")

      // reset starts the search again
      phy.gtTxReset #= true
      dut.clockDomain.waitSampling(4)
      assert(!phy.outClkDetected.toBoolean, "a reset should clear the latch")
    }
  }

  test("XilinxGtpPhyTx should transmit D10.2 in every slot for pattern 1") {
    withPhy("PhyTx_pattern") { (dut, phy) =>
      dut.io.control.enable #= true
      dut.io.tx.resetDone #= true
      startOutClk(dut)
      dut.clockDomain.waitSampling(10)
      assert(dut.io.tx.driver.electricalIdle.toBoolean,
        "a quiet link should hold the driver idle")

      dut.io.control.pattern #= MainLinkPattern.TrainingPattern1
      dut.clockDomain.waitSampling(20)
      // a 20 bit datapath = two symbols, both D10.2 here
      val expected = (TrainingPatternGenerator.D10_2 << 8) | TrainingPatternGenerator.D10_2
      assert(dut.io.tx.rawData.toBigInt == expected,
        s"TXDATA should carry D10.2 twice, was ${dut.io.tx.rawData.toBigInt.toString(16)}")
      assert(!dut.io.tx.driver.electricalIdle.toBoolean,
        "the driver should be on while transmitting")
      assert(phy.transmitting.toBoolean, "and should report that it is")
    }
  }

  test("XilinxGtpPhyTx should transmit the pattern 2 sequence in order") {
    withPhy("PhyTx_pattern2") { (dut, phy) =>
      dut.io.control.enable #= true
      dut.io.tx.resetDone #= true
      dut.io.control.pattern #= MainLinkPattern.TrainingPattern2
      dut.clockDomain.waitSampling(4)

      // the fabric side runs on TXOUTCLK, so step it by hand and read one
      // slotful of the sequence per cycle
      val seen = for (_ <- 0 until 40) yield {
        dut.io.tx.fabricClockOutput.outClk #= false
        sleep(2)
        dut.io.tx.fabricClockOutput.outClk #= true
        sleep(2)
        val data = dut.io.tx.rawData.toBigInt
        val k = dut.io.tx.encoder8b10b.charIsK.toInt
        Seq(
          ((data & 0xff).toInt, (k & 1) != 0),
          (((data >> 8) & 0xff).toInt, (k & 2) != 0)
        )
      }
      val stream = seen.flatten

      val expected = TrainingPatternGenerator.TrainingPattern2Symbols.map(s => (s.value, s.isK))
      val start = stream.indices.find(i =>
        stream.slice(i, i + expected.length * 2) ==
          (expected ++ expected).toIndexedSeq
      )
      assert(start.isDefined,
        s"pattern 2 never appeared, saw ${stream.take(24).map(_._1.toHexString)}")
      assert(start.get < expected.length * 2,
        "the sequence should start within a couple of periods of the select")
    }
  }

  test("XilinxGtpPhyTx should send no control characters for pattern 1") {
    withPhy("PhyTx_pattern1_k") { (dut, phy) =>
      dut.io.control.enable #= true
      dut.io.tx.resetDone #= true
      dut.io.control.pattern #= MainLinkPattern.TrainingPattern1
      startOutClk(dut)
      dut.clockDomain.waitSampling(20)
      assert(dut.io.tx.encoder8b10b.charIsK.toInt == 0,
        "training pattern 1 is all data characters")
    }
  }

  test("XilinxGtpPhyTx should drive the swing the link layer asks for") {
    withPhy("PhyTx_swing") { (dut, _) =>
      for ((code, level) <- XilinxGtpPhyTx.DefaultSwingLevels.zipWithIndex) {
        dut.io.control.drive(0).swing #= level
        dut.clockDomain.waitSampling(2)
        assert(dut.io.tx.driver.driverSwing.toInt == code,
          s"swing level $level should drive $code")
      }
    }
  }

  test("XilinxGtpPhyTx should reach the line rate it was asked for") {
    def vco(rate: HertzNumber): BigDecimal = {
      val c = XilinxGtpPhyTx.pllConfig(RefClk, rate)
      RefClk.toBigDecimal / c.refClkDiv * c.fbDiv * c.fbDiv45
    }
    assert(vco(Hbr) == Hbr.toBigDecimal, s"HBR VCO was ${vco(Hbr)}")
    assert(vco(Rbr) == Rbr.toBigDecimal, s"RBR VCO was ${vco(Rbr)}")
  }

  test("XilinxGtpPhyTx should reject a rate its PLL cannot reach") {
    assertThrows[Throwable](XilinxGtpPhyTx.pllConfig(RefClk, 9 GHz))
  }
}
