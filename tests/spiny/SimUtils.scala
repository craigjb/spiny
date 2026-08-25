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

/** Helper to ensure unique sim directory per test */
object SpinySimConfig {
  def apply(testName: String): SpinalSimConfig = {
    SimConfig
      .withWave
      // sbt clean will clean-up simWorkspace now
      .workspacePath("target/simWorkspace")
      .workspaceName(testName)
  }

  // same, but for tests repeated at multiple clock frequencies.
  def apply(testName: String, clockFreq: HertzNumber): SpinalSimConfig = {
    SpinySimConfig(f"${testName}_$clockFreq%S")
      .withConfig(SpinalConfig(
        defaultClockDomainFrequency = FixedFrequency(clockFreq)))
  }
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
