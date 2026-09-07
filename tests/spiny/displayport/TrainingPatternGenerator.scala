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

/** What the generator puts in the transmit slots, one cycle at a time */
class TrainingPatternGeneratorSpec extends AnyFunSuite {
  val SlotsPerCycle = 2

  /** The symbols and K flags of one cycle, in slot order */
  def cycle(dut: TrainingPatternGenerator): Seq[(Int, Boolean)] =
    (0 until SlotsPerCycle).map(slot =>
      (dut.io.symbol(slot).toInt, dut.io.isK(slot).toBoolean))

  def withGenerator(name: String)(body: TrainingPatternGenerator => Unit): Unit =
    SpinySimConfig
      .fixedClock(name, 100 MHz)
      .compile(TrainingPatternGenerator(SlotsPerCycle))
      .doSim { dut =>
        dut.io.pattern #= MainLinkPattern.Quiet
        dut.clockDomain.forkStimulus()
        dut.clockDomain.waitSampling()
        body(dut)
      }

  test("TrainingPatternGenerator should transmit nothing when quiet") {
    withGenerator("Pattern_quiet") { dut =>
      dut.clockDomain.waitSampling(4)
      assert(dut.io.quiet.toBoolean, "quiet should be reported")
      assert(cycle(dut).forall(_ == (0, false)), "the slots should be empty")
    }
  }

  test("TrainingPatternGenerator should repeat D10.2 for pattern 1") {
    withGenerator("Pattern_tps1") { dut =>
      dut.io.pattern #= MainLinkPattern.TrainingPattern1
      dut.clockDomain.waitSampling(4)
      assert(!dut.io.quiet.toBoolean, "quiet should be released")
      for (_ <- 0 until 4) {
        assert(
          cycle(dut) == Seq.fill(SlotsPerCycle)(
            (TrainingPatternGenerator.D10_2, false)),
          "every slot of every cycle carries D10.2, and none is a control character"
        )
        dut.clockDomain.waitSampling()
      }
    }
  }

  test("TrainingPatternGenerator should walk the pattern 2 sequence in order") {
    withGenerator("Pattern_tps2") { dut =>
      dut.io.pattern #= MainLinkPattern.TrainingPattern2
      dut.clockDomain.waitSampling(2)

      val expected = TrainingPatternGenerator.TrainingPattern2Symbols
        .map(s => (s.value, s.isK))
      val seen = (0 until expected.length * 2).flatMap { _ =>
        val slots = cycle(dut)
        dut.clockDomain.waitSampling()
        slots
      }
      // two full periods back to back, so a slipped or repeated step shows
      val start = seen.indices.find(i =>
        seen.slice(i, i + expected.length * 2) == (expected ++ expected))
      assert(start.isDefined,
        s"the sequence never appeared, saw ${seen.take(12).map(_._1.toHexString)}")
    }
  }

  test("TrainingPatternGenerator should restart pattern 2 on a comma") {
    withGenerator("Pattern_tps2_restart") { dut =>
      val firstSymbol = TrainingPatternGenerator.TrainingPattern2Symbols.head

      // leave the sequence part way through, then come back to it
      dut.io.pattern #= MainLinkPattern.TrainingPattern2
      dut.clockDomain.waitSampling(3)
      dut.io.pattern #= MainLinkPattern.TrainingPattern1
      dut.clockDomain.waitSampling(3)
      dut.io.pattern #= MainLinkPattern.TrainingPattern2
      dut.clockDomain.waitSampling(2)

      assert(cycle(dut).head == (firstSymbol.value, firstSymbol.isK),
        "re-entering pattern 2 should start at the first comma, not mid sequence")
    }
  }
}
