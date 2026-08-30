/*                           /$$                                             **
**                          |__/                                             **
**        /$$$$$$$  /$$$$$$  /$$ /$$$$$$$  /$$   /$$                         **
**       /$$_____/ /$$__  $$| $$| $$__  $$| $$  | $$                         **
**      |  $$$$$$ | $$  \ $$| $$| $$  \ $$| $$  | $$   (c) Craig J Bishop    **
**       \____  $$| $$  | $$| $$  | $$| $$  | $$   All rights reserved   **
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
import spinal.lib._
import spinal.lib.bus.amba3.apb._
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.regif._

import spiny._
import spiny.SimClockDomainExt._

/** Gives AuxLinkSourceRegs a Component and a bus to live in
 */
case class AuxLinkSourceRegsDut(addressWidth: Int = 8) extends Component {
  val apb3Config = Apb3Config(addressWidth = addressWidth, dataWidth = 32)

  val io = new Bundle {
    val apb = slave(Apb3(apb3Config))
    val phy = master(AuxPhyDataIo())
    val interrupt = out Bool ()
  }

  val busIf = Apb3BusInterface(io.apb, SizeMapping(0, 0))
  val link = AuxLinkSource(maxTimeout = 1 ms, retryLimit = 7)
  link.io.phy <> io.phy
  val regs = link.driveFrom(busIf)
  io.interrupt := regs.interrupt
}

class AuxLinkSourceRegsSpec extends AnyFunSuite {
  import AuxSim._

  // interrupt bits, in the order driveFrom passes the triggers
  val IntDone = 0
  val IntRxOverrun = 1
  val IntRxUnexpected = 2
  val IntRequestDropped = 3

  val Request = Seq(0x90, 0x00, 0x00, 0x00)
  val AckReply = Seq(0x00, 0x12)

  def busy(status: BigInt): Boolean = (status & 1) != 0
  def result(status: BigInt): Int = ((status >> 1) & 0x7).toInt
  def replyLength(status: BigInt): Int = ((status >> 4) & 0x1f).toInt

  def withRegs(name: String)(
    body: (AuxLinkSourceRegsDut, Apb3CheckedDriver, AuxLinkSinkModel) => Unit
  ): Unit = {
    SpinySimConfig.fixedClock(name, 100 MHz)
      .compile(AuxLinkSourceRegsDut())
      .doSim { dut =>
        dut.clockDomain.forkStimulus()
        val apb = Apb3CheckedDriver(dut.io.apb, dut.clockDomain)
        val sink = AuxLinkSinkModel(dut.io.phy, dut.clockDomain)
        dut.clockDomain.waitSampling()
        body(dut, apb, sink)
      }
  }

  /** Polls status until the transaction starts */
  def waitBusy(dut: AuxLinkSourceRegsDut, apb: Apb3CheckedDriver): Unit = {
    val deadline = 1000
    var polls = 0
    while (!busy(apb.read(dut.regs.status.addr)) && polls < deadline) {
      polls += 1
    }
    assert(polls < deadline, "the transaction never went busy")
  }

  /** Polls status until the transaction settles */
  def waitIdle(dut: AuxLinkSourceRegsDut, apb: Apb3CheckedDriver): BigInt = {
    val deadline = 20000
    var polls = 0
    var status = apb.read(dut.regs.status.addr)
    while (busy(status) && polls < deadline) {
      status = apb.read(dut.regs.status.addr)
      polls += 1
    }
    assert(polls < deadline, "timed out polling for the transaction to settle")
    status
  }

  test("AuxLinkSourceRegs should run a transaction through the registers") {
    withRegs("AuxLinkSourceRegs_Ack") { (dut, apb, sink) =>
      sink.replies = List(AckReply)
      sink.start()

      Request.foreach(byte => apb.write(dut.regs.request.addr, byte))
      apb.write(dut.regs.control.addr, 1)
      waitBusy(dut, apb)
      val status = waitIdle(dut, apb)

      assert(result(status) == AuxLinkResult.ack.position,
        s"result field should be ack, was ${result(status)}")
      assert(replyLength(status) == AckReply.length,
        s"replyLength field should be ${AckReply.length}, was " +
          s"${replyLength(status)}")
      assert(sink.requests.toSeq == Seq(Request),
        s"sink should have seen one request, saw ${sink.requests}")

      val raw = apb.read(dut.regs.interruptRaw)
      assert(((raw >> IntDone) & 1) == 1, "done raw bit should be latched")
      assert(((raw >> IntRequestDropped) & 1) == 0,
        "no request byte should have been dropped")

      val reply = AckReply.indices.map(_ => apb.read(dut.regs.reply.addr).toInt & 0xff)
      assert(reply == AckReply, s"reply should be $AckReply, was $reply")
    }
  }

  test("AuxLinkSourceRegs should raise a bus error reading past the reply") {
    withRegs("AuxLinkSourceRegs_ReadPastReply") { (dut, apb, sink) =>
      sink.replies = List(AckReply)
      sink.start()

      Request.foreach(byte => apb.write(dut.regs.request.addr, byte))
      apb.write(dut.regs.control.addr, 1)
      waitBusy(dut, apb)
      waitIdle(dut, apb)

      for (index <- AckReply.indices) {
        val (data, error) = apb.readChecked(dut.regs.reply.addr)
        assert(!error, s"reply byte $index should not raise a bus error")
        assert((data.toInt & 0xff) == AckReply(index),
          s"reply byte $index should be ${AckReply(index)}, was $data")
      }
      // the byte returned past the end looks like data, so the error is the
      // only thing that tells firmware it has drained the reply
      val (_, error) = apb.readChecked(dut.regs.reply.addr)
      assert(error, "reading past the reply should raise a bus error")
    }
  }

  test("AuxLinkSourceRegs should drop a request byte written while busy") {
    withRegs("AuxLinkSourceRegs_DropWhileBusy") { (dut, apb, sink) =>
      // slow enough that the transaction is still running for the late write
      sink.replies = List(AckReply)
      sink.replyDelay = (100 us)
      sink.start()

      Request.foreach(byte => apb.write(dut.regs.request.addr, byte))
      apb.write(dut.regs.control.addr, 1)
      waitBusy(dut, apb)
      apb.write(dut.regs.request.addr, 0xff)

      val raw = apb.read(dut.regs.interruptRaw)
      assert(((raw >> IntRequestDropped) & 1) == 1,
        "requestDropped raw bit should latch the dropped write")

      val status = waitIdle(dut, apb)
      assert(result(status) == AuxLinkResult.ack.position,
        s"the dropped byte should not disturb the transaction, result was " +
          s"${result(status)}")
      assert(sink.requests.toSeq == Seq(Request),
        s"sink should have seen the original request, saw ${sink.requests}")
    }
  }
}
