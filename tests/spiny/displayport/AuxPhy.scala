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

object AuxPhySpec {
  val ClockFreqs = Seq(20 MHz, 20.33 MHz, 21.3 MHz, 25.3 MHz, 66.6 MHz, 100 MHz)
  val DataRate = 1 MHz
  // time the sink takes to turn the line around and answer
  val ReplyDelay = 20 us
  // idle time between one transaction and the next
  val TransactionGap = 20 us

  // native AUX requests paired with the reply the sink sends back.
  // reply byte 0 is the AUX_ACK header, any remaining bytes are read data.
  val Transactions = Seq(
    // read DPCD 0x00000 (revision)
    (Seq(0x90, 0x00, 0x00, 0x00), Seq(0x00, 0x12)),
    // write one byte to DPCD 0x00100
    (Seq(0x80, 0x00, 0x01, 0x00, 0x0a), Seq(0x00)),
    // read four bytes from DPCD 0x00002
    (Seq(0x90, 0x00, 0x02, 0x03), Seq(0x00, 0xde, 0xad, 0xbe, 0xef))
  )
}

class AuxPhySpec extends AnyFunSuite {
  import AuxPhySpec._

  for (clockFreq <- ClockFreqs) {
    val timeout = SimCycles(400 us, clockFreq)

    test(f"AuxPhy should request and reply via loopback @ $clockFreq%S") {
      SpinySimConfig("AuxPhy_Loopback", clockFreq)
        .compile(AuxPhy(dataRate = DataRate))
        .doSim { dut =>
          dut.clockDomain.forkStimulus()
          dut.io.aux.read #= false

          // AuxRX must ignore when AuxTx is transmitting, otherwise 
          // the Phy is talking to itself
          var receivedWhileTransmitting = false
          fork {
            while (true) {
              dut.clockDomain.waitSampling()
              if (dut.io.aux.writeEnable.toBoolean) {
                dut.io.aux.read #= dut.io.aux.write.toBoolean
                if (dut.io.rxData.valid.toBoolean) {
                  receivedWhileTransmitting = true
                }
              }
            }
          }

          val txDriver = AuxTxDriver(dut, timeout)
          val rxDriver = AuxRxDriver(dut.io.aux.read)

          for (((request, reply), i) <- Transactions.zipWithIndex) {
            SpinalInfo(s"Checking transaction $i")

            // send the request and check it
            val txCheckerThread = fork {
              dut.clockDomain.waitSampling()
              AuxTxChecker(dut).checkPacket(request)
            }
            dut.clockDomain.waitSampling(10)
            txDriver.write(request)

            val txTimedOut = dut.clockDomain
              .waitSamplingWhere(timeout)(!dut.io.aux.writeEnable.toBoolean)
            assert(!txTimedOut, s"transaction $i: AUX line was never released")
            txCheckerThread.join()

            // let the sink answer, and check what the PHY decodes
            sleep(ReplyDelay)
            val rxCheckerThread = fork {
              AuxRxChecker(dut, timeout).checkPacket(reply)
            }
            rxDriver.packet(reply)
            rxCheckerThread.join()

            sleep(TransactionGap)
          }

          assert(
            !receivedWhileTransmitting,
            "rxData went valid while the PHY was driving the AUX line"
          )
        }
    }
  }
}
