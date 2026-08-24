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

import scala.collection.mutable
import scala.util.Random
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

import spiny._
import spinal.lib.IntRicher
import spiny.displayport.AuxTxSpec.BitPeriod

object AuxRxSpec {
  val DataRate = 1 MHz
  val BitPeriod = DataRate.toTime
  val HalfBitPeriod = DataRate.toTime / 2
  val SyncBits = 16
  val Packets = Seq(
    Seq(0xde, 0xad, 0xbe, 0xef),
    Seq(0xa, 0xb, 0xc, 0xd, 0xe, 0xf),
    Seq(0xab),
    Seq(0xff, 0x00, 0xff, 0x00),
    Seq(0x00, 0xff, 0x00, 0xff)
  )
}

class AuxRxSpec extends AnyFunSuite {
  import AuxRxSpec._

  for (clockFreq <- Seq(20 MHz, 20.33 MHz, 21.3 MHz, 25.3 MHz, 66.6 MHz, 100 MHz)) {
    val dataTimeout = ((400 us) / clockFreq.toTime)
      .setScale(0, BigDecimal.RoundingMode.CEILING)
      .toInt

    test(f"AuxRx should properly decode packets @ $clockFreq%s") {
      SpinySimConfig(f"AuxRx_Packets_$clockFreq%S")
        .withConfig(SpinalConfig(
          defaultClockDomainFrequency = FixedFrequency(clockFreq)))
        .compile(AuxRx(dataRate = DataRate))
        .doSim { dut =>
          dut.clockDomain.forkStimulus()

          val checkerThread = fork {
            val checker = AuxRxChecker(dut, dataTimeout)
            for (packet <- Packets) {
              checker.checkPacket(packet)
            }
          }

          val driver = AuxRxDriver(dut)
          for (packet <- Packets) {
            driver.packet(packet)
          }
          sleep(1 us)
          checkerThread.join()
        }
    }
  }

  for (clockFreq <- Seq(61 MHz, 66.6 MHz, 100 MHz)) {
    val dataTimeout = ((400 us) / clockFreq.toTime)
      .setScale(0, BigDecimal.RoundingMode.CEILING)
      .toInt

    // max glitch size that can be handled depends on filter
    val rawTaps = (50.ns / clockFreq.toTime).toInt
    val taps = if (rawTaps % 2 == 0) rawTaps - 1 else rawTaps
    val maxEdgesAllowed = taps / 2
    val glitchMax = ((clockFreq.toTime * maxEdgesAllowed) - (0.1 ns))

    test(f"AuxRx should handle glitches <$glitchMax%.2s @ $clockFreq%s") {
      SpinySimConfig(f"AuxRx_Glitches_$clockFreq%S")
        .withConfig(SpinalConfig(
          defaultClockDomainFrequency = FixedFrequency(clockFreq)))
        .compile(AuxRx(dataRate = DataRate))
        .doSim { dut =>
          dut.clockDomain.forkStimulus()

          val checkerThread = fork {
            val checker = AuxRxChecker(dut, dataTimeout)
            for (packet <- Packets) {
              checker.checkPacket(packet)
            }
          }

          val driver = AuxRxDriver(dut, glitchMax)
          for (packet <- Packets) {
            driver.packet(packet)
          }
          sleep(1 us)
          checkerThread.join()

        }
    }
  }
}

case class AuxRxDriver(
  dut: AuxRx,
  glitchMax: TimeNumber = 0 ns,
  rngSeed: Int = 12345
) {
  val rng = new Random(rngSeed)
  dut.io.readEnable #= true

  def bit(value: Boolean, period: TimeNumber = AuxRxSpec.BitPeriod) {
    val glitchStart = (period * rng.nextDouble())
      .min(period - glitchMax)
    val glitchLen = glitchMax * rng.nextDouble()
    val glitchEnd = glitchStart + glitchLen

    val edgeTimes = Seq(
      0 ns,
      glitchStart,
      glitchEnd,
      period / 2,
    ).distinct.sortBy(_.toDouble)

    var time = 0.ns
    for (edgeTime <- edgeTimes) {
      sleep(edgeTime - time)
      time = edgeTime
      val idealValue = if (time < period / 2) value else !value
      val isGlitched = time >= glitchStart && time < glitchEnd
      dut.io.read #= (if (isGlitched) !idealValue else idealValue)
    }
    sleep(period - time)
  }

  def preCharge() = sync()

  def sync() {
    for (i <- 0 until AuxRxSpec.SyncBits) {
      bit(false)
    }
  }

  def syncEnd() {
    bit(true, AuxRxSpec.BitPeriod * 4)
  }

  def stop() = syncEnd()

  def data(byte: Int) {
    for (i <- 7 to 0 by -1) {
      val bitValue = ((byte >> i) & 1) == 1
      bit(bitValue)
    }
  }

  def data(bytes: Seq[Int]) {
    for (byte <- bytes) {
      data(byte)
    }
  }

  def packet(bytes: Seq[Int]) {
    preCharge()
    sync()
    syncEnd()
    data(bytes)
    stop()
  }
}

case class AuxRxChecker(dut: AuxRx, dataTimeout: Int) {
  def checkByte(expected: Int, last: Boolean, index: Int) {
    dut.clockDomain.waitSamplingWhere(dataTimeout)(dut.io.data.valid.toBoolean)
    val value = dut.io.data.fragment.toInt
    assert(
      value == expected,
      s"data[$index] should be 0x${expected.hexString()}, but saw 0x${value.hexString()}"
    )
    if (last) {
      assert(dut.io.data.last.toBoolean, s"data[$index] should be last")
    } else {
      assert(!dut.io.data.last.toBoolean, s"data[$index] should not be last")
    }
  }

  def checkPacket(bytes: Seq[Int]) {
    val bytesWithIndex = bytes.zipWithIndex
    for ((byte, i) <- bytesWithIndex.init) {
      checkByte(byte, false, i)
    }
    checkByte(bytes.last, true, bytesWithIndex.length - 1)
  }
}
