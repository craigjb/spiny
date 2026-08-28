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
import spinal.lib.io._

/** Packet data IO ports of AuxPhy
 *
 *  Connects to link layer components
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class AuxPhyDataIo() extends Bundle with IMasterSlave {
  /** Data stream to transmit
   *
   *  Fragment.last indicates the end of a packet. Transmission starts upon
   *  first valid byte and is aborted if this stream underruns (indicated by
   *  txError).
   *  @group ports
   */
  val txData = Stream(Fragment(Bits(8 bits)))

  /** Transmitter error pulse
   *
   *  Asserts for a single cycle on error and packet is dropped
   *  (can be used for retry logic). The txData Stream must be reset when
   *  this error pulse is asserted (e.g. clear the TX FIFO). AuxTx does not
   *  drain the aborted packet, and will start transmitting the remaining data
   *  as a new packet if not cleared.
   *  @group ports
   */
  val txError = Bool()

  /** Received data
   *
   *  Must be read when valid or data can be dropped. Fragment.last indicates
   *  end of packet. On error, packets are aborted (indicated by rxError) and
   *  Fragment.last may not be asserted.
   *  @group ports
   */
  val rxData = Flow(Fragment(Bits(8 bits)))

  /** Receiver error pulse
   *
   *  Asserts for a single cycle on RX error when packet is dropped
   *  (can be used for retry logic). Previously read data must be discarded.
   *  @group ports
   */
  val rxError = Bool()

  override def asMaster(): Unit = {
    master(txData)
    slave(rxData)
    in(txError, rxError)
  }
}

/** DisplayPort AUX channel PHY IO ports
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class AuxPhyIo() extends Bundle {
  /** TriState-able AUX channel
   *
   *  Connect this to differential IO buffers
   *  @group ports
   */
  val aux = master(TriState(Bool()))

  /** Packet data plane, driven by the layer above
   *  @group ports
   */
  val data = slave(AuxPhyDataIo())
}

/** DisplayPort AUX channel PHY
 *
 * AuxPhy implements half-duplex, bidirectional serial communication using
 * Manchester encoding at 1 Mbps per the DisplayPort 1.1 spec. This component
 * is agnostic of the device-specific IO. Users must instantiate appropriate
 * differential IO buffers for the AUX ports.
 *
 * The transmitter will assert txError when a packet is aborted due to data
 * underrun. In this case, the input txData Stream must be reset (e.g. clear
 * the upstream write FIFO), otherwise AuxPhy will start transmitting the 
 * remaining data as a new packet.
 *
 * The receiver uses interval decoding and measures the source bit period
 * during the preamble and sync portion of a packet. If the clock domain is
 * fast enough (> 60 MHz), a majority vote filter also removes spurious edges
 * from crosstalk or EMI. Since the receiver uses oversampling, the clock must
 * supply at least [[AuxRx.MinOversampling]] cycles per bit period, which is
 * 32 MHz at the nominal 1 Mbps.
 *
 * @param dataRate Serial data rate (1 Mbps nominal, ~0.84-1.25 Mbps allowable).
 *                 Table 3-3 allows a 0.4-0.6 µs unit interval, which is half a
 *                 bit period, so the allowable rates are the reciprocals of a
 *                 0.8-1.2 µs bit period. The half bit tick count is rounded up
 *                 to the clock, so the exact limits shift a little with clock
 *                 frequency and elaboration asserts on the quantized value.
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class AuxPhy(
  dataRate: HertzNumber = 1 MHz
) extends Component {
  /** SpinalHDL IO ports
   *  @group ports */
  val io = AuxPhyIo()

  val tx = AuxTx(dataRate = dataRate)
  io.data.txData >> tx.io.data
  io.aux.write := tx.io.write
  io.aux.writeEnable := tx.io.writeEnable
  io.data.txError := tx.io.error

  val rx = AuxRx(dataRate = dataRate)
  rx.io.read := io.aux.read
  rx.io.readEnable := !tx.io.writeEnable
  rx.io.data >> io.data.rxData
  io.data.rxError := rx.io.error
}

