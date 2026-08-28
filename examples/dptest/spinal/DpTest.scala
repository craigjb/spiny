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

package spiny.examples.dptest

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.blackbox.xilinx.s7._

import spiny.displayport._

class DpTest() extends Component {
  val io = new Bundle() {
    val SYS_CLK = in(Bool())
    val LEDS = out(Bits(8 bits))
    val HPD = in(Bool())
    val AUX_P = inout(Analog(Bool))
    val AUX_N = inout(Analog(Bool))
    val UNUSED_P = in(Bool())
    val UNUSED_N = in(Bool())

    val DBG_AUX_WRITE = out(Bool())
    val DBG_AUX_READ = out(Bool())
    val DBG_VALID = out(Bool())
    val DBG_DATA = out(Bits(8 bits))
  }

  noIoPrefix()

  val sysClkDomain = ClockDomain(
    clock = io.SYS_CLK,
    frequency = FixedFrequency(100 MHz),
    config = ClockDomainConfig(
      resetKind = BOOT
    )
  )

  sysClkDomain on {
    val auxPhy = AuxPhy()
    val auxIoBuf = IOBUFDS()
    auxIoBuf.I := auxPhy.io.aux.write
    auxIoBuf.T := !auxPhy.io.aux.writeEnable
    auxPhy.io.aux.read := auxIoBuf.O
    io.AUX_P := auxIoBuf.IO
    io.AUX_N := auxIoBuf.IOB

    // HPD comes from the sink, so synchronize it
    val hpd = BufferCC(io.HPD, init = False)

    val leds = Reg(Bits(8 bits)) init(B"8'0")
    io.LEDS := leds

    // native AUX read of 16 bytes from 0x00000
    val auxRequest = Seq(0x90, 0x00, 0x00, 0x0f)
    val requestBytes = Vec(auxRequest.map(b => B(b, 8 bits)))
    val txIndex = Reg(UInt(log2Up(auxRequest.length) bits)) init(0)
    val rxIndex = Reg(UInt(8 bits)) init(0)

    auxPhy.io.txData.valid := False
    auxPhy.io.txData.fragment := requestBytes(txIndex)
    auxPhy.io.txData.last := txIndex === (auxRequest.length - 1)

    io.DBG_AUX_READ := auxIoBuf.O
    io.DBG_AUX_WRITE := auxPhy.io.aux.write && auxPhy.io.aux.writeEnable
    io.DBG_DATA := auxPhy.io.rxData.fragment
    io.DBG_VALID := auxPhy.io.rxData.valid

    val settleDelay = Timeout(5 ms)

    val fsm = new StateMachine {
      always {
        // any HPD drop restarts the whole test
        when(!hpd) {
          leds := B"8'0"
          forceGoto(stateIdle)
        }
      }

      val stateIdle: State = new State with EntryPoint {
        whenIsActive {
          settleDelay.clear()
          when(hpd) {
            goto(stateSettle)
          }
        }
      }

      val stateSettle: State = new State {
        whenIsActive {
          when(settleDelay) {
            goto(stateRequest)
          }
        }
      }

      val stateRequest: State = new State {
        onEntry {
          txIndex := 0
        }
        whenIsActive {
          auxPhy.io.txData.valid := True
          when(auxPhy.io.txData.fire) {
            when(auxPhy.io.txData.last) {
              goto(stateReply)
            } otherwise {
              txIndex := txIndex + 1
            }
          }
        }
      }

      val stateReply: State = new State {
        onEntry {
          rxIndex := 0
        }
        whenIsActive {
          when(auxPhy.io.rxData.valid) {
            // second byte of the reply is the data byte
            when(rxIndex === 1) {
              leds := auxPhy.io.rxData.fragment
            }
            rxIndex := rxIndex + 1

            when(auxPhy.io.rxData.last) {
              goto(stateDone)
            }
          }
        }
      }

      val stateDone: State = new State {
        whenIsActive {}
      }
    }
  }
}

object TopLevelVerilog extends App {
  val spinalReport = SpinalConfig(
    targetDirectory = "target/spinal",
  ).generateVerilog(new DpTest())
}
