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

import spiny._

class SpinyDisplayPortSourceSpec extends AnyFunSuite {
  val HpdFilter = 100 us
  // shortest IRQ_HPD the sink is allowed to send
  val IrqHpdPulse = 500 us

  // interrupt bits, in the order the hpd Area passes the triggers
  val IntRise = 0
  val IntFall = 1

  def withSource(name: String)(
    body: (SpinyDisplayPortSource, Apb3CheckedDriver) => Unit
  ): Unit = {
    SpinySimConfig.fixedClock(name, 100 MHz)
      .compile(new SpinyDisplayPortSource(hpdFilter = HpdFilter))
      .doSim { dut =>
        dut.io.hpd #= false
        dut.clockDomain.forkStimulus()
        val apb = Apb3CheckedDriver(dut.io.apb, dut.clockDomain)
        dut.clockDomain.waitSampling()
        body(dut, apb)
      }
  }

  def connected(dut: SpinyDisplayPortSource, apb: Apb3CheckedDriver): Boolean =
    (apb.read(dut.hpd.status.addr) & 1) != 0

  def edges(dut: SpinyDisplayPortSource, apb: Apb3CheckedDriver): BigInt =
    apb.read(dut.hpd.interruptRaw)

  def clearEdges(dut: SpinyDisplayPortSource, apb: Apb3CheckedDriver): Unit =
    apb.write(dut.hpd.interruptRaw, 0x3)

  /** Drives HPD high and waits for the filter to accept it */
  def plugIn(dut: SpinyDisplayPortSource, apb: Apb3CheckedDriver): Unit = {
    dut.io.hpd #= true
    sleep(HpdFilter * 2)
    assert(connected(dut, apb), "HPD should have been accepted as connected")
    clearEdges(dut, apb)
  }

  test("SpinyDisplayPortSource should reject an HPD filter that hides IRQ_HPD") {
    assertThrows[AssertionError] {
      SpinalConfig(
        targetDirectory = ElaborationDir.path,
        defaultClockDomainFrequency = FixedFrequency(100 MHz)
      ).generateVerilog(new SpinyDisplayPortSource(hpdFilter = 1 ms))
    }
  }

  test("SpinyDisplayPortSource should report a connect once HPD settles") {
    withSource("SpinyDisplayPortSource_Connect") { (dut, apb) =>
      assert(!connected(dut, apb), "should start disconnected")

      dut.io.hpd #= true
      sleep(HpdFilter / 2)
      assert(!connected(dut, apb),
        "HPD should not count as connected before the filter accepts it")

      sleep(HpdFilter)
      assert(connected(dut, apb), "HPD held past the filter should connect")
      assert(((edges(dut, apb) >> IntRise) & 1) == 1,
        "connecting should latch a rising edge")
    }
  }

  test("SpinyDisplayPortSource should ignore an HPD glitch") {
    withSource("SpinyDisplayPortSource_Glitch") { (dut, apb) =>
      plugIn(dut, apb)

      dut.io.hpd #= false
      sleep(HpdFilter / 2)
      dut.io.hpd #= true
      sleep(HpdFilter * 2)

      assert(connected(dut, apb),
        "a glitch shorter than the filter should not disconnect")
      assert(edges(dut, apb) == 0,
        s"a glitch should latch no edges, latched ${edges(dut, apb)}")
    }
  }

  test("SpinyDisplayPortSource should pass through an IRQ_HPD pulse") {
    withSource("SpinyDisplayPortSource_IrqHpd") { (dut, apb) =>
      plugIn(dut, apb)

      dut.io.hpd #= false
      sleep(IrqHpdPulse)
      dut.io.hpd #= true
      sleep(HpdFilter * 2)

      val latched = edges(dut, apb)
      assert(((latched >> IntFall) & 1) == 1,
        "the shortest IRQ_HPD should still latch a falling edge")
      assert(((latched >> IntRise) & 1) == 1,
        "and the rising edge that ends it")
      assert(connected(dut, apb),
        "an IRQ_HPD is not a disconnect, so HPD should read connected again")
    }
  }

  test("SpinyDisplayPortSource should report a disconnect") {
    withSource("SpinyDisplayPortSource_Disconnect") { (dut, apb) =>
      plugIn(dut, apb)

      dut.io.hpd #= false
      sleep(HpdFilter * 2)

      assert(!connected(dut, apb), "HPD held low should disconnect")
      assert(((edges(dut, apb) >> IntFall) & 1) == 1,
        "disconnecting should latch a falling edge")
    }
  }
}
