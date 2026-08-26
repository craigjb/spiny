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

/** DisplayPort AUX channel transmitter IO
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class AuxTxIo() extends Bundle {
  /** Input data stream to transmit
   *
   *  @group ports
   */
  val data = slave(Stream(Fragment(Bits(8 bits))))

  /** Output AUX channel write wire
   *
   *  Connect to the write input of a differential IO buffer
   *  (or TriState.write)
   *  @group ports
   */
  val write = out(Bool())

  /** Output to disable tristate for writes to AUX channel
   *
   *  Connect to the write enable input of a differential IO buffer
   *  (or TriState.writeEnable)
   *  @group ports
   */
  val writeEnable = out(Bool())

  /** Output error pulse
   *
   *  Asserts for a single cycle on error and packet is dropped
   *  (can be used for retry logic). The input data Stream must be reset when
   *  this error pulse is asserted (e.g. clear the TX FIFO). AuxTx does not
   *  drain the aborted packet, and will start transmitting the remaining data
   *  as a new packet if not cleared.
   *  @group ports
   */
  val error = out(Bool())
}

/** DisplayPort AUX channel transmitter
 *  See [[AuxPhy]] for details
 *
 * @param dataRate Serial data rate (1 Mbps nominal, ~0.84-1.25 Mbps allowable).
 *                 Sets the transmitted bit period directly. The resulting unit
 *                 interval, which is half a bit period rounded up to a whole
 *                 number of clocks, must land in the 0.4-0.6 µs of Table 3-3 or
 *                 elaboration fails.
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class AuxTx(dataRate: HertzNumber = 1 MHz) extends Component {
  /** SpinalHDL IO ports
   *  @group ports */
  val io = AuxTxIo()

  io.write.setAsReg().init(False)
  io.writeEnable.setAsReg().init(False)
  io.data.ready := False
  io.error := False

  // longest is sync + pre-charge (16 + 16)
  val bitCounter = Counter(32)
  // which phase of the bit
  val firstHalf = RegInit(False)

  // ticks twice per bit
  val phaseTick = Pulse(dataRate * 2)
  val unitInterval = 
    ClockDomain.current.frequency.getValue.toTime * phaseTick.stateCount.toInt
  // Per Table 3-3, UI must be 0.4 - 0.6 µs
  assert(unitInterval >= 0.4.us && unitInterval <= 0.6.us,
    f"AuxTx unit interval ($unitInterval%s) is outside range of 0.4-0.6 µs")

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
              io.error := True
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
                  io.error := True
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
