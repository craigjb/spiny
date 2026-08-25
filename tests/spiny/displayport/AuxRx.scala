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
import spiny.SimClockDomainExt._
import spinal.lib.{Flow, Fragment, IntRicher}
import spiny.displayport.AuxTxSpec.BitPeriod

object AuxRxSpec {
  val DataRate = 1 MHz
  val BitPeriod = DataRate.toTime
  val HalfBitPeriod = DataRate.toTime / 2
  val SyncBits = 16
  // long enough for the receiver to see the line as stopped
  val AbortGap = 10 us
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
    test(f"AuxRx should properly decode packets @ $clockFreq%s") {
      SpinySimConfig("AuxRx_Packets", clockFreq)
        .compile(AuxRx(dataRate = DataRate))
        .doSim { dut =>
          val dataTimeout = dut.clockDomain.cycles(400 us)
          dut.clockDomain.forkStimulus()

          val checkerThread = fork {
            val checker = AuxRxChecker(dut, dataTimeout)
            for (packet <- Packets) {
              checker.checkPacket(packet)
            }
          }

          dut.io.readEnable #= true
          val driver = AuxRxDriver(dut.io.read)
          for (packet <- Packets) {
            driver.packet(packet)
          }
          sleep(1 us)
          checkerThread.join()
        }
    }

    test(f"AuxRx should handle 30 ns jitter @ $clockFreq%s") {
      SpinySimConfig("AuxRx_Jitter", clockFreq)
        .compile(AuxRx(dataRate = DataRate))
        .doSim { dut =>
          val dataTimeout = dut.clockDomain.cycles(400 us)
          dut.clockDomain.forkStimulus()

          val checkerThread = fork {
            val checker = AuxRxChecker(dut, dataTimeout)
            for (packet <- Packets) {
              checker.checkPacket(packet)
            }
          }

          dut.io.readEnable #= true
          val driver = AuxRxDriver(dut.io.read, maxJitter = 30 ns)
          for (packet <- Packets) {
            driver.packet(packet)
          }
          sleep(1 us)
          checkerThread.join()
        }
    }
  }

  for (clockFreq <- Seq(61 MHz, 66.6 MHz, 100 MHz)) {
    // max glitch size that can be handled depends on filter
    val rawTaps = (50.ns / clockFreq.toTime).toInt
    val taps = if (rawTaps % 2 == 0) rawTaps - 1 else rawTaps
    val maxEdgesAllowed = taps / 2
    val maxGlitch = ((clockFreq.toTime * maxEdgesAllowed) - (0.1 ns))

    test(f"AuxRx should handle glitches <$maxGlitch%.2s @ $clockFreq%s") {
      SpinySimConfig("AuxRx_Glitches", clockFreq)
        .compile(AuxRx(dataRate = DataRate))
        .doSim { dut =>
          val dataTimeout = dut.clockDomain.cycles(400 us)
          dut.clockDomain.forkStimulus()

          val checkerThread = fork {
            val checker = AuxRxChecker(dut, dataTimeout)
            for (packet <- Packets) {
              checker.checkPacket(packet)
            }
          }

          dut.io.readEnable #= true
          val driver = AuxRxDriver(dut.io.read, maxGlitch)
          for (packet <- Packets) {
            driver.packet(packet)
          }
          sleep(1 us)
          checkerThread.join()

        }
    }
  }

  test("AuxRx should abort when readEnable deasserts") {
    SpinySimConfig("AuxRx_ReadEnableAbort", 100 MHz)
      .compile(AuxRx(dataRate = DataRate))
      .doSim { dut =>
        val dataTimeout = dut.clockDomain.cycles(400 us)
        dut.clockDomain.forkStimulus()

        // nothing may be received between the abort and the next packet
        var expectQuiet = true
        var receivedWhileQuiet = Option.empty[Int]
        fork {
          while (true) {
            dut.clockDomain.waitSampling()
            if (expectQuiet && dut.io.data.valid.toBoolean) {
              receivedWhileQuiet = Some(dut.io.data.fragment.toInt)
            }
          }
        }

        dut.io.readEnable #= true
        val driver = AuxRxDriver(dut.io.read)

        // a byte is latched, but only emitted once the next one arrives
        driver.preCharge()
        driver.sync()
        driver.syncEnd()
        driver.data(0xde)

        // drop readEnable in the middle of a packet
        dut.io.readEnable #= false
        dut.io.read #= false
        sleep(AbortGap)

        // raise it again once the line is released
        dut.io.readEnable #= true
        sleep(AbortGap)

        assert(
          receivedWhileQuiet.isEmpty,
          f"AuxRx emitted 0x${receivedWhileQuiet.getOrElse(0)}%02x " +
            "left over from the aborted packet"
        )

        // the next packet should still be received normally
        expectQuiet = false
        val checkerThread = fork {
          AuxRxChecker(dut, dataTimeout).checkPacket(Packets.head)
        }
        driver.packet(Packets.head)
        sleep(1 us)
        checkerThread.join()
      }
  }
}

case class AuxRxDriver(
  auxRead: Bool,
  maxGlitch: TimeNumber = 0 ns,
  maxJitter: TimeNumber = 0 ns,
  rngSeed: Int = 12345
) {
  val rng = new Random(rngSeed)

  def bit(value: Boolean, period: TimeNumber = AuxRxSpec.BitPeriod) {
    val glitchStart = (period * rng.nextDouble())
      .min(period - maxGlitch)
    val glitchLen = maxGlitch * rng.nextDouble()
    val glitchEnd = glitchStart + glitchLen

    val jitterStart = maxJitter * rng.nextDouble()
    val jitterMid = maxJitter * (rng.nextDouble() * (if (rng.nextBoolean()) 1.0 else -1.0))
    val jitterEnd = maxJitter * (rng.nextDouble() * (if (rng.nextBoolean()) 1.0 else -1.0))

    val midBit = (period / 2) + jitterMid

    val edgeTimes = Seq(
      0.ns + jitterStart,
      glitchStart,
      glitchEnd,
      midBit,
    ).distinct.sortBy(_.toDouble)

    var time = 0.ns
    for (edgeTime <- edgeTimes) {
      sleep(edgeTime - time)
      time = edgeTime
      val idealValue = if (time < midBit) value else !value
      val isGlitched = time >= glitchStart && time < glitchEnd
      auxRead #= (if (isGlitched) !idealValue else idealValue)
    }
    sleep(((period + jitterEnd) - time).max(0.ns))
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

object AuxRxChecker {
  def apply(auxRx: AuxRx, dataTimeout: Int): AuxRxChecker = {
    AuxRxChecker(
      rxData = auxRx.io.data,
      dataTimeout = dataTimeout,
      clockDomain = auxRx.clockDomain
    )
  }

  def apply(auxPhy: AuxPhy, dataTimeout: Int): AuxRxChecker = {
    AuxRxChecker(
      rxData = auxPhy.io.rxData,
      dataTimeout = dataTimeout,
      clockDomain = auxPhy.clockDomain
    )
  }
}

case class AuxRxChecker(
  rxData: Flow[Fragment[Bits]],
  dataTimeout: Int,
  clockDomain: ClockDomain
) {
  def checkByte(expected: Int, last: Boolean, index: Int) {
    clockDomain.waitSamplingWhereOrFail(
        dataTimeout, s"data[$index]"
      )(rxData.valid.toBoolean)
    val value = rxData.fragment.toInt
    assert(
      value == expected,
      s"data[$index] should be 0x${expected.hexString()}, but saw 0x${value.hexString()}"
    )
    if (last) {
      assert(rxData.last.toBoolean, s"data[$index] should be last")
    } else {
      assert(!rxData.last.toBoolean, s"data[$index] should not be last")
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
