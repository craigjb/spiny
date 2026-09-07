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
import spinal.lib.blackbox.xilinx.s7.BUFG
import spinal.lib.bus.regif._

import spiny.DiffPair
import spiny.platform.xilinx._
import spiny.platform.xilinx.blackbox._

object XilinxGtpPhyTx extends MainLinkPhyTxType {
  override def ports: HardType[MainLinkPhyPorts] = XilinxGtpPhyTxPorts()

  /** TXOUT_DIV, the same divider for RBR and HBR */
  val OutDivider = 2

  /** TXDIFFCTRL for DisplayPort voltage swing levels 0 to 3
   *
   *  These are the closest values to 400, 600, 800 and 1200 mVppd for the
   *  7 Series GTP (per UG482 Table 3-28). 1200 mVppd is beyond the range,
   *  so here the swing tops out at 1074 mVppd.
   */
  val DefaultSwingLevels = Seq(0x3, 0x6, 0x9, 0xf)

  /** TXPOSTCURSOR for DisplayPort pre-emphasis levels 0 to 3 */
  val DefaultPreEmphasisLevels = Seq(0x00, 0x08, 0x0e, 0x14)

  /** Training pattern 1 is D10.2, which 8b/10b encoding turns into
   *  a square wave that the sink clock locks onto
   */
  val D10_2 = 0x4a

  /** Dividers that reach a line rate from a reference clock
   *
   *  @param refClkFreq Reference clock frequency
   *  @param lineRate Serial rate the VCO is solved for
   */
  def pllConfig(refClkFreq: HertzNumber, lineRate: HertzNumber): Gtpe2PllConfig = {
    // line rate is VCO x 2 / TXOUT_DIV
    val vco = HertzNumber(lineRate.toBigDecimal * OutDivider / 2)
    Gtpe2PllConfig
      .solve(refClkFreq, vco)
      .getOrElse(SpinalError(s"no GTP PLL divider set reaches $vco from $refClkFreq"))
  }

  /** Builds a transmitter on a PLL and a transmit half it claims itself
   *
   *  A GTP transmitter needs both, and the transmit half has to be claimed
   *  with the config this transmitter runs at, so it claims them here.
   *
   *  @param common The [[GtpCommon]] to claim a PLL from
   *  @param channel The [[GtpChannel]] to claim the transmit half of
   *  @param serial Where the lane goes, e.g. `DiffPair.driving(pinP, pinN)`
   *  @param refClkFreq Reference clock frequency (static)
   *  @param initialLineRate Serial rate the PLL is initialized for
   *  @param refClkSelect Which of reference clocks the PLL should use
   *  @param swingLevels TXDIFFCTRL per DisplayPort voltage swing level
   *  @param preEmphasisLevels TXPOSTCURSOR per pre-emphasis level
   */
  def apply(
    common: GtpCommon,
    channel: GtpChannel,
    serial: DiffPair,
    refClkFreq: HertzNumber = 135 MHz,
    initialLineRate: HertzNumber = 2.7 GHz,
    refClkSelect: Gtpe2PllRefClk = Gtpe2PllRefClk.GtRefClk0,
    swingLevels: Seq[Int] = DefaultSwingLevels,
    preEmphasisLevels: Seq[Int] = DefaultPreEmphasisLevels
  ): XilinxGtpPhyTx = {
    val claim = common.requestPll(pllConfig(refClkFreq, initialLineRate))
    val phy = XilinxGtpPhyTx(
      claim.index,
      refClkFreq,
      initialLineRate,
      refClkSelect,
      swingLevels,
      preEmphasisLevels
    )
    phy.io.pll <> claim
    phy.io.tx <> channel.requestTx(phy.txConfig)
    serial := phy.io.serial
    phy
  }
}

/** Xilinx GTP PHY specific ports on the main link control bus */
case class XilinxGtpPhyTxPorts() extends MainLinkPhyPorts {
  /** Holds the PLL in reset */
  val pllReset = Bool()

  /** Holds the transmitter in reset, which also clears outClkDetected */
  val gtTxReset = Bool()

  /** Tells the transmitter TXUSRCLK is running, see the sequence in UG482 */
  val usrClkReady = Bool()

  /** The PLL has locked to its reference
   *  @group ports
   */
  val pllLocked = Bool()

  /** The PLL's reference clock has stopped
   *  @group ports
   */
  val refClkLost = Bool()

  /** The PLL's feedback clock has stopped
   *  @group ports
   */
  val fbClkLost = Bool()

  /** TXRESETDONE, the transmitter has finished its reset
   *  @group ports
   */
  val resetDone = Bool()

  /** TXOUTCLK has been seen running since the last transmitter reset
   *  @group ports
   */
  val outClkDetected = Bool()

  /** TXBUFSTATUS bit 0, latched
   *  @group ports
   */
  val bufferHalfFull = Bool()

  /** TXBUFSTATUS bit 1, latched, meaning the buffer over or underflowed
   *  @group ports
   */
  val bufferError = Bool()

  /** A pattern other than quiet is going out
   *  @group ports
   */
  val transmitting = Bool()

  override def asMaster(): Unit = {
    out(pllReset, gtTxReset, usrClkReady)
    in(pllLocked, refClkLost, fbClkLost, resetDone)
    in(outClkDetected, bufferHalfFull, bufferError, transmitting)
  }

  override def driveFrom(busIf: BusIf, lane: Int): Unit = {
    val control = busIf
      .newReg(doc = s"Lane $lane GTP transmitter reset sequence")
      .setName(s"lane${lane}GtpControl")
    pllReset := control.field(
      Bool(),
      AccessType.RW,
      resetValue = 1,
      doc = "Holds the PLL in reset"
    )(SymbolName("pllReset"))
    gtTxReset := control.field(
      Bool(),
      AccessType.RW,
      resetValue = 1,
      doc = "Holds the transmitter in reset"
    )(SymbolName("gtTxReset"))
    usrClkReady := control.field(
      Bool(),
      AccessType.RW,
      resetValue = 0,
      doc = "Release once TXOUTCLK is running, the last step of the sequence"
    )(SymbolName("usrClkReady"))

    val status = busIf
      .newReg(doc = s"Lane $lane GTP transmitter status")
      .setName(s"lane${lane}GtpStatus")
    def statusBitRO(signal: Bool, name: String, doc: String): Unit =
      status.field(Bool(), AccessType.RO, doc = doc)(SymbolName(name)) := signal

    statusBitRO(pllLocked, "pllLocked", "The PLL has locked to its reference")
    statusBitRO(refClkLost, "refClkLost", "The PLL's reference clock has stopped")
    statusBitRO(fbClkLost, "fbClkLost", "The PLL's feedback clock has stopped")
    statusBitRO(resetDone, "resetDone", "TXRESETDONE, the transmitter reset finished")
    statusBitRO(outClkDetected, "outClkDetected",
      "TXOUTCLK has been seen running since the last transmitter reset")
    statusBitRO(bufferHalfFull, "bufferHalfFull", "TXBUFSTATUS bit 0, latched")
    statusBitRO(bufferError, "bufferError",
      "TXBUFSTATUS bit 1, latched, the buffer over or underflowed")
    statusBitRO(transmitting, "transmitting", "A pattern other than quiet is going out")
  }
}

/** DisplayPort main link transmitter on a Xilinx 7 series GTP
 *
 *  Claims a PLL from a [[GtpCommon]] and the transmit half of a
 *  [[GtpChannel]]. The reset pins and the transceiver's condition are
 *  exposed as registers, and firmware walks the sequence of UG482.
 *
 *  @param pllIndex Which of the [[GtpCommon]]'s PLLs feeds this lane
 *  @param refClkFreq Reference clock frequency (static)
 *  @param initialLineRate Serial rate the PLL is initialized for
 *  @param refClkSelect Which of the reference clocks the PLL should use
 *  @param swingLevels TXDIFFCTRL per DisplayPort voltage swing level
 *  @param preEmphasisLevels TXPOSTCURSOR per pre-emphasis level
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class XilinxGtpPhyTx(
  pllIndex: Int,
  refClkFreq: HertzNumber,
  initialLineRate: HertzNumber,
  refClkSelect: Gtpe2PllRefClk,
  swingLevels: Seq[Int],
  preEmphasisLevels: Seq[Int]
) extends Component with MainLinkPhyTx {
  assert(swingLevels.size == 4, "swingLevels needs one entry per DPCD level")
  assert(preEmphasisLevels.size == 4,
    "preEmphasisLevels needs one entry per DPCD level")

  private val dataWidth = 20
  private val outDivider = XilinxGtpPhyTx.OutDivider

  val txConfig = Gtpe2TxConfig(
    refClkFreq = refClkFreq,
    dataWidth = dataWidth,
    outDivider = outDivider
  )

  val io = new Bundle {
    /** Link layer side of the PHY
     *  @group ports
     */
    val control =
      slave(MainLinkPhyTxControl(laneCount = 1, XilinxGtpPhyTxPorts()))

    /** The PLL claimed from a [[GtpCommon]]
     *  @group ports
     */
    val pll = slave(GtpPllIo(pllIndex))

    /** The transmit half claimed from a [[GtpChannel]]
     *  @group ports
     */
    val tx = Gtpe2TxIo(txConfig).flip()

    /** The lane's differential pair
     *  @group ports
     */
    val serial = out(DiffPair())
  }

  override def control: MainLinkPhyTxControl = io.control

  val pllConfig = XilinxGtpPhyTx.pllConfig(refClkFreq, initialLineRate)

  val pmaClk = Bool()
  val fabricClk = BUFG.on(pmaClk)
  val txReady = Bool()

  val fabricClkDomain = ClockDomain(
    clock = fabricClk,
    reset = ClockDomain(
      clock = fabricClk,
      reset = !txReady,
      config = ClockDomainConfig(resetKind = ASYNC)
    )(BufferCC(False, init = True)),
    config = ClockDomainConfig(resetKind = ASYNC),
    frequency = FixedFrequency(
      HertzNumber(initialLineRate.toBigDecimal / dataWidth)
    )
  )

  // TXOUTCLK only runs once the PMA is out of reset, and fabricClkDomain is
  // held in reset until bring-up finishes, so this toggle gets a domain of
  // its own to tell the startup sequence the clock has started.
  val fabricAlive = ClockDomain(
    clock = fabricClk,
    config = ClockDomainConfig(resetKind = BOOT)
  ) on new Area {
    val toggle = RegInit(False)
    toggle := !toggle
  }


  pmaClk := io.tx.fabricClockOutput.txOutClkPma()
  io.serial := io.tx.driver.output

  private val phy = io.control.phy.asInstanceOf[XilinxGtpPhyTxPorts]

  /** Status bits cross-clock buffered for register access */
  val status = new Area {
    val pllLocked = BufferCC(io.pll.lock, False)
    val resetDone = BufferCC(io.tx.resetDone, False)
    val refClkLost = BufferCC(io.pll.refClkLost, False)
    val fbClkLost = BufferCC(io.pll.fbClkLost, False)

    // TXOUTCLK cannot be polled from software, so its liveness is latched
    // here and cleared whenever the transmitter is put back into reset
    val outClkAlive = BufferCC(fabricAlive.toggle, False).edge()
    val outClkDetected = RegInit(False) setWhen (outClkAlive) clearWhen (phy.gtTxReset)

    // TXBUFSTATUS is in the transmit domain, so each bit crosses on its own
    // and is latched, since a flag that clears itself still means trouble
    val bufferHalfFull = RegInit(False) setWhen (
      BufferCC(io.tx.buffer.status(0), False))
    val bufferError = RegInit(False) setWhen (
      BufferCC(io.tx.buffer.status(1), False))
  }

  // The transmit datapath is usable once its reset is done
  txReady := status.resetDone
  io.control.ready := status.resetDone

  // enable is the link layer's master switch
  io.pll.reset := phy.pllReset || !io.control.enable
  io.pll.powerDown := False

  // UG482 wants a stable free running clock on the lock detector
  io.pll.lockDetectClk := ClockDomain.current.readClockWire

  io.pll.refClkSelect := refClkSelect.asBits

  io.tx.powerDown := B"2'00"
  io.tx.reset := phy.gtTxReset || !io.control.enable

  io.tx.clocking.staticSysClk(pmaClkPll = pllIndex, txOutClkPll = pllIndex)
  // at 20 bits TXUSRCLK2 is the same clock as TXUSRCLK
  io.tx.clocking.connectClocks(fabricClkDomain)
  io.tx.clocking.usrReady := phy.usrClkReady && io.control.enable

  // the driver settings are asynchronous inputs to the PMA, so they cross
  // from the link layer without a clock
  val drive = io.control.drive(0)
  io.tx.driver.driverSwing := Vec(swingLevels.map(B(_, 4 bits)))(drive.swing)
  io.tx.driver.postCursor :=
    Vec(preEmphasisLevels.map(B(_, 5 bits)))(drive.preEmphasis)
  io.tx.driver.preCursor := B"5'0"
  io.tx.driver.inhibit := False
  io.tx.driver.deEmphasis := False
  // UG482's recommended pre-driver swing
  io.tx.driver.preDriverSwing := B"3'100"
  // TX_MAINCURSOR_SEL leaves the main cursor to TXDIFFCTRL, and TXMARGIN is
  // only read in the PIPE drive mode that DisplayPort does not use
  io.tx.driver.mainCursor := B"7'0"
  io.tx.driver.margin := B"3'0"
  io.tx.driver.preCursorInvert := False
  io.tx.driver.postCursorInvert := False

  val pattern = fabricClkDomain on new Area {
    val select = BufferCC(io.control.pattern, MainLinkPattern.Quiet())
    val symbol = RegInit(B(0, 8 bits))
    val quiet = RegInit(True)

    /** The select as the transmit domain sees it */
    val transmitting = select =/= MainLinkPattern.Quiet

    switch(select) {
      is(MainLinkPattern.TrainingPattern1) {
        symbol := XilinxGtpPhyTx.D10_2
        quiet := False
      }
      default {
        symbol := 0
        quiet := True
      }
    }
  }

  // Training pattern 1 is D10.2 repeated
  io.tx.data().foreach(_ := pattern.symbol)

  // DisplayPort uses 8b/10b encoding
  io.tx.encoder8b10b.enable := True
  io.tx.encoder8b10b.bypass := B"4'0"

  // training patterns carry no K characters
  io.tx.encoder8b10b.charIsK := B"4'0"

  // use electrical idle when quiet
  io.tx.driver.electricalIdle := pattern.quiet

  // feedback on whether the transmitter is actually sending something
  val patternActive = BufferCC(pattern.transmitting, False)

  phy.pllLocked := status.pllLocked
  phy.refClkLost := status.refClkLost
  phy.fbClkLost := status.fbClkLost
  phy.resetDone := status.resetDone
  phy.outClkDetected := status.outClkDetected
  phy.bufferHalfFull := status.bufferHalfFull
  phy.bufferError := status.bufferError
  phy.transmitting := patternActive

  // GTP TX ports unused by this PHY
  io.tx.pmaReset := False
  io.tx.pcsReset := False
  io.tx.gearbox.disable()
  io.tx.bufferBypass.disable()
  io.tx.patternGenerator.disable()
  io.tx.polarity.disable()
  io.tx.phaseInterpolator.disable()
  io.tx.pcie.disable()
  io.tx.outOfBand.disable()
  io.tx.fabricClockOutput.rate.disable()
}
