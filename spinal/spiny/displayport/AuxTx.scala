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

case class AuxTx(dataRate: HertzNumber = 1 MHz) extends Component {
  val io = new Bundle {
    val data = slave(Stream(Fragment(Bits(8 bits))))
    val write = out(Bool())
    val writeEnable = out(Bool())
  }
  io.write.setAsReg().init(False)
  io.writeEnable.setAsReg().init(False)
  io.data.ready := False

  // longest is sync + pre-charge (16 + 16)
  val bitCounter = Counter(32)
  // which phase of the bit
  val firstHalf = RegInit(False)

  // ticks twice per bit
  val phaseTick = Pulse(dataRate * 2)
  when(phaseTick) {
    firstHalf := !firstHalf
    when(!firstHalf) {
      bitCounter.increment()
    }
  }

  val shiftReg = Reg(Bits(8 bits))
  val isLastByte = Reg(Bool())

  val fsm = new StateMachine {
    val stateIdle: State = new State with EntryPoint {
      onEntry(io.writeEnable := False)
      onExit(io.writeEnable := True)
      whenIsActive {
        // wait for data to transmit
        when(io.data.valid) {
          goto(statePreChargeAndSync)
        }
      }
    }

    val statePreChargeAndSync: State = new State {
      // pre-charge consists of 16 zeros
      // sync consists of 16 more zeros
      onEntry {
        // reset phase
        firstHalf := True
        phaseTick.clear()

        // zero starts low
        io.write := False
        bitCounter.clear()
      }
      whenIsActive {
        when(phaseTick) {
          // zero goes high mid-bit
          io.write := firstHalf
          when(!firstHalf && bitCounter === 31) {
            goto(stateSyncEnd)
          }
        }
      }
    }

    val stateSyncEnd: State = new State {
      // sync end is high for two bits then low for two bits
      onEntry {
        // first high for two bits
        io.write := True
        bitCounter.clear()
      }
      whenIsActive {
        when(phaseTick) {
          when(!firstHalf && bitCounter === 1) {
            // then low for two bits
            io.write := False
          } elsewhen(!firstHalf && bitCounter === 3) {
            when(io.data.valid) {
              // valid data, so transmit
              goto(stateData)
            } otherwise {
              // not valid data = underrun, so abort
              goto(stateStop)
            }
          }
        }
      }
    }

    val stateData: State = new State {
      onEntry {
        // load first byte
        io.data.ready := True
        shiftReg := io.data.fragment
        isLastByte := io.data.last

        // drive first half of first bit
        io.write := io.data.fragment.msb

        bitCounter.clear()
      }
      whenIsActive {
        when(phaseTick) {
          when(firstHalf) {
            // second half of bit
            io.write := !shiftReg.msb
          } otherwise {
            // first half of next bit
            when(bitCounter === 7) {
              // end of the byte
              bitCounter.clear()

              when(isLastByte) {
                goto(stateStop)
              } otherwise {
                // fetch next byte
                when(io.data.valid) {
                  // valid data, so load it
                  io.data.ready := True
                  shiftReg := io.data.fragment
                  isLastByte := io.data.last

                  // drive first half of first bit
                  io.write := io.data.fragment.msb
                } otherwise {
                  // not valid data = underrun, so abort
                  goto(stateStop)
                }
              }
            } otherwise {
              // middle of the byte
              val shiftRegNext = shiftReg |<< 1
              shiftReg := shiftRegNext
              io.write := shiftRegNext.msb
            }
          }
        }
      }
    }

    val stateStop: State = new State {
      // stop is high for two bits then low for two bits
      onEntry {
        // first high for two bits
        io.write := True
        bitCounter.clear()
      }
      whenIsActive {
        when(phaseTick) {
          when(!firstHalf && bitCounter === 1) {
            // then low for two bits
            io.write := False
          } elsewhen(!firstHalf && bitCounter === 3) {
            goto(stateIdle)
          }
        }
      }
    }
  }
}
