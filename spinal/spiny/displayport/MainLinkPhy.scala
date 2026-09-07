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
import spinal.lib.bus.regif._

/** What a main link transmitter is putting on the wire */
object MainLinkPattern extends SpinalEnum(binarySequential) {
  /** Lanes idle, nothing transmitted */
  val Quiet = newElement()

  /** Training pattern 1, D10.2 on every lane, for the sink's clock recovery */
  val TrainingPattern1 = newElement()

  /** Training pattern 2, the sequence the sink equalizes and symbol locks to */
  val TrainingPattern2 = newElement()
}

/** Drive levels for one lane (DPCD levels)
 */
case class MainLinkLaneDrive() extends Bundle {
  /** Voltage swing level, 0 to 3 */
  val swing = UInt(2 bits)

  /** Pre-emphasis level, 0 to 3 */
  val preEmphasis = UInt(2 bits)
}

/** Ports and registers a specific PHY adds to the control bus
 *
 *  Each PHY can expose platform-specific control and status registers this way
 */
abstract class MainLinkPhyPorts extends Bundle with IMasterSlave {
  /** Creates this PHY's registers
   *
   *  @param busIf BusIf to add them on
   *  @param lane Which lane these belong to, for naming
   */
  def driveFrom(busIf: BusIf, lane: Int): Unit
}

/** A PHY with nothing of its own to expose (for tests) */
case class NoMainLinkPhyPorts() extends MainLinkPhyPorts {
  override def asMaster(): Unit = {}
  override def driveFrom(busIf: BusIf, lane: Int): Unit = {}
}

/** Helper implemented by each PHY's companion object
 */
trait MainLinkPhyTxType {
  /** Ports and registers this PHY adds, see [[MainLinkPhyPorts]] */
  def ports: HardType[MainLinkPhyPorts]
}

object MainLinkPhyTxType {
  def apply(phyPorts: HardType[MainLinkPhyPorts]): MainLinkPhyTxType =
    new MainLinkPhyTxType {
      override def ports: HardType[MainLinkPhyPorts] = phyPorts
    }
}

/** No transmit PHY, for a source with no main link */
object NoMainLinkPhyTx extends MainLinkPhyTxType {
  override def ports: HardType[MainLinkPhyPorts] = NoMainLinkPhyPorts()
}

/** A main link PHY TX, implemented by concrete PHY TX classes
 */
trait MainLinkPhyTx { self: Component =>
  def control: MainLinkPhyTxControl
}

/** Control bus from a main link controller to its transmit PHY
 *
 *  @param laneCount Number of lanes the link can drive
 */
case class MainLinkPhyTxControl(
  laneCount: Int,
  phyPorts: HardType[MainLinkPhyPorts] = NoMainLinkPhyPorts()
) extends Bundle with IMasterSlave {
  assert(Seq(1, 2, 4).contains(laneCount),
    s"a DisplayPort link has 1, 2 or 4 lanes, not $laneCount")

  /** Brings the transmitter up, low holds it in reset */
  val enable = Bool()

  /** High once the transmitter has finished its reset sequence */
  val ready = Bool()

  /** What to transmit */
  val pattern = MainLinkPattern()

  /** Per lane drive levels, as requested by the sink during training */
  val drive = Vec(MainLinkLaneDrive(), laneCount)

  /** Whatever this particular PHY adds, see [[MainLinkPhyPorts]] */
  val phy = phyPorts()

  override def asMaster(): Unit = {
    out(enable, pattern, drive)
    in(ready)
    master(phy)
  }
}
