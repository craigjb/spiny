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

  /** Drives the request stream, then pulses start
    */
  def sendRequest(dut: AuxLinkSource, bytes: Seq[Int]): Unit = {
    for (byte <- bytes) {
      dut.io.request.valid #= true
      dut.io.request.payload #= byte
      dut.clockDomain.waitSamplingWhereOrFail(
        dut.clockDomain.cycles(1 ms), f"the buffer to accept 0x$byte%02x"
      )(dut.io.request.ready.toBoolean)
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

  test("AuxLinkSource should accept a reply that outlasts the timeout") {
    // The timeout is the sink's turnaround budget, not a deadline for the
    // whole reply. A 16 byte read answered near the limit still takes another
    // ~130 us to arrive, so only the gaps between bytes are timed.
    val longReply = 0x00 +: Seq.tabulate(16)(i => 0x10 + i)
    withLink("AuxLinkSource_SlowReply", replyTimeout = 300 us) {
      (dut, sink, reply) =>
        sink.replies = List(longReply)
        sink.replyDelay = (200 us)
        sink.replyByteGap = (10 us)
        sink.start()

        sendRequest(dut, Request)
        waitDone(dut)

        assert(dut.io.result.toEnum == AuxLinkResult.ack,
          s"result should be ack, was ${dut.io.result.toEnum}")
        assert(sink.requests.length == 1,
          s"the reply started in time, so it should not have retried, but " +
            s"the sink saw ${sink.requests.length} requests")
        assert(reply.toSeq == longReply,
          s"reply bytes should be $longReply, were $reply")
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

  test("AuxLinkSource should drop a packet that arrives on top of a reply") {
    withLink("AuxLinkSource_Overrun") { (dut, sink, reply) =>
      val overrun = SimPulseMonitor(
        dut.io.rxOverrun, dut.clockDomain, "rxOverrun")
      // driving the reply by hand, so the sink model is left stopped
      sendRequest(dut, Request)
      dut.clockDomain.waitSampling(10)

      // the reply, then a byte of a second packet with no gap, which lands
      // while the first is still buffered
      val burst = Seq((0x00, false), (0x12, true), (0x99, false))
      for ((byte, last) <- burst) {
        dut.io.phy.rxData.valid #= true
        dut.io.phy.rxData.fragment #= byte
        dut.io.phy.rxData.last #= last
        dut.clockDomain.waitSampling()
      }
      dut.io.phy.rxData.valid #= false
      waitDone(dut)

      overrun.assertOne("a packet arriving on top of a buffered one")
      assert(dut.io.result.toEnum == AuxLinkResult.ack,
        s"the first reply should still be accepted, was " +
          s"${dut.io.result.toEnum}")
      // dropped rather than appended, so the held reply stays intact
      assert(dut.io.replyLength.toInt == AckReply.length,
        s"replyLength should still be ${AckReply.length}, was " +
          s"${dut.io.replyLength.toInt}")
      assert(reply.toSeq == AckReply,
        s"the overrunning byte should not appear, got $reply")
    }
  }

  test("AuxLinkSource should retry after a PHY error") {
    withLink("AuxLinkSource_PhyErrorRetry", maxRetries = 1) {
      (dut, sink, reply) =>
        // first attempt gets no reply, it is failed by the PHY error instead
        sink.replies = List(Seq(), AckReply)
        sink.start()

        sendRequest(dut, Request)
        dut.clockDomain.waitSampling(10)
        dut.io.phy.rxError #= true
        dut.clockDomain.waitSampling()
        dut.io.phy.rxError #= false
        waitDone(dut)

        assert(dut.io.result.toEnum == AuxLinkResult.ack,
          s"the retry should have succeeded, was ${dut.io.result.toEnum}")
        assert(sink.requests.length == 2,
          s"a PHY error should be retried, but " +
            s"${sink.requests.length} requests were sent")
    }
  }

  test("AuxLinkSource should report a PHY error once retries run out") {
    withLink("AuxLinkSource_PhyError", maxRetries = 0) { (dut, sink, reply) =>
      sink.replies = Nil
      sink.start()

      sendRequest(dut, Request)
      dut.clockDomain.waitSampling(10)
      dut.io.phy.rxError #= true
      dut.clockDomain.waitSampling()
      dut.io.phy.rxError #= false
      waitDone(dut)

      assert(dut.io.result.toEnum == AuxLinkResult.phyError,
        s"result should be phyError, was ${dut.io.result.toEnum}")
      assert(sink.requests.length == 1,
        s"no retries were allowed, but ${sink.requests.length} were sent")
    }
  }

  test("AuxLinkSource should backpressure the request while busy") {
    withLink("AuxLinkSource_Backpressure") { (dut, sink, reply) =>
      sink.replies = List(AckReply)
      sink.start()

      // let the sim settle before reading a combinational output
      dut.clockDomain.waitSampling(2)
      assert(dut.io.request.ready.toBoolean,
        "the buffer should accept bytes while idle")

      sendRequest(dut, Request)
      dut.clockDomain.waitSampling(2)
      assert(dut.io.busy.toBoolean, "should be busy after start")
      assert(!dut.io.request.ready.toBoolean,
        "loading must be refused while a transaction is in flight, or a " +
          "retry could replay corrupted bytes")

      waitDone(dut)
      assert(!dut.io.busy.toBoolean, "should be idle once settled")
      assert(dut.io.request.ready.toBoolean,
        "the buffer should accept the next request once settled")
    }
  }
}
