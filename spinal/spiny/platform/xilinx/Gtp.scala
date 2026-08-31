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

/** A reference clock a PLL can run from
 *
 *  select names one of the primitive's inputs, freq is what it actually runs
 *  at, which is what the divider solver needs.
 */
case class GtpRefClk(select: Gtpe2PllRefClk, freq: HertzNumber)

/** What a PLL should be set to */
sealed trait GtpPllConfig

object GtpPllConfig {
  /** Solve dividers for this VCO frequency */
  case class Vco(freq: HertzNumber) extends GtpPllConfig

  /** Use exactly these dividers, when a particular set behaves better */
  case class Dividers(refClkDiv: Int, fbDiv: Int, fbDiv45: Int)
    extends GtpPllConfig

  /** The VCO a line rate needs at a given channel output divider */
  def lineRate(rate: HertzNumber, outDiv: Int): Vco = {
    Vco(rate * BigDecimal(outDiv) / BigDecimal(2))
  }
}

object GtpPll {
  /** Finds dividers that hit a VCO frequency exactly
   *
   *  f_VCO = refClk / refClkDiv * fbDiv * fbDiv45, so there are only twenty
   *  combinations to try.
   */
  def solve(
    refClk: HertzNumber,
    vco: HertzNumber,
    vcoMin: HertzNumber = GtpCommon.VcoMin,
    vcoMax: HertzNumber = GtpCommon.VcoMax
  ): Option[Gtpe2PllConfig] = {
    if (vco < vcoMin || vco > vcoMax) {
      return None
    }
    // compared as products so the check stays exact, no division
    val target = vco.toBigDecimal
    val reference = refClk.toBigDecimal
    val candidates = for {
      refClkDiv <- Seq(1, 2)
      fbDiv <- 1 to 5
      fbDiv45 <- Seq(4, 5)
      if reference * BigDecimal(fbDiv * fbDiv45) ==
        target * BigDecimal(refClkDiv)
    } yield Gtpe2PllConfig(refClkDiv, fbDiv, fbDiv45)
    candidates.headOption
  }
}

/** One of the two PLLs, claimed from a [[GtpCommon]]
 *
 *  Index and dividers are only resolved once the common is built.
 */
class GtpPll(
  val refClk: GtpRefClk,
  val config: GtpPllConfig,
  val requestedIndex: Option[Int],
  val io: GtpPllIo
) {
  private var assignedIndex = -1
  private var assignedDividers: Gtpe2PllConfig = null

  /** Which of the two PLLs this ended up on */
  def index: Int = {
    assert(assignedIndex >= 0, "PLL index is only known after build()")
    assignedIndex
  }

  /** The dividers the solver settled on */
  def dividers: Gtpe2PllConfig = {
    assert(assignedDividers != null, "PLL dividers are only known after build()")
    assignedDividers
  }

  /** Drives the control inputs to their idle values
   *
   *  For a consumer that never resets, powers down or re-selects the
   *  reference clock. The inputs have no defaults, so anything that neither
   *  drives them nor calls this fails elaboration rather than silently
   *  running with them tied off.
   */
  def tieOff(): Unit = {
    io.reset := False
    io.powerDown := False
    io.lockDetectClk := False
    io.refClkSelect := refClk.select.asBits
  }

  private[xilinx] def assignIndex(index: Int): Unit = assignedIndex = index

  private[xilinx] def assignDividers(dividers: Gtpe2PllConfig): Unit = {
    assignedDividers = dividers
  }
}

/** A PLL handed out by a [[GtpCommon]]
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class GtpPllIo() extends Bundle with IMasterSlave {
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
}

object GtpCommon {
  val VcoMin = 1.6 GHz
  val VcoMax = 3.3 GHz
}

/** Hands out the two PLLs of a GTPE2_COMMON
 *
 *  Consumers claim a PLL with requestPll and wire to the returned handle. The
 *  primitive is instantiated by build(), once every claim is known, because
 *  the dividers are generics rather than ports.
 *
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class GtpCommon(
  vcoMin: HertzNumber = GtpCommon.VcoMin,
  vcoMax: HertzNumber = GtpCommon.VcoMax
) extends Component {
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

  private val pending = mutable.ArrayBuffer[GtpPll]()
  private var built = false

  /** Claims a PLL running from a given reference clock
   *
   *  index pins a particular one, otherwise the next free slot is used at
   *  build time. refClk.select becomes the power-on value of refClkSelect,
   *  which stays a runtime port so the PLL can be switched later.
   */
  def requestPll(
    refClk: GtpRefClk,
    config: GtpPllConfig,
    index: Option[Int] = None
  ): GtpPll = {
    assert(!built, "Cannot call requestPll() after build()")
    assert(pending.size < 2, "A GTPE2_COMMON has only two PLLs")
    index.foreach { i =>
      assert((0 to 1).contains(i), s"PLL index must be 0 or 1, was $i")
      assert(
        !pending.exists(_.requestedIndex.contains(i)),
        s"PLL $i has already been claimed"
      )
    }

    val slot = pending.size
    val port = rework {
      master(GtpPllIo()).setName(s"pll_$slot")
    }

    val pll = new GtpPll(refClk, config, index, port)
    pending += pll
    pll
  }

  /** Resolves every claim and instantiates the primitive */
  def build(): Unit = rework {
    assert(!built, "build() already called")
    assert(pending.nonEmpty, "No PLL requests registered")
    built = true

    assignIndices()
    pending.foreach(pll => pll.assignDividers(solveFor(pll)))

    pending.foreach { pll =>
      val d = pll.dividers
      val ref = pll.refClk.freq.toDouble
      val vco = ref / d.refClkDiv * d.fbDiv * d.fbDiv45
      println(
        f"[GtpCommon] PLL${pll.index} ${ref / 1e6}%.3f MHz " +
          f"(sel ${pll.refClk.select.code}) -> VCO ${vco / 1e6}%.3f MHz " +
          f"(refClkDiv=${d.refClkDiv}, fbDiv=${d.fbDiv}, fbDiv45=${d.fbDiv45})"
      )
    }

    val dividers = Array.fill(2)(Gtpe2PllConfig.default())
    pending.foreach(pll => dividers(pll.index) = pll.dividers)
    val common = Gtpe2Common(dividers(0), dividers(1))

    common.io.clocking.gtRefClk0 := io.gtRefClk0
    common.io.clocking.gtRefClk1 := io.gtRefClk1
    common.io.clocking.gtEastRefClk0 := io.gtEastRefClk0
    common.io.clocking.gtEastRefClk1 := io.gtEastRefClk1
    common.io.clocking.gtWestRefClk0 := io.gtWestRefClk0
    common.io.clocking.gtWestRefClk1 := io.gtWestRefClk1
    // the arbiter that shares this between both PLLs comes later
    common.io.drp.disable()

    val allocated = pending.map(_.index).toSet
    for (i <- 0 to 1) {
      val primitive = if (i == 0) common.io.pll0 else common.io.pll1
      if (allocated.contains(i)) {
        connect(pending.find(_.index == i).get.io, primitive)
      } else {
        primitive.disable()
      }
    }
  }

  /** Explicit claims keep their slot, automatic ones fill what is left */
  private def assignIndices(): Unit = {
    val taken = mutable.Set[Int]()
    pending.flatMap(_.requestedIndex).foreach(taken += _)
    var next = 0
    pending.foreach { pll =>
      pll.assignIndex(pll.requestedIndex.getOrElse {
        while (taken.contains(next)) {
          next += 1
        }
        taken += next
        next
      })
    }
  }

  private def solveFor(pll: GtpPll): Gtpe2PllConfig = {
    val dividers = pll.config match {
      case GtpPllConfig.Dividers(refClkDiv, fbDiv, fbDiv45) =>
        Gtpe2PllConfig(refClkDiv, fbDiv, fbDiv45)
      case GtpPllConfig.Vco(freq) =>
        GtpPll.solve(pll.refClk.freq, freq, vcoMin, vcoMax).getOrElse {
          SpinalError(
            s"GtpCommon: no PLL dividers give a ${freq.toDouble / 1e6} MHz " +
              s"VCO from a ${pll.refClk.freq.toDouble / 1e6} MHz reference " +
              s"(VCO range ${vcoMin.toDouble / 1e6}-${vcoMax.toDouble / 1e6} MHz)"
          )
        }
    }
    dividers.copy(simRefClkSelect = pll.refClk.select)
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
