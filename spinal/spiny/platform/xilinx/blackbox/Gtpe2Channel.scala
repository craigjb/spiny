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

/**
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2ChannelClocking() extends Bundle {
  /** @group ports */
  val pll0Clk = Bool()
  /** @group ports */
  val pll0RefClk = Bool()
  /** @group ports */
  val pll1Clk = Bool()
  /** @group ports */
  val pll1RefClk = Bool()

  /** @group spiny */
  def fromGtpe2Common(common: Gtpe2Common) = {
    pll0Clk := common.io.pll0.outClk
    pll0RefClk := common.io.pll0.outRefClk
    pll1Clk := common.io.pll1.outClk
    pll1RefClk := common.io.pll1.outRefClk
  }
}


/** CLK25_DIV, the reference clock divided down to 25 MHz or just under for
 *  the transceiver's internal use
 */
private[xilinx] object Gtpe2Clk25Div {
  /** @group spiny */
  def apply(side: String, refClkFreq: HertzNumber): Int = {
    val div = (refClkFreq.toBigDecimal / BigDecimal(25e6))
      .setScale(0, BigDecimal.RoundingMode.CEILING)
      .toInt
    assert(
      (1 to 32).contains(div),
      s"$side refClkFreq needs a CLK25_DIV of $div, outside the legal 1 to 32"
    )
    div
  }
}

/** Organization of the fabric to TX or RX data port
 *  @param count Symbols moved per user clock
 *  @param width Bits of each symbol the fabric sees, 8 or 10
 */
private[xilinx] case class Gtpe2SymbolFormat(count: Int, width: Int)

private[xilinx] object Gtpe2SymbolFormat {
  /** Symbol format per UG482 Table 3-1
   *  @param side Which half, only to name the error
   *  @param dataWidth TX or RX datapath width (20 or 40 bits)
   *  @param bypass8b10b Whether the 8b/10b encoder or decoder is bypassed,
   *         which is what makes the difference between 8 and 10 bit symbols
   *  @group spiny
   */
  def of(side: String, dataWidth: Int, bypass8b10b: Boolean): Gtpe2SymbolFormat = {
    if (!bypass8b10b && !Seq(20, 40).contains(dataWidth)) {
      SpinalError(
        s"$side 8b/10b needs a data width of 20 or 40, not $dataWidth"
      )
    }
    val count = dataWidth match {
      case 16 | 20 => 2
      case 32 | 40 => 4
      case w => SpinalError(s"$side data width $w is not 16, 20, 32 or 40")
    }
    Gtpe2SymbolFormat(count, if (bypass8b10b && dataWidth % 10 == 0) 10 else 8)
  }
}

/** Receive side settings for a [[Gtpe2Channel]]
 *
 *  @param refClkFreq Reference clock of the PLL this half selects at runtime
 *  @param dataWidth PCS to PMA width, 16, 20, 32 or 40
 *  @param outDivider Serial clock divider, 1, 2, 4 or 8
 */
case class Gtpe2RxConfig(
  refClkFreq: HertzNumber,
  dataWidth: Int = 20,
  outDivider: Int = 4
) {
  val clk25Div = Gtpe2Clk25Div("rx", refClkFreq)

  assert(Seq(16, 20, 32, 40).contains(dataWidth),
    s"rx dataWidth must be 16, 20, 32, or 40, was $dataWidth")
  assert(Seq(1, 2, 4, 8).contains(outDivider),
    s"rx outDivider must be 1, 2, 4, or 8, was $outDivider")
}

/** Transmit side settings for a [[Gtpe2Channel]]
 *
 *  @param refClkFreq Reference clock of the PLL this half selects at runtime
 *  @param dataWidth PCS to PMA width, 16, 20, 32 or 40
 *  @param outDivider Serial clock divider, 1, 2, 4 or 8
 *  @param bufferEnabled Send TX through the buffer rather than bypassing it
 */
case class Gtpe2TxConfig(
  refClkFreq: HertzNumber,
  dataWidth: Int = 20,
  outDivider: Int = 2,
  bufferEnabled: Boolean = true
) {
  val clk25Div = Gtpe2Clk25Div("tx", refClkFreq)

  // TXBUF_EN, TX_XCLK_SEL and TXSYNC_OVRD only mean anything together, so
  // they are derived here.
  val bufEnable = if (bufferEnabled) "TRUE" else "FALSE"
  val xclkSelect = if (bufferEnabled) "TXOUT" else "TXUSR"
  val syncOverride = !bufferEnabled

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
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2RxBufferBypassSyncIo() extends Bundle {
    /** @group ports */
    val mode = in Bool() setName("RXSYNCMODE")
    /** @group ports */
    val input = in Bool() setName("RXSYNCIN")
    /** @group ports */
    val allPhaseAlignDone = in Bool() setName("RXSYNCALLIN")
    /** @group ports */
    val output = out Bool() setName("RXSYNCOUT")
    /** @group ports */
    val done = out Bool() setName("RXSYNCDONE")

    /** @group spiny */
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
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2RxBufferBypassDelayAlignmentIo() extends Bundle {
    /** @group ports */
    val bypass = in Bool() setName("RXDLYBYPASS")
    /** @group ports */
    val softReset = in Bool() setName("RXDLYSRESET")
    /** @group ports */
    val softResetDone = out Bool() setName("RXDLYSRESETDONE")
    /** @group ports */
    val enable = in Bool() setName("RXDLYEN")
    /** @group ports */
    val counterOverrideEn = in Bool() setName("RXDLYOVRDEN")
    /** @group ports */
    val insertionEnable = in Bool() setName("RXDDIEN")

    /** @group spiny */
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
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2RxBufferBypassPhaseAlignmentIo() extends Bundle {
    /** @group ports */
    val enable = in Bool() setName("RXPHALIGNEN")
    /** @group ports */
    val set = in Bool() setName("RXPHALIGN")
    /** @group ports */
    val done = out Bool() setName("RXPHALIGNDONE")
    /** @group ports */
    val counterOverrideEn = in Bool() setName("RXPHOVRDEN")
    /** @group ports */
    val monitor = out Bits(5 bits) setName("RXPHMONITOR")
    /** @group ports */
    val slipMonitor = out Bits(5 bits) setName("RXPHSLIPMONITOR")

    /** @group spiny */
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
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2RxFabricClockOutputRateIo() extends Bundle {
    /** @group ports */
    val mode = in Bool() setName("RXRATEMODE")
    /** @group ports */
    val divider = in Bits(3 bits) setName("RXRATE")
    /** @group ports */
    val done = out Bool() setName("RXRATEDONE")

    /** RXRATE is synchronous to RXUSRCLK2 in this mode, asynchronous in the
     *  other, so only this path tags it
     *  @group spiny
     */
    def syncMode(usrClk2Domain: ClockDomain) = {
      mode := False
      ClockDomainTag(usrClk2Domain)(divider)
    }

    /** @group spiny */
    def disable() = {
      mode := False
      divider := B"3'0"
    }
}

/** Offset ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2RxClockDataRecoveryOffsetIo() extends Bundle {
    /** @group ports */
    val hold = in Bool() setName("RXOSHOLD")
    /** @group ports */
    val overrideEn = in Bool() setName("RXOSOVRDEN")

    /** @group spiny */
    def disable() = {
      hold := False
      overrideEn := False
    }
}

/** Rate ports on the transmit side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2TxFabricClockOutputRateIo() extends Bundle {
    /** @group ports */
    val mode = in Bool() setName("TXRATEMODE")
    /** @group ports */
    val divider = in Bits(3 bits) setName("TXRATE")
    /** @group ports */
    val done = out Bool() setName("TXRATEDONE")

    /** TXRATE is synchronous to TXUSRCLK2 in this mode, asynchronous in the
     *  other, so only this path tags it
     *  @group spiny
     */
    def syncMode(usrClk2Domain: ClockDomain) = {
      mode := False
      ClockDomainTag(usrClk2Domain)(divider)
    }

    /** @group spiny */
    def disable() = {
      mode := False
      divider := B"3'0"
    }
}

/** DelayAlignment ports on the transmit side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2TxBufferBypassDelayAlignmentIo() extends Bundle {
    /** @group ports */
    val bypass = in Bool() setName("TXDLYBYPASS")
    /** @group ports */
    val softReset = in Bool() setName("TXDLYSRESET")
    /** @group ports */
    val softResetDone = out Bool() setName("TXDLYSRESETDONE")
    /** @group ports */
    val enable = in Bool() setName("TXDLYEN")
    /** @group ports */
    val counterOverrideEn = in Bool() setName("TXDLYOVRDEN")

    /** @group ports */
    val clk = in Bool() setName("TXPHDLYTSTCLK")
    /** @group ports */
    val hold = in Bool() setName("TXDLYHOLD")
    /** @group ports */
    val upOrDown = in Bool() setName("TXDLYUPDOWN")

    /** @group spiny */
    def disable() = {
      bypass := True
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
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2TxBufferBypassPhaseAlignmentIo() extends Bundle {
    /** @group ports */
    val enable = in Bool() setName("TXPHALIGNEN")
    /** @group ports */
    val set = in Bool() setName("TXPHALIGN")
    /** @group ports */
    val done = out Bool() setName("TXPHALIGNDONE")
    /** @group ports */
    val init = in Bool() setName("TXPHINIT")
    /** @group ports */
    val initDone = out Bool() setName("TXPHINITDONE")
    /** @group ports */
    val counterOverrideEn = in Bool() setName("TXPHOVRDEN")

    /** @group spiny */
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
case class Gtpe2RxPcieIo() extends Bundle {
  /** @group ports */
  val valid = out Bool() setName("RXVALID")
  /** @group ports */
  val status = out Bits(3 bits) setName("RXSTATUS")
  /** @group ports */
  val phyStatus = out Bool() setName("PHYSTATUS")

}

/** Gearbox ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2RxGearboxIo() extends Bundle {
  /** @group ports */
  val slip = in Bool() setName("RXGEARBOXSLIP") default(False)
  /** @group ports */
  val dataValid = out Bits(2 bits) setName("RXDATAVALID")
  /** @group ports */
  val headerValid = out Bool() setName("RXHEADERVALID")
  /** @group ports */
  val header = out Bits(3 bits) setName("RXHEADER")
  /** @group ports */
  val startOfSeq = out Bits(2 bits) setName("RXSTARTOFSEQ")

  /** @group spiny */
  def disable() = {
    slip := False
  }
}

/** ChannelBonding ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2RxChannelBondingIo() extends Bundle {
  /** @group ports */
  val enable = in Bool() setName("RXCHBONDEN")
  /** @group ports */
  val master = in Bool() setName("RXCHBONDMASTER")
  /** @group ports */
  val slave = in Bool() setName("RXCHBONDSLAVE")
  /** @group ports */
  val seqDetected = out Bool() setName("RXCHANBONDSEQ")
  /** @group ports */
  val isAligned = out Bool() setName("RXCHANISALIGNED")
  /** @group ports */
  val realign = out Bool() setName("RXCHANREALIGN")
  /** @group ports */
  val level = in Bits(3 bits) setName("RXCHBONDLEVEL")
  /** @group ports */
  val output = out Bits(4 bits) setName("RXCHBONDO")
  /** @group ports */
  val input = in Bits(4 bits) setName("RXCHBONDI")

  /** @group spiny */
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
case class Gtpe2RxClockCorrectionIo() extends Bundle {
  /** @group ports */
  val status = out Bits(2 bits) setName("RXCLKCORCNT")
}

/** ElasticBuffer ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2RxElasticBufferIo() extends Bundle {
  /** @group ports */
  val reset = in Bool() setName("RXBUFRESET")
  /** @group ports */
  val status = out Bits(3 bits) setName("RXBUFSTATUS")


  /** @group spiny */
  def disable() = {
    reset := False
  }
}

/** BufferBypass ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2RxBufferBypassIo() extends Bundle {
  /** @group ports */
  val powerDown = in Bool() setName("RXPHDLYPD")
  /** @group ports */
  val reset = in Bool() setName("RXPHDLYRESET")

  /** @group spiny */
  def disable() = {
    powerDown := False
    reset := False

    phaseAlignment.disable()
    delayAlignment.disable()
    sync.disable()
  }

  /** @group ports */
  val phaseAlignment = Gtpe2RxBufferBypassPhaseAlignmentIo()

  /** @group ports */
  val delayAlignment = Gtpe2RxBufferBypassDelayAlignmentIo()

  /** @group ports */
  val sync = Gtpe2RxBufferBypassSyncIo()
}

/** Decoder8b10b ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2RxDecoder8b10bIo() extends Bundle {
  /** @group ports */
  val enable = in Bool() setName("RX8B10BEN")
  /** @group ports */
  val charIsComma = out Bits(4 bits) setName("RXCHARISCOMMA")
  /** @group ports */
  val charIsK = out Bits(4 bits) setName("RXCHARISK")
  /** @group ports */
  val disparityErr = out Bits(4 bits) setName("RXDISPERR")
  /** @group ports */
  val notInTable = out Bits(4 bits) setName("RXNOTINTABLE")

  /** @group spiny */
  def disable() = {
    enable := False
  }
}

/** CommaAlignment ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2RxCommaAlignmentIo() extends Bundle {
  /** @group ports */
  val detectEnable = in Bool() setName("RXCOMMADETEN")
  /** @group ports */
  val detect = out Bool() setName("RXCOMMADET")
  /** @group ports */
  val mCommaEnable = in Bool() setName("RXMCOMMAALIGNEN")
  /** @group ports */
  val pCommaEnable = in Bool() setName("RXPCOMMAALIGNEN")
  /** @group ports */
  val slide = in Bool() setName("RXSLIDE")

  /** @group spiny */
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
case class Gtpe2RxByteAlignmentIo() extends Bundle {
  /** @group ports */
  val isAligned = out Bool() setName("RXBYTEISALIGNED")
  /** @group ports */
  val realign = out Bool() setName("RXBYTEREALIGN")

}

/** PatternChecker ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2RxPatternCheckerIo() extends Bundle {
  /** @group ports */
  val prbsErrCounterReset = in Bool() setName("RXPRBSCNTRESET")
  /** @group ports */
  val prbsPatternSelect = in Bits(3 bits) setName("RXPRBSSEL")
  /** @group ports */
  val prbsErr = out Bool() setName("RXPRBSERR")

  /** @group spiny */
  def disable() = {
    prbsErrCounterReset := False
    prbsPatternSelect := B"3'0"
  }
}

/** Polarity ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2RxPolarityIo() extends Bundle {
  /** @group ports */
  val invert = in Bool() setName("RXPOLARITY")

  /** @group spiny */
  def disable() = {
    invert := False
  }
}

/** MarginAnalysis ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2RxMarginAnalysisIo() extends Bundle {
  /** @group ports */
  val reset = in Bool() setName("EYESCANRESET")
  /** @group ports */
  val mode = in Bool() setName("EYESCANMODE")
  /** @group ports */
  val trigger = in Bool() setName("EYESCANTRIGGER")
  /** @group ports */
  val dataErr = out Bool() setName("EYESCANDATAERROR")

  /** @group spiny */
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
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2RxFabricClockOutputIo() extends Bundle {
  /** @group ports */
  val outClkSelect = in Bits(3 bits) setName("RXOUTCLKSEL")
  /** @group ports */
  val outClk = out Bool() setName("RXOUTCLK")

  /** @group spiny */
  def rxOutClkPma(): Bool = {
    outClkSelect := B"3'010"
    outClk
  }

  /** @group spiny */
  def disable() = {
    outClkSelect := B"3'011"
    rate.disable()
  }

  /** @group ports */
  val rate = Gtpe2RxFabricClockOutputRateIo()
}

/** ClockDataRecovery ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2RxClockDataRecoveryIo() extends Bundle {
  /** @group ports */
  val hold = in Bool() setName("RXCDRHOLD")

  /** @group spiny */
  def disable() = {
    hold := False

    offset.disable()
  }

  /** @group ports */
  val offset = Gtpe2RxClockDataRecoveryOffsetIo()
}

/** Equalizer ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2RxEqualizerIo() extends Bundle {
  /** @group ports */
  val lpmReset = in Bool() setName("RXLPMRESET")
  /** @group ports */
  val lpmHighFreqOverrideEn = in Bool() setName("RXLPMHFOVRDEN")
  /** @group ports */
  val lpmHighFreqHold = in Bool() setName("RXLPMHFHOLD")
  /** @group ports */
  val lpmLowFreqOverrideEn = in Bool() setName("RXLPMLFOVRDEN")
  /** @group ports */
  val lpmLowFreqHold = in Bool() setName("RXLPMLFHOLD")

  /** @group spiny */
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
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2RxOutOfBandIo() extends Bundle {
  /** @group ports */
  val reset = in Bool() setName("RXOOBRESET")
  /** @group ports */
  val comInitDetect = out Bool() setName("RXCOMINITDET")
  /** @group ports */
  val comSasDetect = out Bool() setName("RXCOMSASDET")
  /** @group ports */
  val comWakeDetect = out Bool() setName("RXCOMWAKEDET")
  /** @group ports */
  val electricalIdle = out Bool() setName("RXELECIDLE")
  /** @group ports */
  val electricalIdleMode = in Bits(2 bits) setName("RXELECIDLEMODE")
  /** @group ports */
  val sigValidClk = in Bool() setName("SIGVALIDCLK")

  /** @group spiny */
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
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2RxAnalogFrontEndIo() extends Bundle {
  /** @group ports */
  val input = in(DiffPair())
  input.p.setName("GTPRXP")
  input.n.setName("GTPRXN")

  /** @group spiny */
  def disable() = {
    input.p := False
    input.n := False
  }
}

/** Clocking ports on the receive side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2RxClockingIo() extends Bundle {
  /** @group ports */
  val sysClkSelect = in Bits(2 bits) setName("RXSYSCLKSEL")
  /** @group ports */
  val usrClk = in Bool() setName("RXUSRCLK")
  /** @group ports */
  val usrClk2 = in Bool() setName("RXUSRCLK2")

  /** Drives RXUSRCLK and RXUSRCLK2 from the owner's domains
   *
   *  @param usrClkDomain Drives RXUSRCLK
   *  @param usrClk2Domain Drives RXUSRCLK2, the same domain at 20 bits
   *  @group spiny
   */
  def connectClocks(
    usrClkDomain: ClockDomain,
    usrClk2Domain: ClockDomain = null
  ): Unit = {
    usrClk := usrClkDomain.readClockWire
    usrClk2 := Option(usrClk2Domain).getOrElse(usrClkDomain).readClockWire
  }
  /** @group ports */
  val usrReady = in Bool() setName("RXUSERRDY")



  /** @group spiny */
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

  /** @group spiny */
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
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2TxOutOfBandIo() extends Bundle {
  /** @group ports */
  val comInit = in Bool() setName("TXCOMINIT")
  /** @group ports */
  val comSas = in Bool() setName("TXCOMSAS")
  /** @group ports */
  val comWake = in Bool() setName("TXCOMWAKE")
  /** @group ports */
  val comFinish = out Bool() setName("TXCOMFINISH")
  /** @group ports */
  val electricalIdleMode = in Bool() setName("TXPDELECIDLEMODE")

  /** @group spiny */
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
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2TxPcieIo() extends Bundle {
  /** @group ports */
  val swing = in Bool() setName("TXSWING")
  /** @group ports */
  val detectReceiver = in Bool() setName("TXDETECTRX")

  /** @group spiny */
  def disable() = {
    swing := False
    detectReceiver := False
  }
}

/** Driver ports on the transmit side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2TxDriverIo() extends Bundle {
  /** @group ports */
  val inhibit = in Bool() setName("TXINHIBIT")
  /** @group ports */
  val electricalIdle = in Bool() setName("TXELECIDLE")
  /** @group ports */
  val preDriverSwing = in Bits(3 bits) setName("TXBUFDIFFCTRL")
  /** @group ports */
  val driverSwing = in Bits(4 bits) setName("TXDIFFCTRL")
  /** @group ports */
  val deEmphasis = in Bool() setName("TXDEEMPH")

  /** @group ports */
  val mainCursor = in Bits(7 bits) setName("TXMAINCURSOR")
  /** @group ports */
  val margin = in Bits(3 bits) setName("TXMARGIN")

  /** @group ports */
  val preCursor = in Bits(5 bits) setName("TXPRECURSOR")
  /** @group ports */
  val preCursorInvert = in Bool() setName("TXPRECURSORINV")

  /** @group ports */
  val postCursor = in Bits(5 bits) setName("TXPOSTCURSOR")
  /** @group ports */
  val postCursorInvert = in Bool() setName("TXPOSTCURSORINV")

  /** @group ports */
  val output = out(new DiffPair())
  output.p.setName("GTPTXP")
  output.n.setName("GTPTXN")

  /** @group spiny */
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
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2TxPhaseInterpolatorIo() extends Bundle {
  /** @group ports */
  val powerDown = in Bool() setName("TXPIPPMPD")
  /** @group ports */
  val enable = in Bool() setName("TXPIPPMEN")
  /** @group ports */
  val overrideEn = in Bool() setName("TXPIPPMOVRDEN")
  /** @group ports */
  val stepSize = in Bits(5 bits) setName("TXPIPPMSTEPSIZE")

  /** @group spiny */
  def disable() = {
    powerDown := False
    enable := False
    overrideEn := False
    stepSize := B"5'0"
  }
}

/** FabricClockOutput ports on the transmit side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2TxFabricClockOutputIo() extends Bundle {
  /** @group ports */
  val outClkSelect = in Bits(3 bits) setName("TXOUTCLKSEL")
  /** @group ports */
  val outClk = out Bool() setName("TXOUTCLK")

  /** Takes TXOUTCLK from the PMA, the source a serial link wants
   *  @group spiny
   */
  def txOutClkPma(): Bool = {
    outClkSelect := B"3'010"
    outClk
  }

  /** @group spiny */
  def disable() = {
    outClkSelect := B"3'011"
    rate.disable()
  }

  /** @group ports */
  val rate = Gtpe2TxFabricClockOutputRateIo()
}

/** Polarity ports on the transmit side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2TxPolarityIo() extends Bundle {
  /** @group ports */
  val invert = in Bool() setName("TXPOLARITY")

  /** @group spiny */
  def disable() = {
    invert := False
  }
}

/** PatternGenerator ports on the transmit side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2TxPatternGeneratorIo() extends Bundle {
  /** @group ports */
  val prbsPatternSelect = in Bits(3 bits) setName("TXPRBSSEL")
  /** @group ports */
  val prbsForceErr = in Bool() setName("TXPRBSFORCEERR")

  /** @group spiny */
  def disable() = {
    prbsPatternSelect := B"3'0"
    prbsForceErr := False
  }
}

/** BufferBypass ports on the transmit side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2TxBufferBypassIo() extends Bundle {
  /** @group ports */
  val powerDown = in Bool() setName("TXPHDLYPD")
  /** @group ports */
  val reset = in Bool() setName("TXPHDLYRESET")

  /** @group spiny */
  def disable() = {
    powerDown := False
    reset := False

    phaseAlignment.disable()
    delayAlignment.disable()
  }

  /** @group ports */
  val phaseAlignment = Gtpe2TxBufferBypassPhaseAlignmentIo()

  /** @group ports */
  val delayAlignment = Gtpe2TxBufferBypassDelayAlignmentIo()
}

/** Buffer ports on the transmit side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2TxBufferIo() extends Bundle {
  /** @group ports */
  val status = out Bits(2 bits) setName("TXBUFSTATUS")
}

/** Gearbox ports on the transmit side
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2TxGearboxIo() extends Bundle {
  /** @group ports */
  val ready = out Bool() setName("TXGEARBOXREADY")
  /** @group ports */
  val header = in Bits(3 bits) setName("TXHEADER")
  /** @group ports */
  val sequence = in Bits(7 bits) setName("TXSEQUENCE")
  /** @group ports */
  val startSeq = in Bool() setName("TXSTARTSEQ")

  /** @group spiny */
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
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2TxEncoder8b10bIo() extends Bundle {
  /** @group ports */
  val enable = in Bool() setName("TX8B10BEN")
  /** @group ports */
  val bypass = in Bits(4 bits) setName("TX8B10BBYPASS")
  /** @group ports */
  val charDisparityMode = in Bits(4 bits) setName("TXCHARDISPMODE")
  /** @group ports */
  val charDisparityValue = in Bits(4 bits) setName("TXCHARDISPVAL")
  /** @group ports */
  val charIsK = in Bits(4 bits) setName("TXCHARISK")

  /** @group spiny */
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
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2TxClockingIo() extends Bundle {
  /** @group ports */
  val sysClkSelect = in Bits(2 bits) setName("TXSYSCLKSEL")
  /** @group ports */
  val usrClk = in Bool() setName("TXUSRCLK")
  /** @group ports */
  val usrClk2 = in Bool() setName("TXUSRCLK2")

  /** Drives TXUSRCLK and TXUSRCLK2 from the owner's domains
   *
   *  @param usrClkDomain Drives TXUSRCLK
   *  @param usrClk2Domain Drives TXUSRCLK2, the same domain at 20 bits
   *  @group spiny
   */
  def connectClocks(
    usrClkDomain: ClockDomain,
    usrClk2Domain: ClockDomain = null
  ): Unit = {
    usrClk := usrClkDomain.readClockWire
    usrClk2 := Option(usrClk2Domain).getOrElse(usrClkDomain).readClockWire
  }
  /** @group ports */
  val usrReady = in Bool() setName("TXUSERRDY")

  /** @group spiny */
  def staticSysClk(pmaClkPll: Int, txOutClkPll: Int) = {
    assert(
      (0 to 1).contains(pmaClkPll),
      "sysClkSelect must be PLL0 or PLL1"
    )
    assert(
      (0 to 1).contains(txOutClkPll),
      "sysClkSelect must be PLL0 or PLL1"
    )
    sysClkSelect(0) := Bool(pmaClkPll == 1)
    sysClkSelect(1) := Bool(txOutClkPll == 1)
  }

  /** @group spiny */
  def disable() = {
    sysClkSelect := B"2'0"
    usrClk := False
    usrClk2 := False
    usrReady := False
  }
}

/**
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2ChannelReservedIo() extends Bundle {
  /** @group ports */
  val gtRsvd = in Bits(16 bits) setName("GTRSVD") default(B"16'0")
  /** @group ports */
  val pcsRsvdIn = in Bits(16 bits) setName("PCSRSVDIN") default(B"16'0")
  /** @group ports */
  val tstIn = in Bits(20 bits) setName("TSTIN") default(B"20'hFFFFF")
  /** @group ports */
  val pmaRsvdOut0 = out Bool() setName("PMARSVDOUT0")
  /** @group ports */
  val pmaRsvdOut1 = out Bool() setName("PMARSVDOUT1")
  /** @group ports */
  val pmaRsvdIn0 = in Bool() setName("PMARSVDIN0") default(False)
  /** @group ports */
  val pmaRsvdIn1 = in Bool() setName("PMARSVDIN1") default(False)
  /** @group ports */
  val pmaRsvdIn2 = in Bool() setName("PMARSVDIN2") default(False)
  /** @group ports */
  val pmaRsvdIn3 = in Bool() setName("PMARSVDIN3") default(False)
  /** @group ports */
  val pmaRsvdIn4 = in Bool() setName("PMARSVDIN4") default(False)
  /** @group ports */
  val rxCdrReset = in Bool() setName("RXCDRRESET") default(False)
  /** @group ports */
  val rxCdrFreqReset = in Bool() setName("RXCDRFREQRESET") default(False)
  /** @group ports */
  val rxCdrOvrdEn = in Bool() setName("RXCDROVRDEN") default(False)
  /** @group ports */
  val rxCdrResetRsv = in Bool() setName("RXCDRRESETRSV") default(False)
  /** @group ports */
  val rxCdrLock = out Bool() setName("RXCDRLOCK")
  /** @group ports */
  val rxOsIntDone = out Bool() setName("RXOSINTDONE")
  /** @group ports */
  val rxOsIntStarted = out Bool() setName("RXOSINTSTARTED")
  /** @group ports */
  val rxOsIntStrobeDone = out Bool() setName("RXOSINTSTROBEDONE")
  /** @group ports */
  val rxOsIntStrobeStarted = out Bool() setName("RXOSINTSTROBESTARTED")
  /** @group ports */
  val rxOsCalReset = in Bool() setName("RXOSCALRESET") default(False)
  /** @group ports */
  val rxOsIntEn = in Bool() setName("RXOSINTEN") default(True)
  /** @group ports */
  val rxOsIntHold = in Bool() setName("RXOSINTHOLD") default(False)
  /** @group ports */
  val rxOsIntNtrLen = in Bool() setName("RXOSINTNTRLEN") default(False)
  /** @group ports */
  val rxOsIntOvrdEn = in Bool() setName("RXOSINTOVRDEN") default(False)
  /** @group ports */
  val rxOsIntPd = in Bool() setName("RXOSINTPD") default(False)
  /** @group ports */
  val rxOsIntStrobe = in Bool() setName("RXOSINTSTROBE") default(False)
  val rxOsIntTestOvrdEn =
    in Bool() setName("RXOSINTTESTOVRDEN") default(False)
  val rxOsIntCfg =
    in Bits(4 bits) setName("RXOSINTCFG") default(B"4'b0010")
  /** @group ports */
  val rxOsIntID0 = in Bits(4 bits) setName("RXOSINTID0") default(B"4'0")
  val rxLpmOsIntNtrLen =
    in Bool() setName("RXLPMOSINTNTRLEN") default(False)
  /** @group ports */
  val rxOutClkFabric = out Bool() setName("RXOUTCLKFABRIC")
  /** @group ports */
  val rxOutClkPcs = out Bool() setName("RXOUTCLKPCS")
  /** @group ports */
  val dMonFifoReset = in Bool() setName("DMONFIFORESET") default(False)
  /** @group ports */
  val pcsRsvdOut = out Bits(16 bits) setName("PCSRSVDOUT")
  /** @group ports */
  val clkRsvd0 = in Bool() setName("CLKRSVD0") default(False)
  /** @group ports */
  val clkRsvd1 = in Bool() setName("CLKRSVD1") default(False)
  /** @group ports */
  val resetOvrd = in Bool() setName("RESETOVRD") default(False)
  /** @group ports */
  val rxDfeXYDEn = in Bool() setName("RXDFEXYDEN") default(False)
  val rxAdaptSelTest =
    in Bits(14 bits) setName("RXADAPTSELTEST") default(B"14'0")
  /** @group ports */
  val setErrStatus = in Bool() setName("SETERRSTATUS") default(False)
  /** @group ports */
  val txSyncMode = in Bool() setName("TXSYNCMODE") default(False)
  /** @group ports */
  val txSyncIn = in Bool() setName("TXSYNCIN") default(False)
  /** @group ports */
  val txSyncOut = out Bool() setName("TXSYNCOUT")
  /** @group ports */
  val txSyncAllIn = in Bool() setName("TXSYNCALLIN") default(False)
  /** @group ports */
  val txSyncDone = out Bool() setName("TXSYNCDONE")
  /** @group ports */
  val txOutClkFabric = out Bool() setName("TXOUTCLKFABRIC")
  /** @group ports */
  val txOutClkPcs = out Bool() setName("TXOUTCLKPCS")
  /** @group ports */
  val txPiPpmSel = in Bool() setName("TXPIPPMSEL") default(True)
  /** @group ports */
  val txPiSoPd = in Bool() setName("TXPISOPD") default(False)
  /** @group ports */
  val txDiffPd = in Bool() setName("TXDIFFPD") default(False)
  /** @group ports */
  val cfgReset = in Bool() setName("CFGRESET") default(False)
}

/** Digital monitor output, selected by DMONITOR_CFG
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2DigitalMonitorIo() extends Bundle {
  /** @group ports */
  val clk = in Bool() setName("DMONITORCLK")
  /** @group ports */
  val output = out Bits(15 bits) setName("DMONITOROUT")

  /** @group spiny */
  def disable() = {
    clk := False
  }
}

/** Loopback mode select
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2LoopbackIo() extends Bundle {
  /** @group ports */
  val mode = in Bits(3 bits) setName("LOOPBACK")

  /** @group spiny */
  def disable() = {
    mode := B"3'0"
  }
}

/** Transmit half of a GTPE2_CHANNEL
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2TxIo(config: Gtpe2TxConfig) extends Bundle {
  /** @group ports */
  val powerDown = in Bits(2 bits) setName("TXPD")

  /** @group ports */
  val reset = in Bool() setName("GTTXRESET")
  /** @group ports */
  val resetDone = out Bool() setName("TXRESETDONE")

  /** @group ports */
  val pmaReset = in Bool() setName("TXPMARESET")
  /** @group ports */
  val pmaResetDone = out Bool() setName("TXPMARESETDONE")

  /** @group ports */
  val pcsReset = in Bool() setName("TXPCSRESET")

  /** @group ports */
  val rawData = in Bits(32 bits) setName("TXDATA")

  /** Data port based on config dataWidth and 8b10b encoding
   *
   *  This helper assumes 8b10b encoding is statically on or off. It automatically
   *  handles wiring TXCHARDISPMODE and TXCHARDISPVAL if needed.
   *  @group spiny
   */
  def data(bypass8b10b: Boolean = false): Vec[Bits] = {
    val format = Gtpe2SymbolFormat.of("tx", config.dataWidth, bypass8b10b)
    val port = Vec(Bits(format.width bits), format.count)

    // per UG482 Table 3-2
    rawData := Cat(port.map(_(7 downto 0))).resized
    if (format.width == 10) {
      encoder8b10b.charDisparityValue := Cat(port.map(_(8))).resized
      encoder8b10b.charDisparityMode := Cat(port.map(_(9))).resized
    } else {
      encoder8b10b.charDisparityValue := B(0, 4 bits)
      encoder8b10b.charDisparityMode := B(0, 4 bits)
    }
    port
  }

  /** @group spiny */
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

  /** @group ports */
  val clocking = Gtpe2TxClockingIo()

  /** @group ports */
  val encoder8b10b = Gtpe2TxEncoder8b10bIo()

  /** @group ports */
  val gearbox = Gtpe2TxGearboxIo()

  /** @group ports */
  val buffer = Gtpe2TxBufferIo()

  /** @group ports */
  val bufferBypass = Gtpe2TxBufferBypassIo()

  /** @group ports */
  val patternGenerator = Gtpe2TxPatternGeneratorIo()

  /** @group ports */
  val polarity = Gtpe2TxPolarityIo()

  /** @group ports */
  val fabricClockOutput = Gtpe2TxFabricClockOutputIo()

  /** @group ports */
  val phaseInterpolator = Gtpe2TxPhaseInterpolatorIo()

  /** @group ports */
  val driver = Gtpe2TxDriverIo()

  /** @group ports */
  val pcie = Gtpe2TxPcieIo()

  /** @group ports */
  val outOfBand = Gtpe2TxOutOfBandIo()

  /** The domain TXUSRCLK2 puts the synchronous pins in, taken from the pin
   *  itself so the tag follows whatever the owner of this half wires up
   */
  val usrClk2Domain = ClockDomain(clocking.usrClk2)

  // The TXUSRCLK2 ports per UG482. Anything not listed here is asynchronous.
  ClockDomainTag(usrClk2Domain)(
    rawData,
    resetDone,
    powerDown,

    encoder8b10b.enable,
    encoder8b10b.bypass,
    encoder8b10b.charDisparityMode,
    encoder8b10b.charDisparityValue,
    encoder8b10b.charIsK,

    gearbox.ready,
    gearbox.header,
    gearbox.sequence,
    gearbox.startSeq,

    buffer.status,

    patternGenerator.prbsPatternSelect,
    patternGenerator.prbsForceErr,

    polarity.invert,

    fabricClockOutput.rate.done,

    phaseInterpolator.enable,
    phaseInterpolator.overrideEn,
    phaseInterpolator.stepSize,

    driver.deEmphasis,
    driver.electricalIdle,
    driver.inhibit,

    pcie.detectReceiver,

    outOfBand.comInit,
    outOfBand.comSas,
    outOfBand.comWake,
    outOfBand.comFinish,
    outOfBand.electricalIdleMode
  )
}

/** Receive half of a GTPE2_CHANNEL
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2RxIo(config: Gtpe2RxConfig) extends Bundle {
  /** @group ports */
  val powerDown = in Bits(2 bits) setName("RXPD")

  /** @group ports */
  val reset = in Bool() setName("GTRXRESET")
  /** @group ports */
  val resetDone = out Bool() setName("RXRESETDONE")

  /** @group ports */
  val pmaReset = in Bool() setName("RXPMARESET")
  /** @group ports */
  val pmaResetDone = out Bool() setName("RXPMARESETDONE")

  /** @group ports */
  val pcsReset = in Bool() setName("RXPCSRESET")

  /** @group spiny */
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

  /** @group ports */
  val clocking = Gtpe2RxClockingIo()

  /** @group ports */
  val analogFrontEnd = Gtpe2RxAnalogFrontEndIo()

  /** @group ports */
  val outOfBand = Gtpe2RxOutOfBandIo()

  /** @group ports */
  val equalizer = Gtpe2RxEqualizerIo()

  /** @group ports */
  val clockDataRecovery = Gtpe2RxClockDataRecoveryIo()

  /** @group ports */
  val fabricClockOutput = Gtpe2RxFabricClockOutputIo()

  /** @group ports */
  val marginAnalysis = Gtpe2RxMarginAnalysisIo()

  /** @group ports */
  val polarity = Gtpe2RxPolarityIo()

  /** @group ports */
  val patternChecker = Gtpe2RxPatternCheckerIo()

  // Byte alignment
  /** @group ports */
  val byteAlignment = Gtpe2RxByteAlignmentIo()

  // Comma alignment
  /** @group ports */
  val commaAlignment = Gtpe2RxCommaAlignmentIo()

  // 8b/10b decoder (not TMDS compatible)
  /** @group ports */
  val decoder8b10b = Gtpe2RxDecoder8b10bIo()

  /** @group ports */
  val bufferBypass = Gtpe2RxBufferBypassIo()

  /** @group ports */
  val elasticBuffer = Gtpe2RxElasticBufferIo()

  /** @group ports */
  val clockCorrection = Gtpe2RxClockCorrectionIo()

  /** @group ports */
  val channelBonding = Gtpe2RxChannelBondingIo()

  /** @group ports */
  val gearbox = Gtpe2RxGearboxIo()

  /** @group ports */
  val pcie = Gtpe2RxPcieIo()

  /** @group ports */
  val rawData = out Bits(32 bits) setName("RXDATA")

  /** Data port based on config dataWidth and 8b10b encoding
   *
   *  This helper assumes 8b10b encoding is statically on or off. It automatically
   *  handles wiring RXDISPERR and RXCHARISK if needed.
   *  @group spiny
   */
  def data(bypass8b10b: Boolean = false): Vec[Bits] = {
    val format = Gtpe2SymbolFormat.of("rx", config.dataWidth, bypass8b10b)
    // per UG482 Table 3-2
    Vec((0 until format.count).map { i =>
      val character = rawData(8 * i + 7 downto 8 * i)
      if (format.width == 10) {
        decoder8b10b.disparityErr(i) ## decoder8b10b.charIsK(i) ## character
      } else {
        character
      }
    })
  }

  val usrClkDomain = ClockDomain(clocking.usrClk)
  val usrClk2Domain = ClockDomain(clocking.usrClk2)

  // The RXUSRCLK ports per UG482. Anything not listed here is asynchronous.
  ClockDomainTag(usrClkDomain)(
    channelBonding.output,
    channelBonding.input
  )

  // The RXUSRCLK2 ports per UG482. Anything not listed here is asynchronous.
  ClockDomainTag(usrClk2Domain)(
    rawData,
    resetDone,

    decoder8b10b.enable,
    decoder8b10b.charIsComma,
    decoder8b10b.charIsK,
    decoder8b10b.disparityErr,
    decoder8b10b.notInTable,

    commaAlignment.detectEnable,
    commaAlignment.detect,
    commaAlignment.mCommaEnable,
    commaAlignment.pCommaEnable,
    commaAlignment.slide,

    byteAlignment.isAligned,
    byteAlignment.realign,

    elasticBuffer.status,
    clockCorrection.status,

    channelBonding.enable,
    channelBonding.master,
    channelBonding.slave,
    channelBonding.seqDetected,
    channelBonding.isAligned,
    channelBonding.realign,
    channelBonding.level,

    gearbox.slip,
    gearbox.dataValid,
    gearbox.headerValid,
    gearbox.header,
    gearbox.startOfSeq,

    patternChecker.prbsErrCounterReset,
    patternChecker.prbsPatternSelect,
    patternChecker.prbsErr,

    marginAnalysis.trigger,

    fabricClockOutput.rate.done,

    pcie.valid,
    pcie.phyStatus,
    pcie.status,

    outOfBand.comInitDetect,
    outOfBand.comSasDetect,
    outOfBand.comWakeDetect,
    outOfBand.electricalIdle
  )
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
  /** @group ports */
  val resetSelection = in Bool() setName("GTRESETSEL")

  /** @group ports */
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
  /** @group ports */
  val clocking = in(Gtpe2ChannelClocking())
  clocking.pll0Clk.setName("PLL0CLK")
  clocking.pll0RefClk.setName("PLL0REFCLK")
  clocking.pll1Clk.setName("PLL1CLK")
  clocking.pll1RefClk.setName("PLL1REFCLK")

  /** @group ports */
  val rx = Gtpe2RxIo(rxConfig)

  /** @group ports */
  val tx = Gtpe2TxIo(txConfig)

  /** @group ports */
  val loopback = Gtpe2LoopbackIo()

  /** @group ports */
  val digitalMonitor = Gtpe2DigitalMonitorIo()

  /** @group ports */
  val reserved = Gtpe2ChannelReservedIo()
}

/** GTPE2_CHANNEL primitive: one transceiver lane
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class Gtpe2Channel(
  rxConfig: Gtpe2RxConfig,
  txConfig: Gtpe2TxConfig,
  drpClkDomain: ClockDomain = null,
  simResetSpeedup: Boolean = false
) extends BlackBox {
  val generic = new Generic {
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
    val RX_CLK25_DIV = rxConfig.clk25Div
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
    val TXBUF_EN = txConfig.bufEnable
    val TXBUF_RESET_ON_RATE_CHANGE = "TRUE"
    val TXDLY_CFG = B"16'h001F"
    val TXDLY_LCFG = B"9'h030"
    val TXDLY_TAP_CFG = B"16'h0000"
    val TXPH_CFG = B"16'h0780"
    val TXPHDLY_CFG = B"24'h084020"
    val TXPH_MONITOR_SEL = B"5'b00000"
    val TX_XCLK_SEL = txConfig.xclkSelect

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
    val TX_CLK25_DIV = txConfig.clk25Div
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
    val TXSYNC_OVRD = B(if (txConfig.syncOverride) 1 else 0, 1 bits)
    val TXSYNC_SKIP_DA = B"1'b0"
  }

  /** @group ports */
  val io = Gtpe2ChannelIo(rxConfig, txConfig, drpClkDomain)

  if (drpClkDomain != null) {
    mapClockDomain(drpClkDomain, io.drp.clk)
  }

  // RXUSRCLK and TXUSRCLK are driven by whoever owns that half, through
  // connectClocks on its clocking bundle.

  noIoPrefix()
  setBlackBoxName("GTPE2_CHANNEL")
}
