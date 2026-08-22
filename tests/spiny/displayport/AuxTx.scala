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

object AuxTxSpec {
  val ClockFreq = FixedFrequency(100 MHz)
  val DataRate = 1 MHz
  val BitPeriod = DataRate.toTime
  val HalfBitPeriod = DataRate.toTime / 2
  val QuarterBitPeriod = DataRate.toTime / 4
  val Timeout = (400.us / ClockFreq.getValue.toTime)
        .setScale(0, BigDecimal.RoundingMode.CEILING)
        .toInt
  val PacketGap = 10 us
}

class AuxTxSpec extends AnyFunSuite {
  import AuxTxSpec._

  test("AuxTx should properly encode and transmit packets") {
    SpinySimConfig("AuxTx_Packets")
      .withConfig(SpinalConfig(defaultClockDomainFrequency = ClockFreq))
      .compile(AuxTx(dataRate = DataRate))
      .doSim { dut =>
        val packets = Seq(
          Seq(0xde, 0xad, 0xbe, 0xef),
          Seq(0xa, 0xb, 0xc, 0xd, 0xe, 0xf),
          Seq(0xab)
        )

        dut.clockDomain.forkStimulus()

        val checkerThread = fork {
          dut.clockDomain.waitSampling()
          val checker = AuxTxChecker(dut)
          for ((packet, i) <- packets.zipWithIndex) {
            println(s"Checking packet $i")
            checker.checkPacket(packet)
          }
        }

        val driver = AuxTxDriver(dut)
        dut.clockDomain.waitSampling(10)
        for (packet <- packets) {
          driver.write(packet)
          dut.clockDomain.waitSamplingWhere(Timeout)(!dut.io.writeEnable.toBoolean)
          sleep(PacketGap)
        }
        checkerThread.join()
      }
  }

  test("AuxTx should abort on no data") {
    SpinySimConfig("AuxTx_NoDataAbort")
      .withConfig(SpinalConfig(defaultClockDomainFrequency = ClockFreq))
      .compile(AuxTx(dataRate = DataRate))
      .doSim { dut =>
        dut.clockDomain.forkStimulus()

        val checkerThread = fork {
          dut.clockDomain.waitSampling()
          val checker = AuxTxChecker(dut)
          checker.waitForPacketStart()
          checker.checkPreCharge()
          checker.checkSync()
          checker.checkSyncEnd()
          checker.checkStop()
        }

        val driver = AuxTxDriver(dut)
        dut.clockDomain.waitSampling(10)

        // kick off packet, but then deassert valid
        dut.io.data.fragment #= 0xde
        dut.io.data.last #= false
        dut.io.data.valid #= true
        dut.clockDomain.waitSampling()
        dut.io.data.valid #= false

        dut.clockDomain.waitSamplingWhere(Timeout)(!dut.io.writeEnable.toBoolean)
        sleep(PacketGap)
        checkerThread.join()
      }
  }

  test("AuxTx should abort on data underrun") {
    SpinySimConfig("AuxTx_UnderrunAbort")
      .withConfig(SpinalConfig(defaultClockDomainFrequency = ClockFreq))
      .compile(AuxTx(dataRate = DataRate))
      .doSim { dut =>
        dut.clockDomain.forkStimulus()

        val checkerThread = fork {
          dut.clockDomain.waitSampling()
          val checker = AuxTxChecker(dut)
          checker.waitForPacketStart()
          checker.checkPreCharge()
          checker.checkSync()
          checker.checkSyncEnd()
          checker.checkByte(0xde)
          checker.checkStop()
        }

        val driver = AuxTxDriver(dut)
        dut.clockDomain.waitSampling(10)
        driver.write(0xde, false)
        dut.clockDomain.waitSamplingWhere(Timeout)(!dut.io.writeEnable.toBoolean)
        sleep(PacketGap)
        checkerThread.join()
      }
  }
}

case class AuxTxDriver(dut: AuxTx) {
  dut.io.data.valid #= false

  def write(byte: Int, last: Boolean) {
    dut.io.data.fragment #= byte
    dut.io.data.last #= last
    dut.io.data.valid #= true
    dut.clockDomain.waitSamplingWhere(AuxTxSpec.Timeout)(dut.io.data.ready.toBoolean)
    dut.io.data.valid #= false
  }

  def write(bytes: Seq[Int]) {
    for(byte <- bytes.init) {
      write(byte, false)
    }
    write(bytes.last, true)
  }
}

case class AuxTxChecker(dut: AuxTx) {
  def checkPreCharge() {
    for (i <- 0 until 16) {
      assert(
        dut.io.write.toBoolean == false,
        s"precharge bit $i first half should be low"
      )
      sleep(AuxTxSpec.HalfBitPeriod)
      assert(
        dut.io.write.toBoolean == true,
        s"precharge bit $i second half should be high"
      )
      sleep(AuxTxSpec.HalfBitPeriod)
    }
  }

  def checkSync() {
    for (i <- 0 until 16) {
      assert(
        dut.io.write.toBoolean == false,
        s"sync bit $i first half should be low"
      )
      sleep(AuxTxSpec.HalfBitPeriod)
      assert(
        dut.io.write.toBoolean == true,
        s"sync bit $i second half should be high"
      )
      sleep(AuxTxSpec.HalfBitPeriod)
    }
  }

  def checkSyncEnd() {
    for (i <- 0 until 2) {
      assert(
        dut.io.write.toBoolean == true,
        s"sync end bit $i should stay high for first and second half"
      )
      sleep(AuxTxSpec.HalfBitPeriod)
      assert(
        dut.io.write.toBoolean == true,
        s"sync end bit $i should stay high for first and second half"
      )
      sleep(AuxTxSpec.HalfBitPeriod)
    }
    for (i <- 0 until 2) {
      assert(
        dut.io.write.toBoolean == false,
        s"sync end bit $i should stay low for first and second half"
      )
      sleep(AuxTxSpec.HalfBitPeriod)
      assert(
        dut.io.write.toBoolean == false,
        s"sync end bit $i should stay low for first and second half"
      )
      sleep(AuxTxSpec.HalfBitPeriod)
    }
  }

  def checkStop() {
    for (i <- 0 until 2) {
      assert(
        dut.io.write.toBoolean == true,
        s"stop bit $i should stay high for first and second half"
      )
      sleep(AuxTxSpec.HalfBitPeriod)
      assert(
        dut.io.write.toBoolean == true,
        s"stop bit $i should stay high for first and second half"
      )
      sleep(AuxTxSpec.HalfBitPeriod)
    }
    for (i <- 0 until 2) {
      assert(
        dut.io.write.toBoolean == false,
        s"stop bit $i should stay low for first and second half"
      )
      sleep(AuxTxSpec.HalfBitPeriod)
      assert(
        dut.io.write.toBoolean == false,
        s"stop bit $i should stay low for first and second half"
      )
      sleep(AuxTxSpec.HalfBitPeriod)
    }
  }

  def checkBit(i: Int, value: Boolean) {
    assert(
      dut.io.write.toBoolean == value,
      s"data bit $i first half should be ${if (value) "high" else "low"}"
    )
    sleep(AuxTxSpec.HalfBitPeriod)
    assert(
      dut.io.write.toBoolean == !value,
      s"data bit $i first half should be ${if (!value) "high" else "low"}"
    )
    sleep(AuxTxSpec.HalfBitPeriod)
  }

  def checkByte(byte: Int) {
    for (i <- 7 to 0 by -1) {
      val bitValue = ((byte >> i) & 1) == 1
      checkBit(i, bitValue)
    } 
  }

  def checkBytes(bytes: Seq[Int]) {
    for (byte <- bytes) {
      checkByte(byte)
    }
  }

  def waitForPacketStart() {
    waitUntil(dut.io.writeEnable.toBoolean)
    sleep(AuxTxSpec.QuarterBitPeriod)
  }

  def checkPacket(bytes: Seq[Int]) {
    waitForPacketStart()
    checkPreCharge()
    checkSync()
    checkSyncEnd()
    checkBytes(bytes)
    checkStop()
  }
}
