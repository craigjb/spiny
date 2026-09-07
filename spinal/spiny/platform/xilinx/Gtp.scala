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

import scala.collection.mutable

import spinal.core._
import spinal.lib._

import spiny.platform.xilinx.blackbox._

/** A PLL handed out by a [[GtpCommon]]
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class GtpPllIo(index: Int) extends Bundle with IMasterSlave {
  assert((0 to 1).contains(index), s"a GTPE2_COMMON has PLL 0 and 1, not $index")

  /** PLL output clock, feeds a channel's clock select
   *  @group ports
   */
  val outClk = Bool()

  /** Reference clock passed through to the channels
   *  @group ports
   */
  val outRefClk = Bool()

  /** High once the PLL has locked to its reference
   *  @group ports
   */
  val lock = Bool()

  /** High when the reference clock has stopped
   *  @group ports
   */
  val refClkLost = Bool()

  /** High when the feedback clock has stopped
   *  @group ports
   */
  val fbClkLost = Bool()

  /** Resets the PLL, needed after changing dividers over DRP
   *  @group ports
   */
  val reset = Bool()

  /** Powers the PLL down
   *  @group ports
   */
  val powerDown = Bool()

  /** Optional fabric clock for lock detection
   *  @group ports
   */
  val lockDetectClk = Bool()

  /** Which reference clock to use, see [[Gtpe2PllRefClk]]
   *  @group ports
   */
  val refClkSelect = Bits(3 bits)

  override def asMaster(): Unit = {
    out(outClk, outRefClk, lock, refClkLost, fbClkLost)
    in(reset, powerDown, lockDetectClk, refClkSelect)
  }

  /** Drives the control inputs to their idle values
   *  @group spiny
   */
  def tieOff(refClk: Gtpe2PllRefClk = Gtpe2PllRefClk.GtRefClk0): Unit = {
    reset := False
    powerDown := False
    lockDetectClk := False
    refClkSelect := refClk.asBits
  }
}

/** Allocates the two PLLs of a GTPE2_COMMON to consumers
 *
 *  Consumers claim a PLL with requestPll and wire to the returned port.
 *  The primitive is instantiated once the enclosing component has elaborated,
 *  so build() only has to be called by hand if this is the toplevel.
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 * @groupname spiny Spiny
 * @groupprio spiny 1
 */
case class GtpCommon() extends Component {
  val io = new Bundle {
    /** This quad's reference clock 0, from an IBUFDS_GTE2 with no BUFG
     *  @group ports
     */
    val gtRefClk0 = in Bool() default(False)

    /** This quad's reference clock 1, from an IBUFDS_GTE2 with no BUFG
     *  @group ports
     */
    val gtRefClk1 = in Bool() default(False)

    /** Reference clock 0 routed from the quad to the east
     *  @group ports
     */
    val gtEastRefClk0 = in Bool() default(False)

    /** Reference clock 1 routed from the quad to the east
     *  @group ports
     */
    val gtEastRefClk1 = in Bool() default(False)

    /** Reference clock 0 routed from the quad to the west
     *  @group ports
     */
    val gtWestRefClk0 = in Bool() default(False)

    /** Reference clock 1 routed from the quad to the west
     *  @group ports
     */
    val gtWestRefClk1 = in Bool() default(False)
  }

  private val claims = mutable.ArrayBuffer[(Gtpe2PllConfig, GtpPllIo)]()
  private var built = false

  // The claims are only all known once the enclosing component has
  // finished elaborating, so the build waits for the parent.
  if (parent != null) parent.addPrePopTask(() => if (!built) build())

  /** Claims the next free PLL
   *  @group spiny
   */
  def requestPll(config: Gtpe2PllConfig): GtpPllIo = {
    assert(!built,
      "Cannot claim a PLL after the GTPE2_COMMON is built. The build runs " +
        "when the enclosing component finishes elaborating, so a claim from " +
        "a prePopTask or afterElaboration block is too late.")
    assert(claims.size < 2, "A GTPE2_COMMON has only two PLLs")

    val slot = claims.size
    val port = rework {
      master(GtpPllIo(slot)).setName(s"pll_$slot")
    }
    claims += ((config, port))
    port
  }

  /** Instantiates the primitive with whatever was claimed
   *
   *  Runs automatically at the end of the enclosing component.
   *  Calling explicitly is allowed.
   *  @group spiny
   */
  def build(): Unit = rework {
    assert(!built, "build() already called")
    assert(claims.nonEmpty, "A GtpCommon was created but no PLL was claimed")
    built = true

    claims.zipWithIndex.foreach { case ((config, _), i) =>
      println(
        s"[GtpCommon] PLL$i refClkDiv=${config.refClkDiv} " +
          s"fbDiv=${config.fbDiv} fbDiv45=${config.fbDiv45}"
      )
    }

    val dividers = Array.fill(2)(Gtpe2PllConfig.default())
    claims.zipWithIndex.foreach { case ((config, _), i) => dividers(i) = config }
    val common = Gtpe2Common(dividers(0), dividers(1))

    common.io.clocking.gtRefClk0 := io.gtRefClk0
    common.io.clocking.gtRefClk1 := io.gtRefClk1
    common.io.clocking.gtEastRefClk0 := io.gtEastRefClk0
    common.io.clocking.gtEastRefClk1 := io.gtEastRefClk1
    common.io.clocking.gtWestRefClk0 := io.gtWestRefClk0
    common.io.clocking.gtWestRefClk1 := io.gtWestRefClk1
    // TODO: arbitrate between PLL bundles
    common.io.drp.disable()

    for (i <- 0 to 1) {
      val primitive = if (i == 0) common.io.pll0 else common.io.pll1
      if (i < claims.size) {
        connect(claims(i)._2, primitive)
      } else {
        primitive.disable()
      }
    }
  }

  private def connect(port: GtpPllIo, primitive: Gtpe2PllIo): Unit = {
    port.outClk := primitive.outClk
    port.outRefClk := primitive.outRefClk
    port.lock := primitive.lock
    port.refClkLost := primitive.refClkLost
    port.fbClkLost := primitive.fbClkLost
    primitive.reset := port.reset
    primitive.powerDown := port.powerDown
    primitive.lockDetectClk := port.lockDetectClk
    primitive.refClkSelect := port.refClkSelect
  }
}
