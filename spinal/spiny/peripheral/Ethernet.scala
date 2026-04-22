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

package spiny.peripheral

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb._
import spinal.lib.bus.regif._
import spinal.lib.com.eth._

import spiny.blackbox.xilinx.ODDR

/** 100BASE-X Ethernet peripheral
 *
 *  Currently only supports 100 Mbit (fast ethernet).
 *  Does not support 10BASE-X or 1000BASE-X (Gbit) yet.
 *  Currently only supports RMII.
 *
 *  Buffers default to hold two MTU packets + headers
 */
class SpinyEthernet(
  rxCd: ClockDomain,
  txCd: ClockDomain,
  rxBufferSize: Int = 4096,
  txBufferSize: Int = 4096,
  addressWidth: Int = 8,
) extends Component with SpinyPeripheral {
  assert(rxCd.frequency.getValue == (50 MHz), "RMII requires 50 MHz clock")
  assert(txCd.frequency.getValue == (50 MHz), "RMII requires 50 MHz clock")

  val apb3Config = Apb3Config(
    addressWidth = addressWidth,
    dataWidth = 32
  )

  val macParam = MacEthParameter(
    phy = PhyParameter(
      txDataWidth = 2,
      rxDataWidth = 2
    ),
    rxDataWidth = 32,
    txDataWidth = 32,
    rxBufferByteSize = rxBufferSize,
    txBufferByteSize = txBufferSize
  )

  val io = new Bundle {
    val apb = slave(Apb3(apb3Config))
    val rmii = master(Rmii(RmiiParameter(
      rx = RmiiRxParameter(
        dataWidth = macParam.phy.rxDataWidth,
        withEr = false
      ),
      tx = RmiiTxParameter(
        dataWidth = macParam.phy.txDataWidth
      )
    )))
    val phyReset = out(Bool())
  }

  val mac = MacEth(
    p = macParam,
    rxCd = rxCd,
    txCd = txCd
  )

  rxCd on {
    val rmiiRxFlow = io.rmii.RX.toRxFlow()
    mac.io.phy.rx << rmiiRxFlow.toStream
  }

  txCd on {
    val rmiiTxStream = io.rmii.TX.fromTxStream()
    mac.io.phy.tx >> rmiiTxStream
  }

  mac.io.phy.colision := False
  mac.io.phy.busy := False

  val busIf = createPeripheralBusInterface(io.apb)

  val ctrl = busIf.newReg("Control")
  val phyReset = ctrl.field(
    Bool(),
    AccessType.RW,
    resetValue = 1,
    doc = "Reset PHY"
  )
  val rxReset = ctrl.field(
    Bool(),
    AccessType.RW,
    resetValue = 1,
    doc = "Reset RX buffer"
  )
  val txReset = ctrl.field(
    Bool(),
    AccessType.RW,
    resetValue = 1,
    doc = "Reset TX buffer"
  )
  val rxAlign = ctrl.field(
    Bool(),
    AccessType.RW,
    resetValue = 0,
    doc = "Enable RX aligner (2 bytes of padding in front of data)"
  )
  val txAlign = ctrl.field(
    Bool(),
    AccessType.RW,
    resetValue = 0,
    doc = "Enable TX aligner (2 bytes of padding in front of data)"
  )
  val clearStats = ctrl.field(
    Bool(),
    AccessType.W1P,
    doc = "Clear RX statistics"
  )

  io.phyReset := phyReset
  mac.io.ctrl.rx.flush := rxReset
  mac.io.ctrl.tx.flush := txReset
  mac.io.ctrl.rx.alignerEnable := rxAlign
  mac.io.ctrl.tx.alignerEnable := txAlign
  mac.io.ctrl.rx.stats.clear := clearStats

  val status = busIf.newReg("Status")
  val txAvail = status.field(
    UInt(macParam.txAvailabilityWidth bits),
    AccessType.RO,
    doc = "TX buffer availability (words)"
  )
  val rxValid = status.field(
    Bool(),
    AccessType.RO,
    doc = "Valid data available in RX Fifo"
  )
  val rxDrops = status.field(
    UInt(8 bits),
    AccessType.RO,
    doc = "RX drop count"
  )
  val rxErrors = status.field(
    UInt(8 bits),
    AccessType.RO,
    doc = "RX error count"
  )

  txAvail := mac.io.ctrl.tx.availability
  rxValid := mac.io.ctrl.rx.stream.valid
  rxDrops := mac.io.ctrl.rx.stats.drops
  rxErrors := mac.io.ctrl.rx.stats.errors

  val txFifo = busIf.newWrFifo(doc = "Write data to transmit")
  txFifo.field(32, doc = "Write data")("data")
  txFifo.bus.toStream >> mac.io.ctrl.tx.stream

  val rxFifo = busIf.newRdFifo(doc = "Read received data")
  rxFifo.field(32, doc = "Read data")("data")
  mac.io.ctrl.rx.stream >> rxFifo.bus

  checkPeripheralMapping()

  /** RMII clock output using ODDR primitive
   *
   *  Xilinx only
   */
  def rmiiClkOutXilinxOddr(oddrCd: ClockDomain = txCd): Bool = {
    val oddr = ODDR()
    oddr.C := oddrCd.readClockWire
    oddr.CE := True
    oddr.D1 := True
    oddr.D2 := False
    oddr.R := False
    oddr.S := False

    oddr.Q
  }
}
