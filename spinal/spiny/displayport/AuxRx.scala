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
  tolerance: BigDecimal = 0.15
) extends Component {
  val io = new Bundle {
    val read = in(Bool())
    val readEnable = in(Bool())
    val data = master(Flow(Fragment(Bits(8 bits))))
  }

  val clocksPerHalfBit = (ClockDomain.current.frequency.getValue / dataRate / 2)
    .setScale(0, BigDecimal.RoundingMode.CEILING)
    .toInt
  val clocksPerBit = clocksPerHalfBit * 2
  val clocksPerTol = (tolerance * clocksPerBit)
    .setScale(0, BigDecimal.RoundingMode.CEILING)
    .toInt

  // synchronize external input
  val readSynced = BufferCC(io.read, init = False)

  // find all edges
  val readReg = RegNext(readSynced) init(False)
  val edgeDetected = readSynced =/= readReg

  val intervalTimer = Counter(clocksPerBit * 3)
  when(!io.readEnable || edgeDetected) {
    intervalTimer.clear()
  } elsewhen(!intervalTimer.willOverflowIfInc) {
    intervalTimer.increment()
  }

  // define intervals between edges
  val isShort = intervalTimer > (clocksPerHalfBit - clocksPerTol) &&
                intervalTimer < (clocksPerHalfBit + clocksPerTol)
  val isLong = intervalTimer > (clocksPerBit - clocksPerTol) &&
                intervalTimer < (clocksPerBit + clocksPerTol)
  val isStop = intervalTimer > (clocksPerHalfBit * 3)
  val isTwoBits = intervalTimer >= (clocksPerBit * 2)

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

  val fsm = new StateMachine {
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
        } elsewhen(isTwoBits && highViolationOccurred) {
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
          when(isDataLatched) {
            // this must be the last byte
            io.data.valid := True
            io.data.last := True
            isDataLatched := False
          }

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
          } otherwise {
            // glitch, abort
            goto(stateIdle)
          }

          when(isValidBit) {
            // low to high = zero
            // high to low = one
            val bitValue = !readSynced
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
