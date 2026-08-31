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
import spinal.lib.blackbox.xilinx.s7.BUFG

import spiny.DiffPair

object IBufDsGte2 {
  /** Buffers a differential reference clock for a transceiver
   *
   *  clkOut gets the BUFG'd copy for fabric use. The returned buffer's raw O
   *  is what feeds GTREFCLK, which must not go through a BUFG.
   */
  def apply(clkIn: DiffPair, clkOut: Bool, enable: Bool) = {
    val buf = new IBufDsGte2()
    buf.io.I := clkIn.p
    buf.io.IB := clkIn.n
    buf.io.CEB := !enable
    clkOut := BUFG.on(buf.io.O)
    buf
  }
}

/** IBUFDS_GTE2 primitive: differential input buffer for a reference clock */
case class IBufDsGte2() extends BlackBox {
  val io = new Bundle {
    val I = in Bool()
    val IB = in Bool()
    val CEB = in Bool()
    val O = out Bool()
    val ODIV2 = out Bool()
  }

  noIoPrefix()
  setBlackBoxName("IBUFDS_GTE2")
}

/** A PLLxREFCLKSEL encoding
 *
 *  Plain data rather than a Bits, so a config holding one can be built
 *  outside an elaboration context. Use asBits to get the hardware literal.
 */
case class Gtpe2PllRefClk(code: Int) {
  assert((1 to 6).contains(code), s"refClkSelect code must be 1-6, was $code")

  def asBits: Bits = B(code, 3 bits)
}

object Gtpe2PllRefClk {
  val GtRefClk0 = Gtpe2PllRefClk(1)
  val GtRefClk1 = Gtpe2PllRefClk(2)
  val GtEastRefClk0 = Gtpe2PllRefClk(3)
  val GtEastRefClk1 = Gtpe2PllRefClk(4)
  val GtWestRefClk0 = Gtpe2PllRefClk(5)
  val GtWestRefClk1 = Gtpe2PllRefClk(6)
}

/** One of the two PLLs in a GTPE2_COMMON
 *
 *  Defined once and instantiated twice, with forPllIndex renaming each copy to
 *  the primitive's PLL0 or PLL1 port names.
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2PllIo() extends Bundle {
  /** PLL output clock, feeds a channel's TX or RX clock select
   *  @group ports
   */
  val outClk = out Bool()

  /** Reference clock passed through to the channels
   *  @group ports
   */
  val outRefClk = out Bool()

  /** High once the PLL has locked to its reference
   *  @group ports
   */
  val lock = out Bool()

  /** Enables lock detection, which is normally always wanted
   *  @group ports
   */
  val lockEnable = in Bool() default(True)

  /** Powers the PLL down
   *  @group ports
   */
  val powerDown = in Bool()

  /** Which reference clock to use, see [[Gtpe2PllRefClk]]
   *
   *  A runtime port, so a PLL can change reference clock dynamically
   *  @group ports
   */
  val refClkSelect = in Bits(3 bits)

  /** Resets the PLL, needed after changing dividers over DRP
   *  @group ports
   */
  val reset = in Bool()

  /** Optional fabric clock for lock detection
   *  @group ports
   */
  val lockDetectClk = in Bool()

  /** High when the reference clock has stopped
   *  @group ports
   */
  val refClkLost = out Bool()

  /** High when the feedback clock has stopped
   *  @group ports
   */
  val fbClkLost = out Bool()

  /** Renames every port to its PLL0 or PLL1 primitive name */
  def forPllIndex(i: Int) = {
    assert((0 to 1).contains(i), "Must be PLL0 or PLL1")
    outClk.setName(f"PLL${i}OUTCLK")
    outRefClk.setName(f"PLL${i}OUTREFCLK")
    lock.setName(f"PLL${i}LOCK")
    lockEnable.setName(f"PLL${i}LOCKEN")
    powerDown.setName(f"PLL${i}PD")
    refClkSelect.setName(f"PLL${i}REFCLKSEL")
    reset.setName(f"PLL${i}RESET")
    lockDetectClk.setName(f"PLL${i}LOCKDETCLK")
    refClkLost.setName(f"PLL${i}REFCLKLOST")
    fbClkLost.setName(f"PLL${i}FBCLKLOST")
    this
  }

  /** Powers down an unused PLL and ties off its inputs */
  def disable() = {
    powerDown := True
    refClkSelect := Gtpe2PllRefClk.GtRefClk0.asBits
    reset := False
    lockDetectClk := False
  }
}

object Gtpe2PllConfig {
  def default() = Gtpe2PllConfig(refClkDiv = 1, fbDiv = 1, fbDiv45 = 4)
}

/** Raw divider settings for one PLL
 *
 *  f_VCO = refClk / refClkDiv * fbDiv * fbDiv45
 */
case class Gtpe2PllConfig(
  refClkDiv: Int,
  fbDiv: Int,
  fbDiv45: Int,
  simRefClkSelect: Gtpe2PllRefClk = Gtpe2PllRefClk.GtRefClk0
) {
  assert((1 to 2).contains(refClkDiv), "refClkDiv must be 1 or 2")
  assert((1 to 5).contains(fbDiv), "fbDiv must be within 1-5")
  assert((4 to 5).contains(fbDiv45), "fbDiv45 must be 4 or 5")
}

/** Dynamic reconfiguration port
 *
 *  One per GTPE2_COMMON, shared by both PLLs, so a design that hands the PLLs
 *  to different owners has to arbitrate this. The channel's DRP is the same
 *  shape with a wider address.
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2DrpIo(addressWidth: Int = 8)
  extends Bundle with IMasterSlave {
  /** DRP clock
   *  @group ports
   */
  val clk = in Bool() setName("DRPCLK")

  /** Register address to access
   *  @group ports
   */
  val addr = in UInt(addressWidth bits) setName("DRPADDR")

  /** Write data
   *  @group ports
   */
  val dataIn = in Bits(16 bits) setName("DRPDI")

  /** Read data, valid with ready
   *  @group ports
   */
  val dataOut = out Bits(16 bits) setName("DRPDO")

  /** Starts an access, held for one clock
   *  @group ports
   */
  val enable = in Bool() setName("DRPEN")

  /** High for a write, low for a read
   *  @group ports
   */
  val writeEnable = in Bool() setName("DRPWE")

  /** Pulses when the access completes
   *  @group ports
   */
  val ready = out Bool() setName("DRPRDY")

  override def asMaster(): Unit = {
    out(clk, addr, dataIn, enable, writeEnable)
    in(dataOut, ready)
  }

  /** Ties off the port when nothing reconfigures the primitive */
  def disable() = {
    clk := False
    addr := 0
    dataIn := B"16'0"
    enable := False
    writeEnable := False
  }
}

/** Reference clock inputs
 *
 *  Which one a PLL uses is chosen at runtime by its refClkSelect, not here.
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2ClockingIo() extends Bundle {
  /** Reference clock 0 shared from the quad to the east
   *  @group ports
   */
  val gtEastRefClk0 = in Bool() setName("GTEASTREFCLK0") default(False)

  /** Reference clock 1 shared from the quad to the east
   *  @group ports
   */
  val gtEastRefClk1 = in Bool() setName("GTEASTREFCLK1") default(False)

  /** Reference clock 0 shared from the quad to the west
   *  @group ports
   */
  val gtWestRefClk0 = in Bool() setName("GTWESTREFCLK0") default(False)

  /** Reference clock 1 shared from the quad to the west
   *  @group ports
   */
  val gtWestRefClk1 = in Bool() setName("GTWESTREFCLK1") default(False)

  /** This quad's own reference clock 0
   *
   *  Comes from an IBUFDS_GTE2 on a dedicated route, so it must not be passed
   *  through a BUFG
   *  @group ports
   */
  val gtRefClk0 = in Bool() setName("GTREFCLK0")

  /** This quad's own reference clock 1
   *  @group ports
   */
  val gtRefClk1 = in Bool() setName("GTREFCLK1")

  /** Fabric sourced reference clock 0, for test use only
   *  @group ports
   */
  val internalGtgRefClk0 = in Bool() setName("GTGREFCLK0") default(False)

  /** Fabric sourced reference clock 1, for test use only
   *  @group ports
   */
  val internalGtgRefClk1 = in Bool() setName("GTGREFCLK1") default(False)
}

/** Bandgap, calibration and reserved pins
 *
 *  Every input defaults to its recommended value, so none of these need
 *  driving
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2ReservedIo() extends Bundle {
  /** Bypasses the bandgap reference, active low
   *  @group ports
   */
  val bgBypassB = in Bool() setName("BGBYPASSB") default(True)

  /** Enables bandgap monitoring, active low
   *  @group ports
   */
  val bgMonitorEnB = in Bool() setName("BGMONITORENB") default(True)

  /** Powers down the bandgap, active low
   *  @group ports
   */
  val bgPDB = in Bool() setName("BGPDB") default(True)

  /** Overrides the resistor calibration value
   *  @group ports
   */
  val bgRCalOvrd = in Bits(5 bits) setName("BGRCALOVRD") default(B"11111")

  /** Enables the calibration override, active low
   *  @group ports
   */
  val bgRCalOvrdEnB = in Bool() setName("BGRCALOVRDENB") default(True)

  /** Enables resistor calibration, active low
   *  @group ports
   */
  val rCalEnB = in Bool() setName("RCALENB") default(True)

  /** Reserved
   *  @group ports
   */
  val pllRsvd1 = in Bits(16 bits) setName("PLLRSVD1") default(B"16'0")

  /** Reserved
   *  @group ports
   */
  val pllRsvd2 = in Bits(5 bits) setName("PLLRSVD2") default(B"5'0")

  /** Reserved
   *  @group ports
   */
  val pmaRsvd = in Bits(8 bits) setName("PMARSVD") default(B"8'0")

  /** Reserved
   *  @group ports
   */
  val pmaRsvdOut = out Bits(16 bits) setName("PMARSVDOUT")
}

/** GTPE2_COMMON IO ports
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class Gtpe2CommonIo() extends Bundle {
  /** Reconfiguration port, shared by both PLLs
   *  @group ports
   */
  val drp = Gtpe2DrpIo()

  /** Reference clock inputs
   *  @group ports
   */
  val clocking = Gtpe2ClockingIo()

  /** First of the two PLLs
   *  @group ports
   */
  val pll0 = Gtpe2PllIo().forPllIndex(0)

  /** Second of the two PLLs
   *  @group ports
   */
  val pll1 = Gtpe2PllIo().forPllIndex(1)

  /** Digital monitor output, selected by DMONITOR_CFG
   *  @group ports
   */
  val digitalMonitorOut = out Bits(8 bits) setName("DMONITOROUT")

  /** Divided copy of reference clock 0, for frequency measurement
   *  @group ports
   */
  val refClkOutMonitor0 = out Bool() setName("REFCLKOUTMONITOR0")

  /** Divided copy of reference clock 1, for frequency measurement
   *  @group ports
   */
  val refClkOutMonitor1 = out Bool() setName("REFCLKOUTMONITOR1")

  /** Bandgap, calibration and reserved pins
   *  @group ports
   */
  val reserved = Gtpe2ReservedIo()
}

/** GTPE2_COMMON primitive: two PLLs and one shared DRP port */
case class Gtpe2Common(
  pll0Config: Gtpe2PllConfig,
  pll1Config: Gtpe2PllConfig,
  simResetSpeedup: Boolean = false
) extends BlackBox {
  val generic = new Generic {
    // Simulation
    val SIM_RESET_SPEEDUP = if (simResetSpeedup) "TRUE" else "FALSE"
    val SIM_PLL0REFCLK_SEL = pll0Config.simRefClkSelect.asBits
    val SIM_PLL1REFCLK_SEL = pll1Config.simRefClkSelect.asBits
    val SIM_VERSION = "2.0"

    // Common configs. BIAS_CFG is the one attribute here that differs from the
    // primitive's own default(which is all zeros); the rest restate it.
    val BIAS_CFG = B"64'h0000000000050001"
    val COMMON_CFG = B"32'h00000000"

    // PLL0. Only the three dividers vary; the CFG/LOCK_CFG/INIT_CFG values are
    // undocumented and take the same value in every configuration Vivado emits.
    val PLL0_REFCLK_DIV = pll0Config.refClkDiv
    val PLL0_FBDIV = pll0Config.fbDiv
    val PLL0_FBDIV_45 = pll0Config.fbDiv45
    val PLL0_CFG = B"27'h01F03DC"
    val PLL0_LOCK_CFG = B"9'h1E8"
    val PLL0_INIT_CFG = B"24'h00001E"
    val PLL0_DMON_CFG = B"1'b0"

    // PLL1
    val PLL1_REFCLK_DIV = pll1Config.refClkDiv
    val PLL1_FBDIV = pll1Config.fbDiv
    val PLL1_FBDIV_45 = pll1Config.fbDiv45
    val PLL1_CFG = B"27'h01F03DC"
    val PLL1_LOCK_CFG = B"9'h1E8"
    val PLL1_INIT_CFG = B"24'h00001E"
    val PLL1_DMON_CFG = B"1'b0"

    // Reserved
    val PLL_CLKOUT_CFG = B"8'0"
    val RSVD_ATTR0 = B"16'0"
    val RSVD_ATTR1 = B"16'0"
  }

  val io = Gtpe2CommonIo()

  noIoPrefix()
  setBlackBoxName("GTPE2_COMMON")
}
