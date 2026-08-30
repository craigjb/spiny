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
import spinal.lib.bus.amba3.apb._
import spinal.lib.bus.regif._
import spinal.lib.io.TriState

import spiny.peripheral._

/** AUX channel settings for a [[SpinyDisplayPortSource]]
 *
 *  @param maxTimeout Longest reply timeout the timeout register can hold
 *  @param defaultTimeout Reset value of the reply timeout register
 *  @param retryLimit Largest value the retry register can hold
 *  @param requestDepth Request bytes buffered
 *  @param replyDepth Reply bytes buffered
 */
case class SourceAuxConfig(
  maxTimeout: TimeNumber = 1 ms,
  defaultTimeout: TimeNumber = 300 us,
  retryLimit: Int = 7,
  requestDepth: Int = 20,
  replyDepth: Int = 17
)

/** DisplayPort source peripheral, covering the AUX channel and HPD
 *
 *  @param auxConfig AUX channel settings
 *  @param hpdFilter How long HPD must be stable to count as a change
 *  @param addressWidth Address width for the APB3 bus
 */
class SpinyDisplayPortSource(
  auxConfig: SourceAuxConfig = SourceAuxConfig(),
  hpdFilter: TimeNumber = 100 us,
  addressWidth: Int = 8
) extends Component with SpinyPeripheral {
  // an IRQ_HPD pulse is 0.5 ms at its shortest, so a filter anywhere near
  // that would swallow the event the sink is trying to signal
  assert(hpdFilter < (0.5 ms),
    "hpdFilter must be shorter than the 0.5 ms minimum IRQ_HPD pulse")

  val apb3Config = Apb3Config(
    addressWidth = addressWidth,
    dataWidth = 32
  )

  val io = new Bundle {
    val apb = slave(Apb3(apb3Config))
    val aux = master(TriState(Bool()))
    val hpd = in Bool ()
    val interrupt = out Bool ()
  }

  val busIf = createPeripheralBusInterface(io.apb)

  val hpd = new Area {
    val filterCycles =
      (hpdFilter.toBigDecimal *
        ClockDomain.current.frequency.getValue.toBigDecimal)
        .setScale(0, BigDecimal.RoundingMode.CEILING)
        .toInt

    // HPD is driven by the sink, so synchronize before filtering it
    val sync = BufferCC(io.hpd, init = False)
    val counter = Reg(UInt(log2Up(filterCycles + 1) bits)) init (0)
    val level = RegInit(False)
    when(sync === level) {
      counter := 0
    } otherwise {
      counter := counter + 1
      when(counter === (filterCycles - 1)) {
        level := sync
        counter := 0
      }
    }

    // firmware tells a short IRQ_HPD from an unplug by which edges it sees
    // and how far apart they are, so both are reported
    val rise = level.rise(False)
    val fall = level.fall(False)

    val status = busIf.newReg(doc = "HPD status").setName("hpdStatus")
    val connected = status.field(
      Bool(),
      AccessType.RO,
      doc = "A sink is connected and asserting HPD"
    )(SymbolName("connected"))
    connected := level

    private val interruptBase = busIf.getRegPtr()
    val interruptRaw = interruptBase
    val interruptForce = interruptBase + busIf.wordAddressInc
    val interruptMask = interruptBase + 2 * busIf.wordAddressInc
    val interruptStatus = interruptBase + 3 * busIf.wordAddressInc

    val interrupt = busIf.interruptFactory("hpd", rise, fall)
  }

  val phy = AuxPhy()
  val link = AuxLinkSource(
    maxTimeout = auxConfig.maxTimeout,
    retryLimit = auxConfig.retryLimit,
    requestDepth = auxConfig.requestDepth,
    replyDepth = auxConfig.replyDepth
  )
  link.io.phy <> phy.io.data
  io.aux <> phy.io.aux

  val auxRegs = link.driveFrom(busIf, auxConfig.defaultTimeout)

  io.interrupt := auxRegs.interrupt || hpd.interrupt

  override def interrupt: Option[Bool] = Some(io.interrupt)

  checkPeripheralMapping()
}
