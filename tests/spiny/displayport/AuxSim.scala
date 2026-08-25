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

import scala.util.Random

import spinal.core._
import spinal.core.sim._
import spinal.lib.{Stream, Flow, Fragment, IntRicher}

import spiny._
import spiny.SimClockDomainExt._

/** Constants shared by AUX tests */
object AuxSim {
  val ClockFreqs = Seq(20 MHz, 20.33 MHz, 21.3 MHz, 25.3 MHz, 66.6 MHz, 100 MHz)
  val DataRate = 1 MHz
  val BitPeriod = DataRate.toTime
  val SyncBits = 16
}

object AuxTxDriver {
  def apply(auxTx: AuxTx, timeout: Int): AuxTxDriver = {
    AuxTxDriver(
      txData = auxTx.io.data,
      timeout = timeout,
      clockDomain = auxTx.clockDomain
    )
  }

  def apply(auxPhy: AuxPhy, timeout: Int): AuxTxDriver = {
    AuxTxDriver(
      txData = auxPhy.io.txData,
      timeout = timeout,
      clockDomain = auxPhy.clockDomain
    )
  }
}

case class AuxTxDriver(
  txData: Stream[Fragment[Bits]],
  timeout: Int,
  clockDomain: ClockDomain
) {
  txData.valid #= false

  def write(byte: Int, last: Boolean) {
    txData.fragment #= byte
    txData.last #= last
    txData.valid #= true
    clockDomain.waitSamplingWhereOrFail(
        timeout, f"txData.ready on 0x$byte%02x"
      )(txData.ready.toBoolean)
    txData.valid #= false
  }

  def write(bytes: Seq[Int]) {
    for(byte <- bytes.init) {
      write(byte, false)
    }
    write(bytes.last, true)
  }
}

object AuxTxErrorMonitor {
  def apply(auxTx: AuxTx): SimPulseMonitor = {
    SimPulseMonitor(
      signal = auxTx.io.error,
      clockDomain = auxTx.clockDomain,
      name = "AuxTx error"
    )
  }

  def apply(auxPhy: AuxPhy): SimPulseMonitor = {
    SimPulseMonitor(
      signal = auxPhy.io.txError,
      clockDomain = auxPhy.clockDomain,
      name = "AuxTx error"
    )
  }
}

object AuxTxChecker {
  def apply(auxTx: AuxTx): AuxTxChecker = {
    AuxTxChecker(
      auxWrite = auxTx.io.write,
      auxWriteEnable = auxTx.io.writeEnable,
      clocksPerHalfBit = auxTx.phaseTick.stateCount.toInt,
      clockDomain = auxTx.clockDomain
    )
  }

  /** Checks the AUX line an AuxPhy drives, rather than the AuxTx port */
  def apply(auxPhy: AuxPhy): AuxTxChecker = {
    AuxTxChecker(
      auxWrite = auxPhy.io.aux.write,
      auxWriteEnable = auxPhy.io.aux.writeEnable,
      clocksPerHalfBit = auxPhy.tx.phaseTick.stateCount.toInt,
      clockDomain = auxPhy.clockDomain
    )
  }
}

case class AuxTxChecker(
  auxWrite: Bool,
  auxWriteEnable: Bool,
  clocksPerHalfBit: Int,
  clockDomain: ClockDomain
) {
  def checkPreCharge() {
    for (i <- 0 until 16) {
      assert(
        auxWrite.toBoolean == false,
        s"precharge bit $i first half should be low"
      )
      clockDomain.waitSampling(clocksPerHalfBit)
      assert(
        auxWrite.toBoolean == true,
        s"precharge bit $i second half should be high"
      )
      clockDomain.waitSampling(clocksPerHalfBit)
    }
  }

  def checkSync() {
    for (i <- 0 until 16) {
      assert(
        auxWrite.toBoolean == false,
        s"sync bit $i first half should be low"
      )
      clockDomain.waitSampling(clocksPerHalfBit)
      assert(
        auxWrite.toBoolean == true,
        s"sync bit $i second half should be high"
      )
      clockDomain.waitSampling(clocksPerHalfBit)
    }
  }

  def checkSyncEnd() {
    for (i <- 0 until 2) {
      assert(
        auxWrite.toBoolean == true,
        s"sync end bit $i should stay high for first and second half"
      )
      clockDomain.waitSampling(clocksPerHalfBit)
      assert(
        auxWrite.toBoolean == true,
        s"sync end bit $i should stay high for first and second half"
      )
      clockDomain.waitSampling(clocksPerHalfBit)
    }
    for (i <- 0 until 2) {
      assert(
        auxWrite.toBoolean == false,
        s"sync end bit $i should stay low for first and second half"
      )
      clockDomain.waitSampling(clocksPerHalfBit)
      assert(
        auxWrite.toBoolean == false,
        s"sync end bit $i should stay low for first and second half"
      )
      clockDomain.waitSampling(clocksPerHalfBit)
    }
  }

  def checkStop() {
    for (i <- 0 until 2) {
      assert(
        auxWrite.toBoolean == true,
        s"stop bit $i should stay high for first and second half"
      )
      clockDomain.waitSampling(clocksPerHalfBit)
      assert(
        auxWrite.toBoolean == true,
        s"stop bit $i should stay high for first and second half"
      )
      clockDomain.waitSampling(clocksPerHalfBit)
    }
    for (i <- 0 until 2) {
      assert(
        auxWrite.toBoolean == false,
        s"stop bit $i should stay low for first and second half"
      )
      clockDomain.waitSampling(clocksPerHalfBit)
      assert(
        auxWrite.toBoolean == false,
        s"stop bit $i should stay low for first and second half"
      )
      clockDomain.waitSampling(clocksPerHalfBit)
    }
  }

  def checkBit(i: Int, value: Boolean) {
    assert(
      auxWrite.toBoolean == value,
      s"data bit $i first half should be ${if (value) "high" else "low"}"
    )
    clockDomain.waitSampling(clocksPerHalfBit)
    assert(
      auxWrite.toBoolean == !value,
      s"data bit $i first half should be ${if (!value) "high" else "low"}"
    )
    clockDomain.waitSampling(clocksPerHalfBit)
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
    waitUntil(auxWriteEnable.toBoolean)
    clockDomain.waitSampling(clocksPerHalfBit / 2)
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

case class AuxRxDriver(
  auxRead: Bool,
  maxGlitch: TimeNumber = 0 ns,
  maxJitter: TimeNumber = 0 ns,
  rngSeed: Int = 12345,
  bitPeriod: TimeNumber = AuxSim.BitPeriod
) {
  val rng = new Random(rngSeed)

  def bit(value: Boolean, period: TimeNumber = bitPeriod) {
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
    for (i <- 0 until AuxSim.SyncBits) {
      bit(false)
    }
  }

  def syncEnd() {
    bit(true, bitPeriod * 4)
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

object AuxRxErrorMonitor {
  def apply(auxRx: AuxRx): SimPulseMonitor = {
    SimPulseMonitor(
      signal = auxRx.io.error,
      clockDomain = auxRx.clockDomain,
      name = "AuxRx error"
    )
  }

  def apply(auxPhy: AuxPhy): SimPulseMonitor = {
    SimPulseMonitor(
      signal = auxPhy.io.rxError,
      clockDomain = auxPhy.clockDomain,
      name = "AuxRx error"
    )
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
