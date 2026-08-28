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

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

/** Replayable byte buffer for one AUX packet
 *
 *  Loaded once, then sent as many times as the retry logic needs. Rewinding
 *  rather than draining is what lets a transaction be retried without the
 *  layer above having to supply the bytes again.
 */
case class AuxPacketBuffer(depth: Int) extends Area {
  val addrWidth = log2Up(depth)
  val countWidth = log2Up(depth + 1)

  val mem = Mem(Bits(8 bits), depth)
  val length = Reg(UInt(countWidth bits)) init(0)
  val readPtr = Reg(UInt(countWidth bits)) init(0)

  val readData = mem.readAsync(readPtr.resize(addrWidth))
  val isLast = readPtr === (length - 1)
  val isFull = length === depth
  val isEmpty = length === 0

  /** Appends a byte at the write position */
  def load(data: Bits): Unit = {
    mem.write(length.resize(addrWidth), data)
    length := length + 1
  }

  /** Restarts sending from the first byte, keeping the contents */
  def rewind(): Unit = readPtr := 0

  /** Steps to the next byte to send */
  def advance(): Unit = readPtr := readPtr + 1

  def clear(): Unit = {
    length := 0
    readPtr := 0
  }
}

/** Captures one received AUX packet
 *
 *  Holds a packet until the layer above drains it. A packet arriving while
 *  one is already held is dropped rather than overwriting, since the held
 *  packet is the one that answers the outstanding request.
 */
case class AuxCaptureBuffer(depth: Int) extends Area {
  val addrWidth = log2Up(depth)
  val countWidth = log2Up(depth + 1)

  val mem = Mem(Bits(8 bits), depth)
  val length = Reg(UInt(countWidth bits)) init(0)
  val readPtr = Reg(UInt(countWidth bits)) init(0)
  /** A whole packet is buffered and not yet drained */
  val complete = Reg(Bool()) init(False)

  val readData = mem.readAsync(readPtr.resize(addrWidth))
  val firstByte = mem.readAsync(U(0, addrWidth bits))
  val isDrained = readPtr === length
  val isFull = length === depth

  /** Captures a byte, returning whether it had to be dropped */
  def capture(data: Bits, last: Bool): Bool = {
    val dropped = False
    when(complete || isFull) {
      dropped := True
    } otherwise {
      mem.write(length.resize(addrWidth), data)
      length := length + 1
      when(last) {
        complete := True
      }
    }
    dropped
  }

  def drain(): Unit = readPtr := readPtr + 1

  def clear(): Unit = {
    length := 0
    readPtr := 0
    complete := False
  }
}

/** Outcome of a completed AUX transaction */
object AuxLinkResult extends SpinalEnum {
  /** Sink replied AUX_ACK */
  val ack = newElement()
  /** Sink replied AUX_NACK, which is a definitive answer and is not retried */
  val nack = newElement()
  /** Sink kept replying AUX_DEFER until the retries ran out */
  val defer = newElement()
  /** No reply arrived before the timeout, on every attempt */
  val timeout = newElement()
  /** The PHY reported a dropped packet on every attempt */
  val phyError = newElement()
}

/** Native AUX reply header, per DisplayPort section 2.4.1.2 */
object AuxReplyCode {
  val ack = 0x0
  val nack = 0x1
  val defer = 0x2
}

/** DisplayPort AUX source side link layer IO ports
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class AuxLinkSourceIo(
  requestDepth: Int,
  replyDepth: Int,
  timeoutWidth: Int,
  retryWidth: Int
) extends Bundle {
  /** Packet data plane of an [[AuxPhy]]
   *  @group ports */
  val phy = master(AuxPhyDataIo())

  /** Request bytes to transmit
   *
   *  Loaded into the paket buffer before start. Backpressures while a
   *  transaction is running, so an in flight retry cannot be corrupted.
   *  @group ports */
  val request = slave(Stream(Bits(8 bits)))

  /** Received reply bytes, valid once done has pulsed
   *
   *  Buffered, so the layer above can take as long as it needs. Starting
   *  another transaction discards whatever has not been drained.
   *  @group ports */
  val reply = master(Stream(Bits(8 bits)))

  /** Pulse to begin transmitting the loaded request
   *  @group ports */
  val start = in Bool()

  /** High from start until the transaction settles
   *  @group ports */
  val busy = out Bool()

  /** Single cycle pulse when a transaction settles, for an interrupt
   *  @group ports */
  val done = out Bool()

  /** Outcome, held until the next transaction starts
   *  @group ports */
  val result = out(AuxLinkResult())

  /** Bytes captured in the reply
   *  @group ports */
  val replyLength = out UInt (log2Up(replyDepth + 1) bits)

  /** Clocks to wait for a reply before retrying (300 µs per the spec)
   *  @group ports */
  val replyTimeout = in UInt (timeoutWidth bits)

  /** Extra attempts after the first, on timeout, DEFER, or a PHY error
   *  @group ports */
  val maxRetries = in UInt (retryWidth bits)

  /** Sticky: a packet arrived while one was already buffered, or overran it
   *  @group ports */
  val rxOverrun = out Bool()

  /** Sticky: a packet arrived outside of a transaction
   *  @group ports */
  val rxUnexpected = out Bool()

  /** Clears the sticky flags
   *  @group ports */
  val clearFlags = in Bool()
}

object AuxLinkSource {
  /** Automatically calculates timer and retry bit widths
    *
    * @param maxTimeout Longest reply timeout that replyTimeout can hold
    * @param retryLimit Largest value that maxRetries can hold
    * @param requestDepth Request bytes buffered (4 header + 16 data)
    * @param replyDepth Reply bytes buffered (1 header + 16 data)
    */
  def apply(
    maxTimeout: TimeNumber = 300 us,
    retryLimit: Int = 7,
    requestDepth: Int = 20,
    replyDepth: Int = 17
  ): AuxLinkSource = {
    val clockFreq = ClockDomain.current.frequency.getValue
    val maxCycles = (maxTimeout.toBigDecimal * clockFreq.toBigDecimal)
      .setScale(0, BigDecimal.RoundingMode.CEILING)
      .toBigInt
    AuxLinkSource(
      requestDepth = requestDepth,
      replyDepth = replyDepth,
      timeoutWidth = log2Up(maxCycles + 1),
      retryWidth = log2Up(retryLimit + 1)
    )
  }
}

/** DisplayPort AUX source side link layer
 *
 *  Frames one transaction at a time onto an [[AuxPhy]]: sends the loaded
 *  request, waits for a reply, and retries on timeout, AUX_DEFER, or a dropped
 *  packet. AUX_NACK is a definitive answer and is never retried.
 *
 *  The request lives in a replay buffer, so a retry re-sends it without the
 *  layer above supplying the bytes again.
 *
 *  Only native AUX reply codes are decoded.
 *
 * @param requestDepth Request bytes buffered
 * @param replyDepth Reply bytes buffered
 * @param timeoutWidth Width of the reply timeout counter (counts clock cycles)
 * @param retryWidth Width of the retry counter
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class AuxLinkSource(
  requestDepth: Int,
  replyDepth: Int,
  timeoutWidth: Int,
  retryWidth: Int
) extends Component {
  /** SpinalHDL IO ports
   *  @group ports */
  val io = AuxLinkSourceIo(requestDepth, replyDepth, timeoutWidth, retryWidth)

  val request = AuxPacketBuffer(requestDepth)
  val reply = AuxCaptureBuffer(replyDepth)

  val retryCount = Reg(UInt(retryWidth bits)) init (0)
  val timer = Reg(UInt(timeoutWidth bits)) init (0)
  val timerExpired = timer === 0

  val resultReg = Reg(AuxLinkResult()) init (AuxLinkResult.ack)
  // why the current attempt failed, reported if the retries run out
  val pendingResult = Reg(AuxLinkResult()) init (AuxLinkResult.timeout)
  val busyReg = RegInit(False)
  val doneReg = RegInit(False)
  val overrunReg = RegInit(False)
  val unexpectedReg = RegInit(False)

  io.busy := busyReg
  io.done := doneReg
  io.result := resultReg
  io.replyLength := reply.length
  io.rxOverrun := overrunReg
  io.rxUnexpected := unexpectedReg

  doneReg := False

  when(io.clearFlags) {
    overrunReg := False
    unexpectedReg := False
  }

  // loading is only allowed between transactions
  io.request.ready := !busyReg && !request.isFull
  when(io.request.fire) {
    request.load(io.request.payload)
  }

  io.reply.valid := reply.complete && !reply.isDrained
  io.reply.payload := reply.readData
  when(io.reply.fire) {
    reply.drain()
  }

  io.phy.txData.valid := False
  io.phy.txData.fragment := request.readData
  io.phy.txData.last := request.isLast

  when(timer =/= 0) {
    timer := timer - 1
  }

  val fsm = new StateMachine {
    val stateIdle: State = new State with EntryPoint {
      whenIsActive {
        when(io.phy.rxData.valid) {
          unexpectedReg := True
        }
        when(io.start && !request.isEmpty) {
          retryCount := 0
          busyReg := True
          reply.clear()
          request.rewind()
          goto(stateSend)
        }
      }
    }

    val stateSend: State = new State {
      whenIsActive {
        io.phy.txData.valid := True
        when(io.phy.txData.fire) {
          request.advance()
          when(request.isLast) {
            timer := io.replyTimeout
            goto(stateWait)
          }
        }
        when(io.phy.txError) {
          pendingResult := AuxLinkResult.phyError
          goto(stateRetry)
        }
      }
    }

    val stateWait: State = new State {
      whenIsActive {
        when(io.phy.rxData.valid) {
          when(reply.capture(io.phy.rxData.fragment, io.phy.rxData.last)) {
            overrunReg := True
          }
        }
        when(io.phy.rxError) {
          pendingResult := AuxLinkResult.phyError
          goto(stateRetry)
        } elsewhen (reply.complete) {
          goto(stateEvaluate)
        } elsewhen (timerExpired) {
          pendingResult := AuxLinkResult.timeout
          goto(stateRetry)
        }
      }
    }

    val stateEvaluate: State = new State {
      whenIsActive {
        val code = reply.firstByte(7 downto 4).asUInt
        switch(code) {
          is(AuxReplyCode.ack) {
            resultReg := AuxLinkResult.ack
            goto(stateDone)
          }
          is(AuxReplyCode.nack) {
            // definitive, so no retry
            resultReg := AuxLinkResult.nack
            goto(stateDone)
          }
          default {
            // DEFER, or a code this does not decode
            pendingResult := AuxLinkResult.defer
            goto(stateRetry)
          }
        }
      }
    }

    val stateRetry: State = new State {
      whenIsActive {
        when(retryCount < io.maxRetries) {
          retryCount := retryCount + 1
          reply.clear()
          request.rewind()
          goto(stateSend)
        } otherwise {
          resultReg := pendingResult
          goto(stateDone)
        }
      }
    }

    val stateDone: State = new State {
      whenIsActive {
        doneReg := True
        busyReg := False
        // free the buffer for the next request
        request.clear()
        goto(stateIdle)
      }
    }
  }
}
