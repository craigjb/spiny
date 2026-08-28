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

    // four PMOD debug signals, plus the byte bus
    val DBG_AUX_READ = out(Bool())
    val DBG_AUX_WRITE_EN = out(Bool())
    val DBG_BUSY = out(Bool())
    val DBG_DATA_CLK = out(Bool())
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

    val auxLink = AuxLinkSource(maxTimeout = 1 ms, retryLimit = 7)
    auxLink.io.phy <> auxPhy.io.data
    auxLink.io.replyTimeout := AuxLinkSource.timeoutCycles(300 us)
    auxLink.io.maxRetries := 7

    // native AUX read of 16 bytes from 0x00000
    val auxRequest = Seq(0x90, 0x00, 0x00, 0x0f)
    val requestBytes = Vec(auxRequest.map(b => B(b, 8 bits)))
    val txIndex = Reg(UInt(log2Up(auxRequest.length) bits)) init(0)
    val rxIndex = Reg(UInt(8 bits)) init(0)

    auxLink.io.request.valid := False
    auxLink.io.request.payload := requestBytes(txIndex)
    auxLink.io.start := False
    auxLink.io.reply.ready := False

    val dbgBytePeriod = 1 us
    val dbgByteCycles =
      (dbgBytePeriod.toBigDecimal *
        ClockDomain.current.frequency.getValue.toBigDecimal)
        .setScale(0, BigDecimal.RoundingMode.CEILING)
        .toInt
    val dbgCounter = Counter(dbgByteCycles)
    val dbgData = Reg(Bits(8 bits)) init (B"8'0")
    val dbgDataValid = Reg(Bool()) init (False)

    io.DBG_AUX_READ := auxIoBuf.O
    io.DBG_AUX_WRITE_EN := auxPhy.io.aux.writeEnable
    io.DBG_BUSY := auxLink.io.busy
    io.DBG_DATA := dbgData
    io.DBG_DATA_CLK := dbgDataValid && dbgCounter >= (dbgByteCycles / 2)

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

      // load the request into the replay buffer, a byte at a time
      val stateRequest: State = new State {
        onEntry {
          txIndex := 0
        }
        whenIsActive {
          auxLink.io.request.valid := True
          when(auxLink.io.request.fire) {
            when(txIndex === (auxRequest.length - 1)) {
              goto(stateStart)
            } otherwise {
              txIndex := txIndex + 1
            }
          }
        }
      }

      val stateStart: State = new State {
        whenIsActive {
          auxLink.io.start := True
          goto(stateWait)
        }
      }

      // AuxLinkSource handles the reply timeout and any retries
      val stateWait: State = new State {
        whenIsActive {
          when(auxLink.io.done) {
            goto(stateReply)
          }
        }
      }

      val stateReply: State = new State {
        onEntry {
          rxIndex := 0
          dbgCounter.clear()
          dbgDataValid := False
        }
        whenIsActive {
          dbgCounter.increment()
          // one byte per debug period, so the analyser can sample it
          auxLink.io.reply.ready := dbgCounter.willOverflow
          when(auxLink.io.reply.fire) {
            dbgData := auxLink.io.reply.payload
            dbgDataValid := True
            // second byte of the reply is the first byte of DPCD data
            when(rxIndex === 1) {
              leds := auxLink.io.reply.payload
            }
            rxIndex := rxIndex + 1
          }
          // the buffer never bubbles, so the first idle beat means drained
          when(dbgCounter.willOverflow && !auxLink.io.reply.valid) {
            goto(stateDone)
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
