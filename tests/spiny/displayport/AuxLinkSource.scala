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
  val ClockFreq = 100 MHz
  // native read of 1 byte from DPCD 0x00000
  val Request = Seq(0x90, 0x00, 0x00, 0x00)
  val AckReply = Seq(0x00, 0x12)
  val NackReply = Seq(0x10)
  val DeferReply = Seq(0x20)
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

  def init(
    dut: AuxLinkSource,
    maxRetries: Int = 3,
    replyTimeout: TimeNumber = 300 us
  ): Unit = {
    dut.io.start #= false
    dut.io.request.valid #= false
    dut.io.reply.ready #= false
    dut.io.replyTimeout #= dut.clockDomain.cycles(replyTimeout)
    dut.io.maxRetries #= maxRetries
    dut.clockDomain.forkStimulus()
  }

  /** Builds the DUT, sink model and reply monitor, then runs the body */
  def withLink(
    name: String,
    maxRetries: Int = 3,
    replyTimeout: TimeNumber = 300 us
  )(body: (AuxLinkSource, AuxLinkSinkModel, mutable.ArrayBuffer[Int]) => Unit
  ): Unit = {
    SpinySimConfig.fixedClock(name, ClockFreq)
      .compile(AuxLinkSource(maxTimeout = 1 ms, retryLimit = 7))
      .doSim { dut =>
        init(dut, maxRetries, replyTimeout)
        val reply = monitorReply(dut)
        val sink = AuxLinkSinkModel(dut.io.phy, dut.clockDomain)
        sink.replyDelay = (2 us)
        body(dut, sink, reply)
      }
  }

  /** Waits for the transaction to settle, then for the reply to drain
    *
    *  The reply stream is held back until busy drops, so nothing can be read
    *  until after done.
    */
  def waitDone(dut: AuxLinkSource): Unit = {
    dut.clockDomain.waitSamplingWhereOrFail(
      dut.clockDomain.cycles(2 ms), "the transaction to settle"
    )(dut.io.done.toBoolean)
    dut.clockDomain.waitSampling(dut.io.replyLength.toInt + 4)
  }

  test("AuxLinkSource should complete an acknowledged transaction") {
    withLink("AuxLinkSource_Ack") { (dut, sink, reply) =>
      val overrun = SimPulseMonitor(
        dut.io.rxOverrun, dut.clockDomain, "rxOverrun")
      val unexpected = SimPulseMonitor(
        dut.io.rxUnexpected, dut.clockDomain, "rxUnexpected")
      sink.replies = List(AckReply)
      sink.start()

      sendRequest(dut, Request)
      waitDone(dut)

      assert(dut.io.result.toEnum == AuxLinkResult.ack,
        s"result should be ack, was ${dut.io.result.toEnum}")
      assert(dut.io.replyLength.toInt == AckReply.length,
        s"replyLength should be ${AckReply.length}")
      assert(sink.requests.toSeq == Seq(Request),
        s"sink should have seen one request, saw ${sink.requests}")
      assert(reply.toSeq == AckReply,
        s"reply bytes should be $AckReply, were $reply")
      overrun.assertNone("completing a clean transaction")
      unexpected.assertNone("completing a clean transaction")
    }
  }

  test("AuxLinkSource should not retry a NACK") {
    withLink("AuxLinkSource_Nack") { (dut, sink, reply) =>
      sink.replies = List(NackReply)
      sink.start()

      sendRequest(dut, Request)
      waitDone(dut)

      assert(dut.io.result.toEnum == AuxLinkResult.nack,
        s"result should be nack, was ${dut.io.result.toEnum}")
      assert(sink.requests.length == 1,
        s"a NACK is definitive, so it should not be retried, but " +
          s"${sink.requests.length} requests were sent")
    }
  }

  test("AuxLinkSource should replay the request on DEFER until acknowledged") {
    withLink("AuxLinkSource_DeferRetry") { (dut, sink, reply) =>
      sink.replies = List(DeferReply, DeferReply, AckReply)
      sink.start()

      sendRequest(dut, Request)
      waitDone(dut)

      assert(dut.io.result.toEnum == AuxLinkResult.ack,
        s"result should be ack, was ${dut.io.result.toEnum}")
      assert(sink.requests.length == 3,
        s"should have taken 3 attempts, took ${sink.requests.length}")
      // the whole point of the replay buffer
      assert(sink.requests.forall(_ == Request),
        s"every attempt should replay the same bytes, saw ${sink.requests}")
      assert(reply.toSeq == AckReply,
        s"only the accepted reply should surface, got $reply")
    }
  }

  test("AuxLinkSource should report a timeout once retries run out") {
    withLink("AuxLinkSource_Timeout", maxRetries = 2, replyTimeout = 10 us) {
      (dut, sink, reply) =>
        // a sink that never answers
        sink.replies = Nil
        sink.start()

        sendRequest(dut, Request)
        waitDone(dut)

        assert(dut.io.result.toEnum == AuxLinkResult.timeout,
          s"result should be timeout, was ${dut.io.result.toEnum}")
        assert(sink.requests.length == 3,
          s"should be one attempt plus two retries, was " +
            s"${sink.requests.length}")
        assert(dut.io.replyLength.toInt == 0, "no reply should be buffered")
    }
  }

  test("AuxLinkSource should report defer once retries run out") {
    withLink("AuxLinkSource_DeferExhausted", maxRetries = 2) {
      (dut, sink, reply) =>
        sink.replies = List.fill(5)(DeferReply)
        sink.start()

        sendRequest(dut, Request)
        waitDone(dut)

        assert(dut.io.result.toEnum == AuxLinkResult.defer,
          s"result should be defer, was ${dut.io.result.toEnum}")
        assert(sink.requests.length == 3,
          s"should be one attempt plus two retries, was " +
            s"${sink.requests.length}")
    }
  }

  test("AuxLinkSource should pulse rxUnexpected outside a transaction") {
    withLink("AuxLinkSource_Unexpected") { (dut, sink, reply) =>
      dut.io.phy.txData.ready #= true
      dut.io.phy.rxData.valid #= false
      dut.io.phy.txError #= false
      dut.io.phy.rxError #= false
      // an interrupt register above latches this, so count the pulses
      val unexpected = SimPulseMonitor(
        dut.io.rxUnexpected, dut.clockDomain, "rxUnexpected")
      dut.clockDomain.waitSampling(5)
      unexpected.assertNone("idle with nothing on the line")

      // a sink talking out of turn, with nothing outstanding
      dut.io.phy.rxData.valid #= true
      dut.io.phy.rxData.fragment #= 0x00
      dut.io.phy.rxData.last #= true
      dut.clockDomain.waitSampling()
      dut.io.phy.rxData.valid #= false
      dut.clockDomain.waitSampling(3)

      unexpected.assertOne("receiving an unsolicited packet")
    }
  }
}
