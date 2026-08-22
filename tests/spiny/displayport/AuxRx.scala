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
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

import spiny._
import spinal.lib.IntRicher

object AuxRxSpec {
  val ClockFreq = FixedFrequency(100 MHz)
  val DataRate = 1 MHz
  val ClocksPerQuarterBit = (ClockFreq.value / DataRate / 4)
    .setScale(0, BigDecimal.RoundingMode.CEILING)
    .toInt
  val ClocksPerHalfBit = ClocksPerQuarterBit * 2
  val ClocksPerBit = ClocksPerQuarterBit * 4
  val PreChargeBits = 16
  val SyncBits = 16
  val SyncEndBits = 4
  val StopBits = 4
  val DataTimeout = ClocksPerBit * 
    (PreChargeBits + SyncBits + SyncEndBits + StopBits + 16)
}

class AuxRxSpec extends AnyFunSuite {
  import AuxRxSpec._

  test("AuxRx should properly decode packets") {
    SpinySimConfig("AuxRx_Transactions")
      .withConfig(SpinalConfig(defaultClockDomainFrequency = ClockFreq))
      .compile(AuxRx(dataRate = DataRate))
      .doSim { dut =>
        val packets = Seq(
          Seq(0xde, 0xad, 0xbe, 0xef),
          Seq(0xa, 0xb, 0xc, 0xd, 0xe, 0xf),
          Seq(0xab),
          Seq(0xff, 0x00, 0xff, 0x00),
          Seq(0x00, 0xff, 0x00, 0xff)
        )

        dut.clockDomain.forkStimulus()

        val checkerThread = fork {
          val checker = AuxRxChecker(dut)
          for (packet <- packets) {
            checker.checkPacket(packet)
          }
        }

        val driver = AuxRxDriver(dut)
        for (packet <- packets) {
          driver.packet(packet)
        }
        dut.clockDomain.waitSampling(ClocksPerBit)
        checkerThread.join()
      }
  }
}

case class AuxRxDriver(dut: AuxRx) {
  dut.io.readEnable #= true

  def bit(value: Boolean) {
    dut.io.read #= value
    dut.clockDomain.waitSampling(AuxRxSpec.ClocksPerHalfBit)
    dut.io.read #= !value
    dut.clockDomain.waitSampling(AuxRxSpec.ClocksPerHalfBit)
  }

  def preCharge() = sync()

  def sync() {
    for (i <- 0 until AuxRxSpec.SyncBits) {
      bit(false)
    }
  }

  def syncEnd() {
    dut.io.read #= true
    dut.clockDomain.waitSampling(AuxRxSpec.ClocksPerBit * 2)
    dut.io.read #= false
    dut.clockDomain.waitSampling(AuxRxSpec.ClocksPerBit * 2)
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

case class AuxRxChecker(dut: AuxRx) {
  def checkByte(expected: Int, last: Boolean, index: Int) {
    dut.clockDomain.waitSamplingWhere(
      AuxRxSpec.DataTimeout)(dut.io.data.valid.toBoolean)
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
