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

package spiny

import spinal.core._
import spinal.core.sim._
import spinal.core.fiber.Handle
import spinal.lib.bus.amba3.apb.Apb3
import spinal.lib.bus.amba3.apb.sim.Apb3Driver

/** Helper to ensure unique sim directory per test */
object SpinySimConfig {
  def apply(testName: String): SpinalSimConfig = {
    SimConfig
      .withWave
      // sbt clean will clean-up simWorkspace now
      .workspacePath("target/simWorkspace")
      .workspaceName(testName)
      // One verilator cache per suite. SpinalHDL sorts the whole cache by
      // lastModified on every compile, which throws "Comparison method
      // violates its general contract" when a parallel suite writes an entry
      // mid-sort. Suites run their own tests serially, so a cache per suite
      // means nothing else is modifying the directory being sorted.
      .cachePath(s"target/simWorkspace/.cache/${testName.takeWhile(_ != '_')}")
  }

  // same, but for tests repeated at multiple clock frequencies. The
  // frequency is appended to keep the sim directory unique per test.
  def apply(testName: String, clockFreq: HertzNumber): SpinalSimConfig = {
    fixedClock(f"${testName}_$clockFreq%S", clockFreq)
  }

  /** Sets the clock frequency without naming the directory after it
    *
    *  For a component with no frequency derived constants, the frequency is
    *  only a simulation timebase for forkStimulus and cycles(). Naming the
    *  directory after it would imply a sweep that isn't there.
    */
  def fixedClock(testName: String, clockFreq: HertzNumber): SpinalSimConfig = {
    SpinySimConfig(testName)
      .withConfig(SpinalConfig(
        defaultClockDomainFrequency = FixedFrequency(clockFreq)))
  }
}

/** Scratch output dir for tests that only elaborate, no simulation */
object ElaborationDir {
  val path = "target/elaboration"
}

/** ClockDomain helpers for simulation */
object SimClockDomainExt {
  implicit class SimClockDomainExtension(clockDomain: ClockDomain) {
    /** Clock cycles in a span of time (rounding up) */
    def cycles(time: TimeNumber): Int = {
      (time / clockDomain.frequency.getValue.toTime)
        .setScale(0, BigDecimal.RoundingMode.CEILING)
        .toInt
    }

    /** Calls waitSamplingWhere, but fails assert on timeout */
    def waitSamplingWhereOrFail(timeout: Int, waitingFor: String)
                               (condAnd: => Boolean) {
      assert(
        !clockDomain.waitSamplingWhere(timeout)(condAnd),
        s"timed out after $timeout cycles waiting for $waitingFor"
      )
    }
  }

  // dut.clockDomain is a Handle, and Scala applies only one implicit
  // conversion, so this is needed to handle DUT ClockDomains
  implicit class SimClockDomainHandleExtension(clockDomain: Handle[ClockDomain])
    extends SimClockDomainExtension(clockDomain.get)
}

/** APB3 accesses that report PSLVERR, which Apb3Driver discards
 *
 *  regif raises a bus error for things like reading an empty read FIFO, and
 *  the byte it returns alongside looks like ordinary data, so a test that
 *  ignores the error cannot tell the difference.
 */
case class Apb3CheckedDriver(apb: Apb3, clockDomain: ClockDomain) {
  val driver = Apb3Driver(apb, clockDomain)

  def write(address: BigInt, data: BigInt): Unit = driver.write(address, data)

  def read(address: BigInt): BigInt = driver.read(address)

  /** Reads, returning the data and whether the slave signalled an error
    *
    *  PSLVERROR is registered alongside PRDATA, so both are sampled at the
    *  same point, right after the access phase.
    */
  def readChecked(address: BigInt): (BigInt, Boolean) = {
    val data = driver.read(address)
    (data, apb.PSLVERROR.toBoolean)
  }
}

/** Counts single cycle pulses on a signal, from construction onwards */
case class SimPulseMonitor(signal: Bool, clockDomain: ClockDomain, name: String) {
  var count = 0

  fork {
    while (true) {
      clockDomain.waitSampling()
      if (signal.toBoolean) {
        count += 1
      }
    }
  }

  def assertNone(whileDoing: String) {
    assert(count == 0, s"$name pulsed $count time(s) while $whileDoing")
  }

  def assertOne(whileDoing: String) {
    assert(count == 1, s"$name pulsed $count time(s) while $whileDoing, expected 1")
  }
}
