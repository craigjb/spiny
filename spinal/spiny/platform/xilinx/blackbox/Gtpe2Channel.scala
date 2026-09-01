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

package spiny.platform.xilinx.blackbox

import spinal.core._
import spinal.lib._

import spiny.DiffPair

case class Gtpe2ChannelClocking() extends Bundle {
  val pll0Clk = Bool()
  val pll0RefClk = Bool()
  val pll1Clk = Bool()
  val pll1RefClk = Bool()

  def fromGtpe2Common(common: Gtpe2Common) = {
    pll0Clk := common.io.pll0.outClk
    pll0RefClk := common.io.pll0.outRefClk
    pll1Clk := common.io.pll1.outClk
    pll1RefClk := common.io.pll1.outRefClk
  }
}


/** Receive side settings for a [[Gtpe2Channel]]
 *
 *  @param usrClkDomain Drives RXUSRCLK
 *  @param usrClk2Domain Drives RXUSRCLK2, equal to usrClkDomain at 20 bits
 *  @param dataWidth PCS to PMA width, 16, 20, 32 or 40
 *  @param outDivider Serial clock divider, 1, 2, 4 or 8
 */
case class Gtpe2RxConfig(
  usrClkDomain: ClockDomain = null,
  usrClk2Domain: ClockDomain = null,
  dataWidth: Int = 20,
  outDivider: Int = 4
) {
  assert(Seq(16, 20, 32, 40).contains(dataWidth),
    s"rx dataWidth must be 16, 20, 32, or 40, was $dataWidth")
  assert(Seq(1, 2, 4, 8).contains(outDivider),
    s"rx outDivider must be 1, 2, 4, or 8, was $outDivider")
}

/** Transmit side settings for a [[Gtpe2Channel]]
 *
 *  @param usrClkDomain Drives TXUSRCLK
 *  @param usrClk2Domain Drives TXUSRCLK2, equal to usrClkDomain at 20 bits
 *  @param dataWidth PCS to PMA width, 16, 20, 32 or 40
 *  @param outDivider Serial clock divider, 1, 2, 4 or 8
 */
case class Gtpe2TxConfig(
  usrClkDomain: ClockDomain = null,
  usrClk2Domain: ClockDomain = null,
  dataWidth: Int = 20,
  outDivider: Int = 2
) {
  assert(Seq(16, 20, 32, 40).contains(dataWidth),
    s"tx dataWidth must be 16, 20, 32, or 40, was $dataWidth")
  assert(Seq(1, 2, 4, 8).contains(outDivider),
    s"tx outDivider must be 1, 2, 4, or 8, was $outDivider")
}

/** Reserved and tie-off pins, all defaulted so none need driving
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
/** Sync ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2RxBufferBypassSyncIo() extends Bundle {
    val mode = in Bool() setName("RXSYNCMODE")
    val input = in Bool() setName("RXSYNCIN")
    val allPhaseAlignDone = in Bool() setName("RXSYNCALLIN")
    val output = out Bool() setName("RXSYNCOUT")
    val done = out Bool() setName("RXSYNCDONE")

    def disable() = {
      mode := False
      input := False
      allPhaseAlignDone := False
    }
}

/** DelayAlignment ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2RxBufferBypassDelayAlignmentIo() extends Bundle {
    val bypass = in Bool() setName("RXDLYBYPASS")
    val softReset = in Bool() setName("RXDLYSRESET")
    val softResetDone = out Bool() setName("RXDLYSRESETDONE")
    val enable = in Bool() setName("RXDLYEN")
    val counterOverrideEn = in Bool() setName("RXDLYOVRDEN")
    val insertionEnable = in Bool() setName("RXDDIEN")

    def disable() = {
      bypass := True
      softReset := False
      enable := False
      counterOverrideEn := False
      insertionEnable := False
    }
}

/** PhaseAlignment ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2RxBufferBypassPhaseAlignmentIo() extends Bundle {
    val enable = in Bool() setName("RXPHALIGNEN")
    val set = in Bool() setName("RXPHALIGN")
    val done = out Bool() setName("RXPHALIGNDONE")
    val counterOverrideEn = in Bool() setName("RXPHOVRDEN")
    val monitor = out Bits(5 bits) setName("RXPHMONITOR")
    val slipMonitor = out Bits(5 bits) setName("RXPHSLIPMONITOR")

    def disable() = {
      enable := False
      set := False
      counterOverrideEn := False
    }
}

/** Rate ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2RxFabricClockOutputRateIo(config: Gtpe2RxConfig) extends Bundle {
    val mode = in Bool() setName("RXRATEMODE")
    val divider = in Bits(3 bits) setName("RXRATE")
    val done = out Bool() setName("RXRATEDONE")

    ClockDomainTag(config.usrClk2Domain)(done)

    def syncMode() = {
      mode := False
      ClockDomainTag(config.usrClk2Domain)(divider)
    }

    def disable() = {
      mode := False
      divider := B"3'0"
    }
}

/** Offset ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2RxClockDataRecoveryOffsetIo() extends Bundle {
    val hold = in Bool() setName("RXOSHOLD")
    val overrideEn = in Bool() setName("RXOSOVRDEN")

    def disable() = {
      hold := False
      overrideEn := False
    }
}

/** Rate ports on the transmit side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2TxFabricClockOutputRateIo() extends Bundle {
    val mode = in Bool() setName("TXRATEMODE")
    val divider = in Bits(3 bits) setName("TXRATE")
    val done = out Bool() setName("TXRATEDONE")

    def disable() = {
      mode := False
      divider := B"3'0"
    }
}

/** DelayAlignment ports on the transmit side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2TxBufferBypassDelayAlignmentIo() extends Bundle {
    val bypass = in Bool() setName("TXDLYBYPASS")
    val softReset = in Bool() setName("TXDLYSRESET")
    val softResetDone = out Bool() setName("TXDLYSRESETDONE")
    val enable = in Bool() setName("TXDLYEN")
    val counterOverrideEn = in Bool() setName("TXDLYOVRDEN")

    val clk = in Bool() setName("TXPHDLYTSTCLK")
    val hold = in Bool() setName("TXDLYHOLD")
    val upOrDown = in Bool() setName("TXDLYUPDOWN")

    def disable() = {
      bypass := False
      softReset := False
      enable := False
      counterOverrideEn := False
      clk := False
      hold := False
      upOrDown := False
    }
}

/** PhaseAlignment ports on the transmit side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2TxBufferBypassPhaseAlignmentIo() extends Bundle {
    val enable = in Bool() setName("TXPHALIGNEN")
    val set = in Bool() setName("TXPHALIGN")
    val done = out Bool() setName("TXPHALIGNDONE")
    val init = in Bool() setName("TXPHINIT")
    val initDone = out Bool() setName("TXPHINITDONE")
    val counterOverrideEn = in Bool() setName("TXPHOVRDEN")

    def disable() = {
      enable := False
      set := False
      init := False
      counterOverrideEn := False
    }
}

/** Pcie ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2RxPcieIo(config: Gtpe2RxConfig) extends Bundle {
  val valid = out Bool() setName("RXVALID")
  val status = out Bits(3 bits) setName("RXSTATUS")
  val phyStatus = out Bool() setName("PHYSTATUS")

  ClockDomainTag(config.usrClk2Domain)(
    valid,
    phyStatus,
    status
  )
}

/** Gearbox ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2RxGearboxIo(config: Gtpe2RxConfig) extends Bundle {
  // the gearbox is disabled (RXGEARBOX_EN is FALSE), so slip never needs
  // driving. Make this conditional again if the gearbox becomes optional.
  val slip = in Bool() setName("RXGEARBOXSLIP") default(False)
  val dataValid = out Bits(2 bits) setName("RXDATAVALID")
  val headerValid = out Bool() setName("RXHEADERVALID")
  val header = out Bits(3 bits) setName("RXHEADER")
  val startOfSeq = out Bits(2 bits) setName("RXSTARTOFSEQ")

  ClockDomainTag(config.usrClk2Domain)(
    slip,
    dataValid,
    headerValid,
    header,
    startOfSeq
  )

  def disable() = {
    slip := False
  }
}

/** ChannelBonding ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2RxChannelBondingIo(config: Gtpe2RxConfig) extends Bundle {
  val enable = in Bool() setName("RXCHBONDEN")
  val master = in Bool() setName("RXCHBONDMASTER")
  val slave = in Bool() setName("RXCHBONDSLAVE")
  val seqDetected = out Bool() setName("RXCHANBONDSEQ")
  val isAligned = out Bool() setName("RXCHANISALIGNED")
  val realign = out Bool() setName("RXCHANREALIGN")
  val level = in Bits(3 bits) setName("RXCHBONDLEVEL")
  val output = out Bits(4 bits) setName("RXCHBONDO")
  val input = in Bits(4 bits) setName("RXCHBONDI")

  ClockDomainTag(config.usrClkDomain)(
    output,
    input
  )

  ClockDomainTag(config.usrClk2Domain)(
    enable,
    master,
    slave,
    seqDetected,
    isAligned,
    realign,
    level
  )

  def disable() = {
    enable := False
    master := False
    slave := False
    level := B"3'0"
    input := B"4'0"
  }
}

/** ClockCorrection ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2RxClockCorrectionIo(config: Gtpe2RxConfig) extends Bundle {
  val status = out Bits(2 bits) setName("RXCLKCORCNT")
  ClockDomainTag(config.usrClk2Domain)(status)
}

/** ElasticBuffer ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2RxElasticBufferIo(config: Gtpe2RxConfig) extends Bundle {
  val reset = in Bool() setName("RXBUFRESET")
  val status = out Bits(3 bits) setName("RXBUFSTATUS")

  ClockDomainTag(config.usrClk2Domain)(status)

  def disable() = {
    reset := False
  }
}

/** BufferBypass ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2RxBufferBypassIo() extends Bundle {
  val powerDown = in Bool() setName("RXPHDLYPD")
  val reset = in Bool() setName("RXPHDLYRESET")

  def disable() = {
    powerDown := False
    reset := False

    phaseAlignment.disable()
    delayAlignment.disable()
    sync.disable()
  }

  val phaseAlignment = Gtpe2RxBufferBypassPhaseAlignmentIo()

  val delayAlignment = Gtpe2RxBufferBypassDelayAlignmentIo()

  val sync = Gtpe2RxBufferBypassSyncIo()
}

/** Decoder8b10b ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2RxDecoder8b10bIo(config: Gtpe2RxConfig) extends Bundle {
  val enable = in Bool() setName("RX8B10BEN")
  val charIsComma = out Bits(4 bits) setName("RXCHARISCOMMA")
  val charIsK = out Bits(4 bits) setName("RXCHARISK")
  val disparityErr = out Bits(4 bits) setName("RXDISPERR")
  val notInTable = out Bits(4 bits) setName("RXNOTINTABLE")

  ClockDomainTag(config.usrClk2Domain)(
    enable,
    charIsComma,
    charIsK,
    disparityErr,
    notInTable
  )

  def disable() = {
    enable := False
  }
}

/** CommaAlignment ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2RxCommaAlignmentIo(config: Gtpe2RxConfig) extends Bundle {
  val detectEnable = in Bool() setName("RXCOMMADETEN")
  val detect = out Bool() setName("RXCOMMADET")
  val mCommaEnable = in Bool() setName("RXMCOMMAALIGNEN")
  val pCommaEnable = in Bool() setName("RXPCOMMAALIGNEN")
  val slide = in Bool() setName("RXSLIDE")

  ClockDomainTag(config.usrClk2Domain)(
    detectEnable,
    detect,
    mCommaEnable,
    pCommaEnable,
    slide
  )

  def disable() = {
    detectEnable := False
    mCommaEnable := False
    pCommaEnable := False
    slide := False
  }
}

/** ByteAlignment ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2RxByteAlignmentIo(config: Gtpe2RxConfig) extends Bundle {
  val isAligned = out Bool() setName("RXBYTEISALIGNED")
  val realign = out Bool() setName("RXBYTEREALIGN")

  ClockDomainTag(config.usrClk2Domain)(
    isAligned,
    realign
  )
}

/** PatternChecker ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2RxPatternCheckerIo(config: Gtpe2RxConfig) extends Bundle {
  val prbsErrCounterReset = in Bool() setName("RXPRBSCNTRESET")
  val prbsPatternSelect = in Bits(3 bits) setName("RXPRBSSEL")
  val prbsErr = out Bool() setName("RXPRBSERR")

  ClockDomainTag(config.usrClk2Domain)(
    prbsErrCounterReset,
    prbsPatternSelect,
    prbsErr
  )

  def disable() = {
    prbsErrCounterReset := False
    prbsPatternSelect := B"3'0"
  }
}

/** Polarity ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2RxPolarityIo() extends Bundle {
  val invert = in Bool() setName("RXPOLARITY")

  def disable() = {
    invert := False
  }
}

/** MarginAnalysis ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2RxMarginAnalysisIo(config: Gtpe2RxConfig) extends Bundle {
  val reset = in Bool() setName("EYESCANRESET")
  val mode = in Bool() setName("EYESCANMODE")
  val trigger = in Bool() setName("EYESCANTRIGGER")
  val dataErr = out Bool() setName("EYESCANDATAERROR")

  ClockDomainTag(config.usrClk2Domain)(trigger)

  def disable() = {
    reset := False
    mode := False
    trigger := False
  }
}

/** FabricClockOutput ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2RxFabricClockOutputIo(config: Gtpe2RxConfig) extends Bundle {
  val outClkSelect = in Bits(3 bits) setName("RXOUTCLKSEL")
  val outClk = out Bool() setName("RXOUTCLK")

  def rxOutClkPma(): Bool = {
    outClkSelect := B"3'010"
    outClk
  }

  def disable() = {
    outClkSelect := B"3'011"
    rate.disable()
  }

  val rate = Gtpe2RxFabricClockOutputRateIo(config)
}

/** ClockDataRecovery ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2RxClockDataRecoveryIo() extends Bundle {
  val hold = in Bool() setName("RXCDRHOLD")

  def disable() = {
    hold := False

    offset.disable()
  }

  val offset = Gtpe2RxClockDataRecoveryOffsetIo()
}

/** Equalizer ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2RxEqualizerIo() extends Bundle {
  val lpmReset = in Bool() setName("RXLPMRESET")
  val lpmHighFreqOverrideEn = in Bool() setName("RXLPMHFOVRDEN")
  val lpmHighFreqHold = in Bool() setName("RXLPMHFHOLD")
  val lpmLowFreqOverrideEn = in Bool() setName("RXLPMLFOVRDEN")
  val lpmLowFreqHold = in Bool() setName("RXLPMLFHOLD")

  def disable() = {
    lpmReset := False
    lpmHighFreqOverrideEn := False
    lpmHighFreqHold := False
    lpmLowFreqOverrideEn := False
    lpmLowFreqHold := False
  }
}

/** OutOfBand ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2RxOutOfBandIo(config: Gtpe2RxConfig) extends Bundle {
  val reset = in Bool() setName("RXOOBRESET")
  val comInitDetect = out Bool() setName("RXCOMINITDET")
  val comSasDetect = out Bool() setName("RXCOMSASDET")
  val comWakeDetect = out Bool() setName("RXCOMWAKEDET")
  val electricalIdle = out Bool() setName("RXELECIDLE")
  val electricalIdleMode = in Bits(2 bits) setName("RXELECIDLEMODE")
  val sigValidClk = in Bool() setName("SIGVALIDCLK")

  ClockDomainTag(config.usrClk2Domain)(
    comInitDetect,
    comSasDetect,
    comWakeDetect,
    electricalIdle
  )

  def disable() = {
    reset := False
    electricalIdleMode := B"2'11"
    sigValidClk := False
  }
}

/** AnalogFrontEnd ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2RxAnalogFrontEndIo() extends Bundle {
  val input = in(DiffPair())
  input.p.setName("GTPRXP")
  input.n.setName("GTPRXN")

  def disable() = {
    input.p := False
    input.n := False
  }
}

/** Clocking ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2RxClockingIo() extends Bundle {
  val sysClkSelect = in Bits(2 bits) setName("RXSYSCLKSEL")
  val usrClk = in Bool() setName("RXUSRCLK")
  val usrClk2 = in Bool() setName("RXUSRCLK2")
  val usrReady = in Bool() setName("RXUSERRDY")



  def staticSysClk(pmaClkPll: Int, rxOutClkPll: Int) = {
    assert(
      (0 to 1).contains(pmaClkPll),
      "sysClkSelect must be PLL0 or PLL1"
    )
    assert(
      (0 to 1).contains(rxOutClkPll),
      "sysClkSelect must be PLL0 or PLL1"
    )
    sysClkSelect(0) := Bool(pmaClkPll == 1)
    sysClkSelect(1) := Bool(rxOutClkPll == 1)
  }

  def disable() = {
    sysClkSelect := B"2'0"
    usrClk := False
    usrClk2 := False
    usrReady := False
  }
}

/** OutOfBand ports on the transmit side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2TxOutOfBandIo() extends Bundle {
  val comInit = in Bool() setName("TXCOMINIT")
  val comSas = in Bool() setName("TXCOMSAS")
  val comWake = in Bool() setName("TXCOMWAKE")
  val comFinish = out Bool() setName("TXCOMFINISH")
  val electricalIdleMode = in Bool() setName("TXPDELECIDLEMODE")

  def disable() = {
    comInit := False
    comSas := False
    comWake := False
    electricalIdleMode := False
  }
}

/** Pcie ports on the transmit side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2TxPcieIo() extends Bundle {
  val swing = in Bool() setName("TXSWING")
  val detectReceiver = in Bool() setName("TXDETECTRX")

  def disable() = {
    swing := False
    detectReceiver := False
  }
}

/** Driver ports on the transmit side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2TxDriverIo() extends Bundle {
  val inhibit = in Bool() setName("TXINHIBIT")
  val electricalIdle = in Bool() setName("TXELECIDLE")
  val preDriverSwing = in Bits(3 bits) setName("TXBUFDIFFCTRL")
  val driverSwing = in Bits(4 bits) setName("TXDIFFCTRL")
  val deEmphasis = in Bool() setName("TXDEEMPH")

  val mainCursor = in Bits(7 bits) setName("TXMAINCURSOR")
  val margin = in Bits(3 bits) setName("TXMARGIN")

  val preCursor = in Bits(5 bits) setName("TXPRECURSOR")
  val preCursorInvert = in Bool() setName("TXPRECURSORINV")

  val postCursor = in Bits(5 bits) setName("TXPOSTCURSOR")
  val postCursorInvert = in Bool() setName("TXPOSTCURSORINV")

  val output = out(new DiffPair())
  output.p.setName("GTPTXP")
  output.n.setName("GTPTXN")

  def disable() = {
    inhibit := False
    electricalIdle := True
    preDriverSwing := B"3'0"
    driverSwing := B"4'0"
    deEmphasis := False
    mainCursor := B"7'0"
    margin := B"3'0"
    preCursor := B"5'0"
    preCursorInvert := False
    postCursor := B"5'0"
    postCursorInvert := False
  }
}

/** PhaseInterpolator ports on the transmit side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2TxPhaseInterpolatorIo() extends Bundle {
  val powerDown = in Bool() setName("TXPIPPMPD")
  val enable = in Bool() setName("TXPIPPMEN")
  val overrideEn = in Bool() setName("TXPIPPMOVRDEN")
  val stepSize = in Bits(5 bits) setName("TXPIPPMSTEPSIZE")

  def disable() = {
    powerDown := True
    enable := False
    overrideEn := False
    stepSize := B"5'0"
  }
}

/** FabricClockOutput ports on the transmit side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2TxFabricClockOutputIo() extends Bundle {
  val outClkSelect = in Bits(3 bits) setName("TXOUTCLKSEL")
  val outClk = out Bool() setName("TXOUTCLK")

  def disable() = {
    outClkSelect := B"3'011"
    rate.disable()
  }

  val rate = Gtpe2TxFabricClockOutputRateIo()
}

/** Polarity ports on the transmit side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2TxPolarityIo() extends Bundle {
  val invert = in Bool() setName("TXPOLARITY")

  def disable() = {
    invert := False
  }
}

/** PatternGenerator ports on the transmit side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2TxPatternGeneratorIo() extends Bundle {
  val prbsPatternSelect = in Bits(3 bits) setName("TXPRBSSEL")
  val prbsForceErr = in Bool() setName("TXPRBSFORCEERR")

  def disable() = {
    prbsPatternSelect := B"3'0"
    prbsForceErr := False
  }
}

/** BufferBypass ports on the transmit side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2TxBufferBypassIo() extends Bundle {
  val powerDown = in Bool() setName("TXPHDLYPD")
  val reset = in Bool() setName("TXPHDLYRESET")

  def disable() = {
    powerDown := True
    reset := False

    phaseAlignment.disable()
    delayAlignment.disable()
  }

  val phaseAlignment = Gtpe2TxBufferBypassPhaseAlignmentIo()

  val delayAlignment = Gtpe2TxBufferBypassDelayAlignmentIo()
}

/** Buffer ports on the transmit side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2TxBufferIo() extends Bundle {
  val status = out Bits(2 bits) setName("TXBUFSTATUS")
}

/** Gearbox ports on the transmit side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2TxGearboxIo() extends Bundle {
  val ready = out Bool() setName("TXGEARBOXREADY")
  val header = in Bits(3 bits) setName("TXHEADER")
  val sequence = in Bits(7 bits) setName("TXSEQUENCE")
  val startSeq = in Bool() setName("TXSTARTSEQ")

  def disable() = {
    header := B"3'0"
    sequence := B"7'0"
    startSeq := False
  }
}

/** Encoder8b10b ports on the transmit side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2TxEncoder8b10bIo() extends Bundle {
  val enable = in Bool() setName("TX8B10BEN")
  val bypass = in Bits(4 bits) setName("TX8B10BBYPASS")
  val charDisparityMode = in Bits(4 bits) setName("TXCHARDISPMODE")
  val charDisparityValue = in Bits(4 bits) setName("TXCHARDISPVAL")
  val charIsK = in Bits(4 bits) setName("TXCHARISK")

  def disable() = {
    enable := False
    bypass := B"4'0"
    charDisparityMode := B"4'0"
    charDisparityValue := B"4'0"
    charIsK := B"4'0"
  }
}

/** Clocking ports on the transmit side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2TxClockingIo() extends Bundle {
  val sysClkSelect = in Bits(2 bits) setName("TXSYSCLKSEL")
  val usrClk = in Bool() setName("TXUSRCLK")
  val usrClk2 = in Bool() setName("TXUSRCLK2")
  val usrReady = in Bool() setName("TXUSERRDY")


  def disable() = {
    sysClkSelect := B"2'0"
    usrClk := False
    usrClk2 := False
    usrReady := False
  }
}

case class Gtpe2ChannelReservedIo() extends Bundle {
  val gtRsvd = in Bits(16 bits) setName("GTRSVD") default(B"16'0")
  val pcsRsvdIn = in Bits(16 bits) setName("PCSRSVDIN") default(B"16'0")
  val tstIn = in Bits(20 bits) setName("TSTIN") default(B"20'hFFFFF")
  val pmaRsvdOut0 = out Bool() setName("PMARSVDOUT0")
  val pmaRsvdOut1 = out Bool() setName("PMARSVDOUT1")
  val pmaRsvdIn0 = in Bool() setName("PMARSVDIN0") default(False)
  val pmaRsvdIn1 = in Bool() setName("PMARSVDIN1") default(False)
  val pmaRsvdIn2 = in Bool() setName("PMARSVDIN2") default(False)
  val pmaRsvdIn3 = in Bool() setName("PMARSVDIN3") default(False)
  val pmaRsvdIn4 = in Bool() setName("PMARSVDIN4") default(False)
  val rxCdrReset = in Bool() setName("RXCDRRESET") default(False)
  val rxCdrFreqReset = in Bool() setName("RXCDRFREQRESET") default(False)
  val rxCdrOvrdEn = in Bool() setName("RXCDROVRDEN") default(False)
  val rxCdrResetRsv = in Bool() setName("RXCDRRESETRSV") default(False)
  val rxCdrLock = out Bool() setName("RXCDRLOCK")
  val rxOsIntDone = out Bool() setName("RXOSINTDONE")
  val rxOsIntStarted = out Bool() setName("RXOSINTSTARTED")
  val rxOsIntStrobeDone = out Bool() setName("RXOSINTSTROBEDONE")
  val rxOsIntStrobeStarted = out Bool() setName("RXOSINTSTROBESTARTED")
  val rxOsCalReset = in Bool() setName("RXOSCALRESET") default(False)
  val rxOsIntEn = in Bool() setName("RXOSINTEN") default(True)
  val rxOsIntHold = in Bool() setName("RXOSINTHOLD") default(False)
  val rxOsIntNtrLen = in Bool() setName("RXOSINTNTRLEN") default(False)
  val rxOsIntOvrdEn = in Bool() setName("RXOSINTOVRDEN") default(False)
  val rxOsIntPd = in Bool() setName("RXOSINTPD") default(False)
  val rxOsIntStrobe = in Bool() setName("RXOSINTSTROBE") default(False)
  val rxOsIntTestOvrdEn =
    in Bool() setName("RXOSINTTESTOVRDEN") default(False)
  val rxOsIntCfg =
    in Bits(4 bits) setName("RXOSINTCFG") default(B"4'b0010")
  val rxOsIntID0 = in Bits(4 bits) setName("RXOSINTID0") default(B"4'0")
  val rxLpmOsIntNtrLen =
    in Bool() setName("RXLPMOSINTNTRLEN") default(False)
  val rxOutClkFabric = out Bool() setName("RXOUTCLKFABRIC")
  val rxOutClkPcs = out Bool() setName("RXOUTCLKPCS")
  val dMonFifoReset = in Bool() setName("DMONFIFORESET") default(False)
  val pcsRsvdOut = out Bits(16 bits) setName("PCSRSVDOUT")
  val clkRsvd0 = in Bool() setName("CLKRSVD0") default(False)
  val clkRsvd1 = in Bool() setName("CLKRSVD1") default(False)
  val resetOvrd = in Bool() setName("RESETOVRD") default(False)
  val rxDfeXYDEn = in Bool() setName("RXDFEXYDEN") default(False)
  val rxAdaptSelTest =
    in Bits(14 bits) setName("RXADAPTSELTEST") default(B"14'0")
  val setErrStatus = in Bool() setName("SETERRSTATUS") default(False)
  val txSyncMode = in Bool() setName("TXSYNCMODE") default(False)
  val txSyncIn = in Bool() setName("TXSYNCIN") default(False)
  val txSyncOut = out Bool() setName("TXSYNCOUT")
  val txSyncAllIn = in Bool() setName("TXSYNCALLIN") default(False)
  val txSyncDone = out Bool() setName("TXSYNCDONE")
  val txOutClkFabric = out Bool() setName("TXOUTCLKFABRIC")
  val txOutClkPcs = out Bool() setName("TXOUTCLKPCS")
  val txPiPpmSel = in Bool() setName("TXPIPPMSEL") default(True)
  val txPiSoPd = in Bool() setName("TXPISOPD") default(False)
  val txDiffPd = in Bool() setName("TXDIFFPD") default(False)
  val cfgReset = in Bool() setName("CFGRESET") default(False)
}

/** Digital monitor output, selected by DMONITOR_CFG
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2DigitalMonitorIo() extends Bundle {
  val clk = in Bool() setName("DMONITORCLK")
  val output = out Bits(15 bits) setName("DMONITOROUT")

  def disable() = {
    clk := False
  }
}

/** Loopback mode select
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2LoopbackIo() extends Bundle {
  val mode = in Bits(3 bits) setName("LOOPBACK")

  def disable() = {
    mode := B"3'0"
  }
}

/** Transmit half of a GTPE2_CHANNEL
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2TxIo(config: Gtpe2TxConfig) extends Bundle {
  val powerDown = in Bits(2 bits) setName("TXPD")

  val reset = in Bool() setName("GTTXRESET")
  val resetDone = out Bool() setName("TXRESETDONE")

  val pmaReset = in Bool() setName("TXPMARESET")
  val pmaResetDone = out Bool() setName("TXPMARESETDONE")

  val pcsReset = in Bool() setName("TXPCSRESET")

  /** The primitive's full 32 bit port
   *
   *  Only part of it carries data. With 8b/10b enabled that is one byte
   *  per symbol, so config.dataWidth 20 uses bits 15..0 with charIsK 1..0, and
   *  40 uses all 32 with charIsK 3..0. With the encoder bypassed the
   *  symbols are split across this and the encoder's disparity fields, as
   *  they are on the RX side.
   */
  val rawData = in Bits(32 bits) setName("TXDATA")

  def disable() = {
    powerDown := B"2'11"
    reset := False
    pmaReset := False
    pcsReset := False
    rawData := B"32'0"

    clocking.disable()
    encoder8b10b.disable()
    gearbox.disable()
    bufferBypass.disable()
    patternGenerator.disable()
    polarity.disable()
    fabricClockOutput.disable()
    phaseInterpolator.disable()
    driver.disable()
    pcie.disable()
    outOfBand.disable()
  }

  val clocking = Gtpe2TxClockingIo()

  val encoder8b10b = Gtpe2TxEncoder8b10bIo()

  val gearbox = Gtpe2TxGearboxIo()

  val buffer = Gtpe2TxBufferIo()

  val bufferBypass = Gtpe2TxBufferBypassIo()

  val patternGenerator = Gtpe2TxPatternGeneratorIo()

  val polarity = Gtpe2TxPolarityIo()

  val fabricClockOutput = Gtpe2TxFabricClockOutputIo()

  val phaseInterpolator = Gtpe2TxPhaseInterpolatorIo()

  val driver = Gtpe2TxDriverIo()

  val pcie = Gtpe2TxPcieIo()

  val outOfBand = Gtpe2TxOutOfBandIo()
}

/** Receive half of a GTPE2_CHANNEL
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2RxIo(config: Gtpe2RxConfig) extends Bundle {
  val powerDown = in Bits(2 bits) setName("RXPD")

  val reset = in Bool() setName("GTRXRESET")
  val resetDone = out Bool() setName("RXRESETDONE")
  ClockDomainTag(config.usrClk2Domain)(resetDone)

  val pmaReset = in Bool() setName("RXPMARESET")
  val pmaResetDone = out Bool() setName("RXPMARESETDONE")

  val pcsReset = in Bool() setName("RXPCSRESET")

  def disable() = {
    powerDown := B"2'11"
    reset := False
    pmaReset := False
    pcsReset := False

    clocking.disable()
    analogFrontEnd.disable()
    outOfBand.disable()
    equalizer.disable()
    clockDataRecovery.disable()
    fabricClockOutput.disable()
    marginAnalysis.disable()
    polarity.disable()
    patternChecker.disable()
    commaAlignment.disable()
    decoder8b10b.disable()
    bufferBypass.disable()
    elasticBuffer.disable()
    channelBonding.disable()
    gearbox.disable()
  }

  val clocking = Gtpe2RxClockingIo()

  val analogFrontEnd = Gtpe2RxAnalogFrontEndIo()

  val outOfBand = Gtpe2RxOutOfBandIo(config)

  val equalizer = Gtpe2RxEqualizerIo()

  val clockDataRecovery = Gtpe2RxClockDataRecoveryIo()

  val fabricClockOutput = Gtpe2RxFabricClockOutputIo(config)

  val marginAnalysis = Gtpe2RxMarginAnalysisIo(config)

  val polarity = Gtpe2RxPolarityIo()

  val patternChecker = Gtpe2RxPatternCheckerIo(config)

  // Byte alignment
  val byteAlignment = Gtpe2RxByteAlignmentIo(config)

  // Comma alignment
  val commaAlignment = Gtpe2RxCommaAlignmentIo(config)

  // 8b/10b decoder (not TMDS compatible)
  val decoder8b10b = Gtpe2RxDecoder8b10bIo(config)

  val bufferBypass = Gtpe2RxBufferBypassIo()

  val elasticBuffer = Gtpe2RxElasticBufferIo(config)

  val clockCorrection = Gtpe2RxClockCorrectionIo(config)

  val channelBonding = Gtpe2RxChannelBondingIo(config)

  val gearbox = Gtpe2RxGearboxIo(config)

  val pcie = Gtpe2RxPcieIo(config)

  val rawData = out Bits(32 bits) setName("RXDATA")
  ClockDomainTag(config.usrClk2Domain)(rawData)

  def data(decoder8b10bBypass: Boolean): Bits = {
    if (decoder8b10bBypass) {
      Cat(
        decoder8b10b.disparityErr(3),
        decoder8b10b.charIsK(3),
        rawData(31 downto 24),
        decoder8b10b.disparityErr(2),
        decoder8b10b.charIsK(2),
        rawData(23 downto 16),
        decoder8b10b.disparityErr(1),
        decoder8b10b.charIsK(1),
        rawData(15 downto 8),
        decoder8b10b.disparityErr(0),
        decoder8b10b.charIsK(0),
        rawData(7 downto 0)
      )
    } else {
      rawData
    }
  }
}

/** GTPE2_CHANNEL IO ports
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2ChannelIo(
  rxConfig: Gtpe2RxConfig,
  txConfig: Gtpe2TxConfig,
  drpClkDomain: ClockDomain
) extends Bundle {
  val resetSelection = in Bool() setName("GTRESETSEL")

  val drp = slave(Gtpe2DrpIo(9))
  drp.clk.setName("DRPCLK")
  ClockDomainTag(drpClkDomain)(
    drp.addr.setName("DRPADDR"),
    drp.dataIn.setName("DRPDI"),
    drp.dataOut.setName("DRPDO"),
    drp.enable.setName("DRPEN"),
    drp.writeEnable.setName("DRPWE"),
    drp.ready.setName("DRPRDY")
  )
  val clocking = in(Gtpe2ChannelClocking())
  clocking.pll0Clk.setName("PLL0CLK")
  clocking.pll0RefClk.setName("PLL0REFCLK")
  clocking.pll1Clk.setName("PLL1CLK")
  clocking.pll1RefClk.setName("PLL1REFCLK")

  val rx = Gtpe2RxIo(rxConfig)

  val tx = Gtpe2TxIo(txConfig)

  val loopback = Gtpe2LoopbackIo()

  val digitalMonitor = Gtpe2DigitalMonitorIo()

  val reserved = Gtpe2ChannelReservedIo()
}

/** GTPE2_CHANNEL primitive: one transceiver lane
 *
 *  @param refClkFreq The reference clock the PLL runs from, which sets CLK25_DIV
 */
case class Gtpe2Channel(
  refClkFreq: HertzNumber,
  rxConfig: Gtpe2RxConfig = Gtpe2RxConfig(),
  txConfig: Gtpe2TxConfig = Gtpe2TxConfig(),
  drpClkDomain: ClockDomain = null,
  simResetSpeedup: Boolean = false
) extends BlackBox {
  val generic = new Generic {
    // The reference clock is divided down to 25 MHz or just under for the
    // transceiver's internal use. Vivado's own PCIe configs bear this out:
    // 250, 125 and 100 MHz map to 10, 5 and 4. Shared by both directions.
    val clk25Div = (refClkFreq.toBigDecimal / BigDecimal(25e6))
      .setScale(0, BigDecimal.RoundingMode.CEILING)
      .toInt
    assert(
      (1 to 32).contains(clk25Div),
      s"refClkFreq needs a CLK25_DIV of $clk25Div, outside the legal 1 to 32"
    )

    // Simulation
    val SIM_RECEIVER_DETECT_PASS = "TRUE"
    val SIM_TX_EIDLE_DRIVE_LEVEL = "X"
    val SIM_RESET_SPEEDUP = if (simResetSpeedup) "TRUE" else "FALSE"
    val SIM_VERSION = "2.0"

    // RX Byte and Word Alignment
    val ALIGN_COMMA_DOUBLE = "FALSE"
    val ALIGN_COMMA_ENABLE = B"10'b1111111111"
    val ALIGN_COMMA_WORD = 1
    val ALIGN_MCOMMA_DET = "FALSE"
    val ALIGN_MCOMMA_VALUE = B"10'b1010000011"
    val ALIGN_PCOMMA_DET = "FALSE"
    val ALIGN_PCOMMA_VALUE = B"10'b0101111100"
    val SHOW_REALIGN_COMMA = "TRUE"
    val RXSLIDE_AUTO_WAIT = 7
    val RXSLIDE_MODE = "OFF"
    val RX_SIG_VALID_DLY = 10

    // RX 8B/10B Decoder
    val RX_DISPERR_SEQ_MATCH = "FALSE"
    val DEC_MCOMMA_DETECT = "FALSE"
    val DEC_PCOMMA_DETECT = "FALSE"
    val DEC_VALID_COMMA_ONLY = "FALSE"

    // RX Clock Correction
    val CBCC_DATA_SOURCE_SEL = "ENCODED"
    val CLK_COR_SEQ_2_USE = "FALSE"
    val CLK_COR_KEEP_IDLE = "FALSE"
    val CLK_COR_MAX_LAT = 9
    val CLK_COR_MIN_LAT = 7
    val CLK_COR_PRECEDENCE = "TRUE"
    val CLK_COR_REPEAT_WAIT = 0
    val CLK_COR_SEQ_LEN = 1
    val CLK_COR_SEQ_1_ENABLE = B"4'b1111"
    val CLK_COR_SEQ_1_1 = B"10'b0100000000"
    val CLK_COR_SEQ_1_2 = B"10'b0000000000"
    val CLK_COR_SEQ_1_3 = B"10'b0000000000"
    val CLK_COR_SEQ_1_4 = B"10'b0000000000"
    val CLK_CORRECT_USE = "FALSE"
    val CLK_COR_SEQ_2_ENABLE = B"4'b1111"
    val CLK_COR_SEQ_2_1 = B"10'b0100000000"
    val CLK_COR_SEQ_2_2 = B"10'b0000000000"
    val CLK_COR_SEQ_2_3 = B"10'b0000000000"
    val CLK_COR_SEQ_2_4 = B"10'b0000000000"

    // RX Channel Bonding
    val CHAN_BOND_KEEP_ALIGN = "FALSE"
    val CHAN_BOND_MAX_SKEW = 1
    val CHAN_BOND_SEQ_LEN = 1
    val CHAN_BOND_SEQ_1_1 = B"10'b0000000000"
    val CHAN_BOND_SEQ_1_2 = B"10'b0000000000"
    val CHAN_BOND_SEQ_1_3 = B"10'b0000000000"
    val CHAN_BOND_SEQ_1_4 = B"10'b0000000000"
    val CHAN_BOND_SEQ_1_ENABLE = B"4'b1111"
    val CHAN_BOND_SEQ_2_1 = B"10'b0000000000"
    val CHAN_BOND_SEQ_2_2 = B"10'b0000000000"
    val CHAN_BOND_SEQ_2_3 = B"10'b0000000000"
    val CHAN_BOND_SEQ_2_4 = B"10'b0000000000"
    val CHAN_BOND_SEQ_2_ENABLE = B"4'b1111"
    val CHAN_BOND_SEQ_2_USE = "FALSE"
    val FTS_DESKEW_SEQ_ENABLE = B"4'b1111"
    val FTS_LANE_DESKEW_CFG = B"4'b1111"
    val FTS_LANE_DESKEW_EN = "FALSE"

    // RX Margin Analysis
    val ES_CONTROL = B"6'b000000"
    val ES_ERRDET_EN = "FALSE"
    val ES_EYE_SCAN_EN = "FALSE"
    val ES_HORZ_OFFSET = B"12'h010"
    val ES_PMA_CFG = B"10'b0000000000"
    val ES_PRESCALE = B"5'b00000"
    val ES_QUALIFIER = B"80'h00000000000000000000"
    val ES_QUAL_MASK = B"80'h00000000000000000000"
    val ES_SDATA_MASK = B"80'h00000000000000000000"
    val ES_VERT_OFFSET = B"9'b000000000"

    // FPGA RX Interface
    val RX_DATA_WIDTH = rxConfig.dataWidth

    // PMA
    val OUTREFCLK_SEL_INV = B"2'b11"
    val PMA_RSV = B"32'h00000333"
    val PMA_RSV2 = B"32'h00002040"
    val PMA_RSV3 = B"2'b00"
    val PMA_RSV4 = B"4'b0000"
    val RX_BIAS_CFG = B"16'b0000111100110011"
    val DMONITOR_CFG = B"24'h000A00"
    val RX_CM_SEL = B"2'b11"
    val RX_CM_TRIM = B"4'b1010"
    val RX_DEBUG_CFG = B"14'b00000000000000"
    val RX_OS_CFG = B"13'b0000010000000"
    val TERM_RCAL_CFG = B"15'b100001000010000"
    val TERM_RCAL_OVRD = B"3'b000"
    val TST_RSV = B"32'h00000000"
    val UCODEER_CLR = B"1'b0"

    // PCI Express
    val PCS_PCIE_EN = "FALSE"

    // PCS
    val PCS_RSVD_ATTR = B"48'h000000000000"

    // RX Buffer
    val RXBUF_ADDR_MODE = "FAST"
    val RXBUF_EIDLE_HI_CNT = B"4'b1000"
    val RXBUF_EIDLE_LO_CNT = B"4'b0000"
    val RXBUF_EN = "TRUE"
    val RX_BUFFER_CFG = B"6'b000000"
    val RXBUF_RESET_ON_CB_CHANGE = "TRUE"
    val RXBUF_RESET_ON_COMMAALIGN = "FALSE"
    val RXBUF_RESET_ON_EIDLE = "FALSE"
    val RXBUF_RESET_ON_RATE_CHANGE = "TRUE"
    val RXBUFRESET_TIME = B"5'b00001"
    val RXBUF_THRESH_OVFLW = 61
    val RXBUF_THRESH_OVRD = "FALSE"
    val RXBUF_THRESH_UNDFLW = 4
    val RXDLY_CFG = B"16'h001F"
    val RXDLY_LCFG = B"9'h030"
    val RXDLY_TAP_CFG = B"16'h0000"
    val RXPH_CFG = B"24'hC00002"
    val RXPHDLY_CFG = B"24'h084020"
    val RXPH_MONITOR_SEL = B"5'b00000"
    val RX_XCLK_SEL = "RXREC"
    val RX_DDI_SEL = B"6'b000000"
    val RX_DEFER_RESET_BUF_EN = "TRUE"

    // CDR
    val RXCDR_CFG = rxConfig.outDivider match {
      // Table 4-13 (UG482 v1.9)
      case 1     => B"83'h0_0011_07FE_2060_2104_1010"
      case 2     => B"83'h0_0011_07FE_2060_2108_1010"
      case 4 | 8 => B"83'h0_0011_07FE_0860_2110_1010"
    }
    val RXCDR_FR_RESET_ON_EIDLE = B"1'b0"
    val RXCDR_HOLD_DURING_EIDLE = B"1'b0"
    val RXCDR_PH_RESET_ON_EIDLE = B"1'b0"
    val RXCDR_LOCK_CFG = B"6'b001001"

    // RX Initialization and Reset
    val RX_CLK25_DIV = clk25Div
    val RXCDRFREQRESET_TIME = B"5'b00001"
    val RXCDRPHRESET_TIME = B"5'b00001"
    val RXISCANRESET_TIME = B"5'b00001"
    val RXPCSRESET_TIME = B"5'b00001"
    val RXPMARESET_TIME = B"5'b00011"

    // RX OOB Signaling
    val RXOOB_CFG = B"7'b0000110"

    // RX Gearbox
    val RXGEARBOX_EN = "FALSE"
    val GEARBOX_MODE = B"3'b000"

    // PRBS Detection
    val RXPRBS_ERR_LOOPBACK = B"1'b0"

    // Power-Down
    val PD_TRANS_TIME_FROM_P2 = B"12'h03c"
    val PD_TRANS_TIME_NONE_P2 = B"8'h3c"
    val PD_TRANS_TIME_TO_P2 = B"8'h64"

    // RX OOB Signaling
    val SAS_MAX_COM = 64
    val SAS_MIN_COM = 36
    val SATA_BURST_SEQ_LEN = B"4'b0101"
    val SATA_BURST_VAL = B"3'b100"
    val SATA_EIDLE_VAL = B"3'b100"
    val SATA_MAX_BURST = 8
    val SATA_MAX_INIT = 21
    val SATA_MAX_WAKE = 7
    val SATA_MIN_BURST = 4
    val SATA_MIN_INIT = 12
    val SATA_MIN_WAKE = 4

    // RX Fabric Clock Output Control
    val TRANS_TIME_RATE = B"8'h0E"

    // TX Buffer
    val TXBUF_EN = "FALSE"
    val TXBUF_RESET_ON_RATE_CHANGE = "TRUE"
    val TXDLY_CFG = B"16'h001F"
    val TXDLY_LCFG = B"9'h030"
    val TXDLY_TAP_CFG = B"16'h0000"
    val TXPH_CFG = B"16'h0780"
    val TXPHDLY_CFG = B"24'h084020"
    val TXPH_MONITOR_SEL = B"5'b00000"
    val TX_XCLK_SEL = "TXUSR"

    // FPGA TX Interface
    val TX_DATA_WIDTH = txConfig.dataWidth

    // TX Configurable Driver
    val TX_DEEMPH0 = B"6'b000000"
    val TX_DEEMPH1 = B"6'b000000"
    val TX_EIDLE_ASSERT_DELAY = B"3'b110"
    val TX_EIDLE_DEASSERT_DELAY = B"3'b100"
    val TX_LOOPBACK_DRIVE_HIZ = "FALSE"
    val TX_MAINCURSOR_SEL = B"1'b0"
    val TX_DRIVE_MODE = "DIRECT"
    val TX_MARGIN_FULL_0 = B"7'b1001110"
    val TX_MARGIN_FULL_1 = B"7'b1001001"
    val TX_MARGIN_FULL_2 = B"7'b1000101"
    val TX_MARGIN_FULL_3 = B"7'b1000010"
    val TX_MARGIN_FULL_4 = B"7'b1000000"
    val TX_MARGIN_LOW_0 = B"7'b1000110"
    val TX_MARGIN_LOW_1 = B"7'b1000100"
    val TX_MARGIN_LOW_2 = B"7'b1000010"
    val TX_MARGIN_LOW_3 = B"7'b1000000"
    val TX_MARGIN_LOW_4 = B"7'b1000000"

    // TX Gearbox
    val TXGEARBOX_EN = "FALSE"

    // TX Initialization and Reset
    val TX_CLK25_DIV = clk25Div
    val TXPCSRESET_TIME = B"5'b00001"
    val TXPMARESET_TIME = B"5'b00001"

    // TX Receiver Detection
    val TX_RXDETECT_CFG = B"14'h1832"
    val TX_RXDETECT_REF = B"3'b100"

    // JTAG
    val ACJTAG_DEBUG_MODE = B"1'b0"
    val ACJTAG_MODE = B"1'b0"
    val ACJTAG_RESET = B"1'b0"

    // CDR
    val CFOK_CFG = B"43'h49000040E80"
    val CFOK_CFG2 = B"7'b0100000"
    val CFOK_CFG3 = B"7'b0100000"
    val CFOK_CFG4 = B"1'b0"
    val CFOK_CFG5 = B"2'h0"
    val CFOK_CFG6 = B"4'b0000"
    val RXOSCALRESET_TIME = B"5'b00011"
    val RXOSCALRESET_TIMEOUT = B"5'b00000"

    // PMA
    val CLK_COMMON_SWING = B"1'b0"
    val RX_CLKMUX_EN = B"1'b1"
    val TX_CLKMUX_EN = B"1'b1"
    val ES_CLK_PHASE_SEL = B"1'b0"
    val USE_PCS_CLK_PHASE_SEL = B"1'b0"
    val PMA_RSV6 = B"1'b0"
    val PMA_RSV7 = B"1'b0"

    // TX Configuration Driver
    val TX_PREDRIVER_MODE = B"1'b0"
    val PMA_RSV5 = B"1'b0"
    val SATA_PLL_CFG = "VCO_3000MHZ"

    // RX Fabric Clock Output Control
    val RXOUT_DIV = rxConfig.outDivider

    // TX Fabric Clock Output Control
    val TXOUT_DIV = txConfig.outDivider

    // RX Phase Interpolator
    val RXPI_CFG0 = B"3'b000"
    val RXPI_CFG1 = B"1'b1"
    val RXPI_CFG2 = B"1'b1"

    // RX Equalizer
    val ADAPT_CFG0 = B"20'h00000"
    val RXLPMRESET_TIME = B"7'b0001111"
    val RXLPM_BIAS_STARTUP_DISABLE = B"1'b0"
    val RXLPM_CFG = B"4'b0110"
    val RXLPM_CFG1 = B"1'b0"
    val RXLPM_CM_CFG = B"1'b0"
    val RXLPM_GC_CFG = B"9'b111100010"
    val RXLPM_GC_CFG2 = B"3'b001"
    val RXLPM_HF_CFG = B"14'b00001111110000"
    val RXLPM_HF_CFG2 = B"5'b01010"
    val RXLPM_HF_CFG3 = B"4'b0000"
    val RXLPM_HOLD_DURING_EIDLE = B"1'b0"
    val RXLPM_INCM_CFG = B"1'b1"
    val RXLPM_IPCM_CFG = B"1'b0"
    val RXLPM_LF_CFG = B"18'b000000001111110000"
    val RXLPM_LF_CFG2 = B"5'b01010"
    val RXLPM_OSINT_CFG = B"3'b100"

    // TX Phase Interpolator PPM Controller
    val TXPI_CFG0 = B"2'b00"
    val TXPI_CFG1 = B"2'b00"
    val TXPI_CFG2 = B"2'b00"
    val TXPI_CFG3 = B"1'b0"
    val TXPI_CFG4 = B"1'b0"
    val TXPI_CFG5 = B"3'b000"
    val TXPI_GREY_SEL = B"1'b0"
    val TXPI_INVSTROBE_SEL = B"1'b0"
    val TXPI_PPMCLK_SEL = "TXUSRCLK2"
    val TXPI_PPM_CFG = B"8'h00"
    val TXPI_SYNFREQ_PPM = B"3'b001"

    // Loopback
    val LOOPBACK_CFG = B"1'b0"
    val PMA_LOOPBACK_CFG = B"1'b0"

    // RX OOB Signalling
    val RXOOB_CLK_CFG = "PMA"

    // TX OOB Signalling
    val TXOOB_CFG = B"1'b0"

    // RX Buffer
    val RXSYNC_MULTILANE = B"1'b1"
    val RXSYNC_OVRD = B"1'b0"
    val RXSYNC_SKIP_DA = B"1'b0"

    // TX Buffer
    val TXSYNC_MULTILANE = B"1'b0"
    val TXSYNC_OVRD = B"1'b1"
    val TXSYNC_SKIP_DA = B"1'b0"
  }

  val io = Gtpe2ChannelIo(rxConfig, txConfig, drpClkDomain)

  if (drpClkDomain != null) {
    mapClockDomain(drpClkDomain, io.drp.clk)
  }
  if (rxConfig.usrClkDomain != null) {
    mapClockDomain(rxConfig.usrClkDomain, io.rx.clocking.usrClk)
  }
  if (rxConfig.usrClk2Domain != null) {
    mapClockDomain(rxConfig.usrClk2Domain, io.rx.clocking.usrClk2)
  }
  if (txConfig.usrClkDomain != null) {
    mapClockDomain(txConfig.usrClkDomain, io.tx.clocking.usrClk)
  }
  if (txConfig.usrClk2Domain != null) {
    mapClockDomain(txConfig.usrClk2Domain, io.tx.clocking.usrClk2)
  }

  noIoPrefix()
  setBlackBoxName("GTPE2_CHANNEL")
}
