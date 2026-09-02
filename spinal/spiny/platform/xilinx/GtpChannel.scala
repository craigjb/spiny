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

package spiny.platform.xilinx

import spinal.core._
import spinal.lib._

import spiny.platform.xilinx.blackbox._

/** Allocates TX and RX of a GTP transceiver to consumers
 *
 *  The two halves are independent, so one block can drive the transmitter
 *  while another owns the receiver. Each half is claimed with requestTx or
 *  requestRx and wired to the returned port. Unclaimed TX or RX blocks are
 *  powered down. The primitive is instantiated once the enclosing component
 *  has elaborated, so build() only has to be called by hand if this is the
 *  toplevel.
 *
 * @param drpClkDomain Clock domain for the dynamic reconfiguration port
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class GtpChannel(
  drpClkDomain: ClockDomain = null
) extends Component {
  val io = new Bundle {
    /** Both PLL outputs from a [[GtpCommon]], either of which a half can use
     *  @group ports
     */
    val clocking = in(Gtpe2ChannelClocking())
  }

  private var txClaim: Option[(Gtpe2TxConfig, Gtpe2TxIo)] = None
  private var rxClaim: Option[(Gtpe2RxConfig, Gtpe2RxIo)] = None
  private var built = false

  // The claims are only all known once the enclosing component has
  // finished elaborating, so the build waits for the parent.
  if (parent != null) parent.addPrePopTask(() => if (!built) build())

  /** Claims the transmit half */
  def requestTx(config: Gtpe2TxConfig): Gtpe2TxIo = {
    assert(!built, GtpChannel.lateClaim)
    assert(txClaim.isEmpty, "The transmit half is already claimed")

    val port = rework { Gtpe2TxIo(config).setName("tx") }
    txClaim = Some((config, port))
    port
  }

  /** Claims the receive half */
  def requestRx(config: Gtpe2RxConfig): Gtpe2RxIo = {
    assert(!built, GtpChannel.lateClaim)
    assert(rxClaim.isEmpty, "The receive half is already claimed")

    val port = rework { Gtpe2RxIo(config).setName("rx") }
    rxClaim = Some((config, port))
    port
  }

  /** Instantiates the primitive with whatever was claimed
   *
   *  Runs automatically at the end of the enclosing component.
   */
  def build(): Unit = rework {
    assert(!built, "build() already called")
    assert(txClaim.nonEmpty || rxClaim.nonEmpty,
      "A GtpChannel was created but neither half was claimed")
    built = true

    println(
      s"[GtpChannel] tx=${describe(txClaim.map(_._1.dataWidth), txClaim.map(_._1.outDivider))} " +
        s"rx=${describe(rxClaim.map(_._1.dataWidth), rxClaim.map(_._1.outDivider))}"
    )

    // A powered down half still needs a legal CLK25_DIV, so it borrows the
    // claimed half's reference clock. One of the two is always claimed.
    val refClkFreq =
      txClaim.map(_._1.refClkFreq).orElse(rxClaim.map(_._1.refClkFreq)).get

    val channel = Gtpe2Channel(
      rxConfig = rxClaim.map(_._1).getOrElse(Gtpe2RxConfig(refClkFreq)),
      txConfig = txClaim.map(_._1).getOrElse(Gtpe2TxConfig(refClkFreq)),
      drpClkDomain = drpClkDomain
    )

    channel.io.clocking := io.clocking
    // sequential reset mode, the one the reset sequence in UG482 describes
    channel.io.resetSelection := False
    // Nothing needs the DRP yet: rate switching rides TXRATE/RXRATE and the
    // sysclk select, all ports. Only a runtime RXCDR_CFG would need it.
    channel.io.drp.disable()
    channel.io.loopback.disable()
    channel.io.digitalMonitor.disable()

    txClaim match {
      case Some((_, port)) => channel.io.tx <> port
      case None            => channel.io.tx.disable()
    }
    rxClaim match {
      case Some((_, port)) => channel.io.rx <> port
      case None            => channel.io.rx.disable()
    }
  }

  private def describe(width: Option[Int], divider: Option[Int]): String =
    (width, divider) match {
      case (Some(w), Some(d)) => s"${w}b/div$d"
      case _                  => "unclaimed"
    }
}

object GtpChannel {
  private val lateClaim =
    "Cannot claim a half after the GTPE2_CHANNEL is built. The build runs " +
      "when the enclosing component finishes elaborating, so a claim from " +
      "a prePopTask or afterElaboration block is too late."
}
