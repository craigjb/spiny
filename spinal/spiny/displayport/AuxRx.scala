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

import spiny._

/** RX side of DisplayPort AUX channel */
case class AuxRx(
  dataRate: HertzNumber = 1 MHz,
  tolerance: BigDecimal = 0.15,
  filterWindow: TimeNumber = 50 ns
) extends Component {
  assert(
    ClockDomain.current.frequency.getValue >= 20.MHz,
    "AuxRx must be clocked at ≥20 MHz")

  val io = new Bundle {
    val read = in(Bool())
    val readEnable = in(Bool())
    val data = master(Flow(Fragment(Bits(8 bits))))
    // pulse on error and packet is dropped (e.g. for user retry or abort logic)
    val error = out(Bool())
  }

  val ratio = ClockDomain.current.frequency.getValue / dataRate
  // calculate and round each one separately to avoid cascading rounding error
  val clocksPerBit = ratio.setScale(0, BigDecimal.RoundingMode.HALF_UP).toInt
  val clocksPerHalfBit = (ratio / 2).setScale(0, BigDecimal.RoundingMode.HALF_UP).toInt
  val clocksPerTol = (tolerance * ratio).setScale(0, BigDecimal.RoundingMode.HALF_UP).toInt

  // synchronize external input
  val readSynced = BufferCC(io.read, init = False)
  val readValue = AuxRxFilter.on(readSynced, filterWindow)

  // find all edges
  val readReg = RegNext(readValue) init(False)
  val edgeDetected = readValue =/= readReg

  val intervalTimer = Counter(clocksPerBit * 3)
  when(!io.readEnable || edgeDetected) {
    intervalTimer.clear()
  } elsewhen(!intervalTimer.willOverflowIfInc) {
    intervalTimer.increment()
  }

  // half bit period is measured on sync pulses to adapt bit period
  // (required to meet spec UI range)
  val measuredHalfBit = Reg(UInt(intervalTimer.getWidth bits))
    .init(clocksPerHalfBit)

  // define intervals between edges
  // half bit stays against the nominal rate, since it's used to
  // acquire the measurements for the other intervals
  val isBounce = intervalTimer <= clocksPerHalfBit - clocksPerTol
  val isShort = intervalTimer >= (clocksPerHalfBit - clocksPerTol) &&
                intervalTimer <= (clocksPerHalfBit + clocksPerTol)
  // these use the measured bit period
  val isLong = intervalTimer >= (measuredHalfBit * 2 - clocksPerTol) &&
               intervalTimer <= (measuredHalfBit * 2 + clocksPerTol)
  val isStop = intervalTimer >= measuredHalfBit * 3

  val nextEdgeIsMidBit = RegInit(True)
  val highViolationOccurred = RegInit(False)

  val bitCounter = Counter(8)

  val shiftRegNext = Bits(8 bits)
  val shiftReg = RegNext(shiftRegNext)
  shiftRegNext := shiftReg

  val isDataLatched = RegInit(False)
  io.data.fragment.setAsReg()
  io.data.last := False
  io.data.valid := False
  io.error := False

  val fsm = new StateMachine {
    always {
      // abort whatever packet is in flight, so no half-received data
      // leaks into the next one
      when(!io.readEnable) {
        forceGoto(stateIdle)
      }
    }

    val stateIdle: State = new State with EntryPoint {
      whenIsActive {
        when(io.readEnable) {
          goto(stateHuntSyncEnd)
        }
      }
    }

    val stateHuntSyncEnd: State = new State {
      onEntry {
        highViolationOccurred := False
      }
      whenIsActive {
        when(edgeDetected) {
          when(isShort) {
            // measure half bit period on sync pulses
            // average in so jitter on any single edge doesn't drag the rate
            // round rather than truncate, or it creeps downwards every update
            measuredHalfBit := (measuredHalfBit +^ intervalTimer.value + 1) >> 1
          }
          when(isStop && readReg) {
            // 2 µs high then low
            highViolationOccurred := True
          } elsewhen(isStop && !readReg && highViolationOccurred) {
            // after high, 2 µs low
            // sync has ended, data is coming
            highViolationOccurred := False
            goto(stateData)
          } otherwise {
            // glitch, reset hunt
            highViolationOccurred := False
          }
        } elsewhen(highViolationOccurred &&
                   (intervalTimer.value >> 2) >= measuredHalfBit) {
          // sync end drives low for 4 half bits of the source, so data
          // starts here even though the first bit has no edge yet
          highViolationOccurred := False
          intervalTimer.clear()
          goto(stateData)
        }
      }
    }

    val stateData: State = new State {
      onEntry {
        bitCounter.clear()
        nextEdgeIsMidBit := True
        isDataLatched := False
      }
      whenIsActive {
        when(isStop) {
          when(isDataLatched && bitCounter === 0) {
            // whole bytes only, so this must be the last one
            io.data.valid := True
            io.data.last := True
          } otherwise {
            // stopped mid-byte, or sync ended with no data at all
            io.error := True
          }
          isDataLatched := False

          // packet is done (2 µs without edge)
          goto(stateStop)
        } elsewhen(edgeDetected) {
          val isValidBit = False

          when(isShort) {
            when(nextEdgeIsMidBit) {
              // this edge was in the middle of a bit, so a valid bit
              isValidBit := True
              // bit boundary is expected next
              nextEdgeIsMidBit := False
            } otherwise {
              // this edge was at a bit boundary, so not a valid bit
              // mid-bit edge is expected next
              nextEdgeIsMidBit := True
            }
          } elsewhen(isLong) {
            // this edge skipped past the bit boundary, so it must be a
            // mid-bit edge, therefore a valid bit
            isValidBit := True
            // bit boundary is expected next
            nextEdgeIsMidBit := False
          } elsewhen(isBounce) {
            // edge arrived early or is a bounce
            // do nothing
          } otherwise {
            // interval matches nothing valid, so the packet is corrupt.
            // Wait out the rest of it rather than re-hunting straight away,
            // which would read the trailing stop as another sync end.
            io.error := True
            goto(stateStop)
          }

          when(isValidBit) {
            // low to high = zero
            // high to low = one
            val bitValue = !readValue
            shiftRegNext := (shiftReg |<< 1) | bitValue.asBits.resized
            bitCounter.increment()

            when(bitCounter === 7) {
              when(isDataLatched) {
                io.data.valid := True
              }

              io.data.fragment := shiftRegNext
              isDataLatched := True
            }
          }
        }
      }
    }

    val stateStop: State = new State {
      onEntry {
        highViolationOccurred := False
      }
      whenIsActive {
        when(edgeDetected && isStop && readReg) {
          // 2 µs high then low
          highViolationOccurred := True
        } elsewhen(highViolationOccurred && isStop) {
          goto(stateIdle)
        }
      }
    }
  }
}

object AuxRxFilter {
  def on(rxSynced: Bool, filterWindow:TimeNumber = 50 ns): Bool = {
    val filter = AuxRxFilter(filterWindow)
    filter.io.rxSynced := rxSynced
    filter.io.rxFiltered
  }
}

case class AuxRxFilter(filterWindow: TimeNumber = 50 ns) extends Component {
  val io = new Bundle() {
    val rxSynced = in(Bool())
    val rxFiltered = out(Bool())
  }

  val clockFreq = ClockDomain.current.frequency.getValue
  val rawTaps = (filterWindow / clockFreq.toTime).toInt
  if (rawTaps >= 3) {
    val taps = if (rawTaps % 2 == 0) rawTaps - 1 else rawTaps
    val threshold = taps / 2
    val history = History(io.rxSynced, taps)

    val sumWidth = log2Up(taps + 1) bits
    val activeBits = history.map(_.asUInt(sumWidth)).reduce(_ + _)
    io.rxFiltered := activeBits > threshold

    SpinalInfo(f"AuxRxFilter: Generated $taps-tap majority vote filter for $clockFreq%s")
  } else {
    io.rxFiltered := io.rxSynced

    SpinalInfo(f"AuxRxFilter: $clockFreq%s is too slow for $filterWindow%s filter window")
  }
}
