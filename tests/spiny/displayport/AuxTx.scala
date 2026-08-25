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
import spiny.SimClockDomainExt._

object AuxTxSpec {
  val PacketGap = 10 us
  val Packets = Seq(
    Seq(0xde, 0xad, 0xbe, 0xef),
    Seq(0xa, 0xb, 0xc, 0xd, 0xe, 0xf),
    Seq(0xab),
    // all zeros encodes the same as sync, all ones is the other extreme
    Seq(0x00, 0xff, 0x00, 0xff),
    Seq(0xff, 0x00, 0xff, 0x00)
  )
}

class AuxTxSpec extends AnyFunSuite {
  import AuxSim._
  import AuxTxSpec._

  for (clockFreq <- ClockFreqs) {
    test(f"AuxTx should properly encode and transmit packets @ $clockFreq%S") {
      SpinySimConfig("AuxTx_Packets", clockFreq)
        .compile(AuxTx(dataRate = DataRate))
        .doSim { dut =>
          val timeout = dut.clockDomain.cycles(400 us)
          dut.clockDomain.forkStimulus()

          val checkerThread = fork {
            dut.clockDomain.waitSampling()
            val checker = AuxTxChecker(dut)
            for ((packet, i) <- Packets.zipWithIndex) {
              SpinalInfo(s"Checking packet $i")
              checker.checkPacket(packet)
            }
          }

          val errors = AuxTxErrorMonitor(dut)
          val driver = AuxTxDriver(dut, timeout)
          dut.clockDomain.waitSampling(10)
          for (packet <- Packets) {
            driver.write(packet)
            dut.clockDomain.waitSamplingWhereOrFail(
                timeout, "writeEnable to drop"
              )(!dut.io.writeEnable.toBoolean)
            sleep(PacketGap)
          }
          checkerThread.join()
          errors.assertNone("transmitting good packets")
        }
    }
  }

  test("AuxTx should abort on no data") {
    SpinySimConfig("AuxTx_NoDataAbort", 100 MHz)
      .compile(AuxTx(dataRate = DataRate))
      .doSim { dut =>
        val timeout = dut.clockDomain.cycles(400 us)
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

        val errors = AuxTxErrorMonitor(dut)
        val driver = AuxTxDriver(dut, timeout)
        dut.clockDomain.waitSampling(10)

        // kick off packet, but then deassert valid
        dut.io.data.fragment #= 0xde
        dut.io.data.last #= false
        dut.io.data.valid #= true
        dut.clockDomain.waitSampling()
        dut.io.data.valid #= false

        dut.clockDomain.waitSamplingWhereOrFail(
            timeout, "writeEnable to drop"
          )(!dut.io.writeEnable.toBoolean)
        sleep(PacketGap)
        checkerThread.join()
        errors.assertOne("aborting with no data")
      }
  }

  test("AuxTx should abort on data underrun") {
    SpinySimConfig("AuxTx_UnderrunAbort", 100 MHz)
      .compile(AuxTx(dataRate = DataRate))
      .doSim { dut =>
        val timeout = dut.clockDomain.cycles(400 us)
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

        val errors = AuxTxErrorMonitor(dut)
        val driver = AuxTxDriver(dut, timeout)
        dut.clockDomain.waitSampling(10)
        driver.write(0xde, false)
        dut.clockDomain.waitSamplingWhereOrFail(
            timeout, "writeEnable to drop"
          )(!dut.io.writeEnable.toBoolean)
        sleep(PacketGap)
        checkerThread.join()
        errors.assertOne("aborting on underrun")
      }
  }
}
