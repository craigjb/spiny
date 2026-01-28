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

package spiny.dram

import spinal.core._
import spinal.lib._

/**
 * SDR physical interface
 */
case class SdrDramIo(
  addressWidth: Int,
  bankAddressWidth: Int,
  dataWidth: Int
) extends Bundle with IMasterSlave {
  val a = Bits(addressWidth bits).setCompositeName(this, "a")
  val ba = Bits(bankAddressWidth bits).setCompositeName(this, "ba")
  val rasN = Bool().setCompositeName(this, "rasN")
  val casN = Bool().setCompositeName(this, "casN")
  val weN = Bool().setCompositeName(this, "weN")
  val csN = Bool().setCompositeName(this, "csN")
  val dm = Bits(dataWidth / 8 bits).setCompositeName(this, "dm")
  val dq = inout(Analog(Bits(dataWidth bits))).setCompositeName(this, "dq")
  val cke = Bool().setCompositeName(this, "cke")

  override def asMaster(): Unit = {
    out(a, ba, rasN, casN, weN, csN, dm, cke)
  }

  /**
   * Set signal names to match LiteDRAM's generated Verilog
   */
  def setLiteDramNames(): this.type = {
    a.setName("sdram_a")
    ba.setName("sdram_ba")
    rasN.setName("sdram_ras_n")
    casN.setName("sdram_cas_n")
    weN.setName("sdram_we_n")
    csN.setName("sdram_cs_n")
    dm.setName("sdram_dm")
    dq.setName("sdram_dq")
    cke.setName("sdram_cke")
    this
  }
}

/**
 * DDR2 physical interface
 */
case class Ddr2Io(
  addressWidth: Int,
  bankAddressWidth: Int,
  dataWidth: Int,
  numRanks: Int
) extends Bundle with IMasterSlave {
  val a = Bits(addressWidth bits).setCompositeName(this, "a")
  val ba = Bits(bankAddressWidth bits).setCompositeName(this, "ba")
  val rasN = Bool().setCompositeName(this, "rasN")
  val casN = Bool().setCompositeName(this, "casN")
  val weN = Bool().setCompositeName(this, "weN")
  val csN = Bits(numRanks bits).setCompositeName(this, "csN")
  val dm = Bits(dataWidth / 8 bits).setCompositeName(this, "dm")
  val dq = inout(Analog(Bits(dataWidth bits))).setCompositeName(this, "dq")
  val dqsP = inout(Analog(Bits(dataWidth / 8 bits))).setCompositeName(this, "dqsP")
  val dqsN = inout(Analog(Bits(dataWidth / 8 bits))).setCompositeName(this, "dqsN")
  val clkP = Bits(numRanks bits).setCompositeName(this, "clkP")
  val clkN = Bits(numRanks bits).setCompositeName(this, "clkN")
  val cke = Bits(numRanks bits).setCompositeName(this, "cke")
  val odt = Bits(numRanks bits).setCompositeName(this, "odt")

  override def asMaster(): Unit = {
    out(a, ba, rasN, casN, weN, csN, dm, clkP, clkN, cke, odt)
  }

  /**
   * Set signal names to match LiteDRAM's generated Verilog
   */
  def setLiteDramNames(): this.type = {
    a.setName("ddram_a")
    ba.setName("ddram_ba")
    rasN.setName("ddram_ras_n")
    casN.setName("ddram_cas_n")
    weN.setName("ddram_we_n")
    csN.setName("ddram_cs_n")
    dm.setName("ddram_dm")
    dq.setName("ddram_dq")
    dqsP.setName("ddram_dqs_p")
    dqsN.setName("ddram_dqs_n")
    clkP.setName("ddram_clk_p")
    clkN.setName("ddram_clk_n")
    cke.setName("ddram_cke")
    odt.setName("ddram_odt")
    this
  }
}

/**
 * DDR3 physical interface
 */
case class Ddr3Io(
  addressWidth: Int,
  bankAddressWidth: Int,
  dataWidth: Int,
  numRanks: Int
) extends Bundle with IMasterSlave {
  val a = Bits(addressWidth bits).setCompositeName(this, "a")
  val ba = Bits(bankAddressWidth bits).setCompositeName(this, "ba")
  val rasN = Bool().setCompositeName(this, "rasN")
  val casN = Bool().setCompositeName(this, "casN")
  val weN = Bool().setCompositeName(this, "weN")
  val csN = Bits(numRanks bits).setCompositeName(this, "csN")
  val dm = Bits(dataWidth / 8 bits).setCompositeName(this, "dm")
  val dq = inout(Analog(Bits(dataWidth bits))).setCompositeName(this, "dq")
  val dqsP = inout(Analog(Bits(dataWidth / 8 bits))).setCompositeName(this, "dqsP")
  val dqsN = inout(Analog(Bits(dataWidth / 8 bits))).setCompositeName(this, "dqsN")
  val clkP = Bits(numRanks bits).setCompositeName(this, "clkP")
  val clkN = Bits(numRanks bits).setCompositeName(this, "clkN")
  val cke = Bits(numRanks bits).setCompositeName(this, "cke")
  val odt = Bits(numRanks bits).setCompositeName(this, "odt")
  val resetN = Bool().setCompositeName(this, "resetN")

  override def asMaster(): Unit = {
    out(a, ba, rasN, casN, weN, csN, dm, clkP, clkN, cke, odt, resetN)
  }

  /**
   * Set signal names to match LiteDRAM's generated Verilog
   */
  def setLiteDramNames(): this.type = {
    a.setName("ddram_a")
    ba.setName("ddram_ba")
    rasN.setName("ddram_ras_n")
    casN.setName("ddram_cas_n")
    weN.setName("ddram_we_n")
    csN.setName("ddram_cs_n")
    dm.setName("ddram_dm")
    dq.setName("ddram_dq")
    dqsP.setName("ddram_dqs_p")
    dqsN.setName("ddram_dqs_n")
    clkP.setName("ddram_clk_p")
    clkN.setName("ddram_clk_n")
    cke.setName("ddram_cke")
    odt.setName("ddram_odt")
    resetN.setName("ddram_reset_n")
    this
  }
}

/**
 * DDR4 physical interface
 */
case class Ddr4Io(
  addressWidth: Int,
  bankAddressWidth: Int,
  bankGroupWidth: Int,
  dataWidth: Int,
  numRanks: Int
) extends Bundle with IMasterSlave {
  val a = Bits(addressWidth bits).setCompositeName(this, "a")
  val ba = Bits(bankAddressWidth bits).setCompositeName(this, "ba")
  val bg = Bits(bankGroupWidth bits).setCompositeName(this, "bg")
  val rasN = Bool().setCompositeName(this, "rasN")
  val casN = Bool().setCompositeName(this, "casN")
  val weN = Bool().setCompositeName(this, "weN")
  val csN = Bits(numRanks bits).setCompositeName(this, "csN")
  val actN = Bool().setCompositeName(this, "actN")
  val dm = Bits(dataWidth / 8 bits).setCompositeName(this, "dm")
  val dq = inout(Analog(Bits(dataWidth bits))).setCompositeName(this, "dq")
  val dqsP = inout(Analog(Bits(dataWidth / 8 bits))).setCompositeName(this, "dqsP")
  val dqsN = inout(Analog(Bits(dataWidth / 8 bits))).setCompositeName(this, "dqsN")
  val clkP = Bits(numRanks bits).setCompositeName(this, "clkP")
  val clkN = Bits(numRanks bits).setCompositeName(this, "clkN")
  val cke = Bits(numRanks bits).setCompositeName(this, "cke")
  val odt = Bits(numRanks bits).setCompositeName(this, "odt")
  val resetN = Bool().setCompositeName(this, "resetN")

  override def asMaster(): Unit = {
    out(a, ba, bg, rasN, casN, weN, csN, actN, dm, clkP, clkN, cke, odt, resetN)
  }

  /**
   * Set signal names to match LiteDRAM's generated Verilog
   */
  def setLiteDramNames(): this.type = {
    a.setName("ddram_a")
    ba.setName("ddram_ba")
    bg.setName("ddram_bg")
    rasN.setName("ddram_ras_n")
    casN.setName("ddram_cas_n")
    weN.setName("ddram_we_n")
    csN.setName("ddram_cs_n")
    actN.setName("ddram_act_n")
    dm.setName("ddram_dm")
    dq.setName("ddram_dq")
    dqsP.setName("ddram_dqs_p")
    dqsN.setName("ddram_dqs_n")
    clkP.setName("ddram_clk_p")
    clkN.setName("ddram_clk_n")
    cke.setName("ddram_cke")
    odt.setName("ddram_odt")
    resetN.setName("ddram_reset_n")
    this
  }
}
