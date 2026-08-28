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
import spinal.lib.sim._

import scala.collection.mutable

import spiny._
import spiny.SimClockDomainExt._

object AuxLinkSourceSpec {
  // AuxLinkSource has no frequency derived constants, so unlike the PHY
  // specs this is not a swept dimension. It is only here to give the
  // simulation a timebase, since forkStimulus and cycles() both need one.
  val ClockFreq = 100 MHz
  // native read of 1 byte from DPCD 0x00000
  val Request = Seq(0x90, 0x00, 0x00, 0x00)
  val AckReply = Seq(0x00, 0x12)
}

class AuxLinkSourceSpec extends AnyFunSuite {
  import AuxLinkSourceSpec._

  /** Drives the request stream, then pulses start */
  def sendRequest(dut: AuxLinkSource, bytes: Seq[Int]): Unit = {
    for (byte <- bytes) {
      dut.io.request.valid #= true
      dut.io.request.payload #= byte
      dut.clockDomain.waitSamplingWhere(dut.io.request.ready.toBoolean)
    }
    dut.io.request.valid #= false
    dut.io.start #= true
    dut.clockDomain.waitSampling()
    dut.io.start #= false
  }

  /** Collects reply bytes in the background, draining as they arrive */
  def monitorReply(dut: AuxLinkSource): mutable.ArrayBuffer[Int] = {
    val bytes = mutable.ArrayBuffer[Int]()
    dut.io.reply.ready #= true
    StreamMonitor(dut.io.reply, dut.clockDomain) { payload =>
      bytes += payload.toInt
    }
    bytes
  }

  def init(dut: AuxLinkSource, maxRetries: Int = 3): Unit = {
    dut.io.start #= false
    dut.io.request.valid #= false
    dut.io.reply.ready #= false
    dut.io.clearFlags #= false
    dut.io.replyTimeout #= dut.clockDomain.cycles(300 us)
    dut.io.maxRetries #= maxRetries
    dut.clockDomain.forkStimulus()
  }

  test("AuxLinkSource should complete an acknowledged transaction") {
    SpinySimConfig.fixedClock("AuxLinkSource_Ack", ClockFreq)
      .compile(AuxLinkSource())
      .doSim { dut =>
        val timeout = dut.clockDomain.cycles(2 ms)
        init(dut)
        val reply = monitorReply(dut)
        val sink = AuxLinkSinkModel(dut.io.phy, dut.clockDomain)
        sink.replies = List(AckReply)
        sink.start()

        sendRequest(dut, Request)
        dut.clockDomain.waitSamplingWhereOrFail(timeout, "done")(
          dut.io.done.toBoolean)

        assert(dut.io.result.toEnum == AuxLinkResult.ack,
          s"result should be ack, was ${dut.io.result.toEnum}")
        assert(dut.io.replyLength.toInt == AckReply.length,
          s"replyLength should be ${AckReply.length}")
        assert(sink.requests.toSeq == Seq(Request),
          s"sink should have seen one request, saw ${sink.requests}")
        assert(reply.toSeq == AckReply,
          s"reply bytes should be $AckReply, were $reply")
        assert(!dut.io.rxOverrun.toBoolean && !dut.io.rxUnexpected.toBoolean,
          "no sticky flags should be set")
      }
  }
}
