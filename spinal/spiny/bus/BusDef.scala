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

package spiny.bus

import spinal.core._
import spinal.lib._
import spinal.lib.bus.regif._
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.amba3.apb._
import spinal.lib.bus.wishbone._


object BusDef {
  type Bus = Bundle with IMasterSlave
}

/** Bus definition 
 *
 *  Allows components to be generic over different buses by 
 *  taking a type parameter implementing this trait and using 
 *  the included methods to instantiate the bus and register 
 *  interface
 */
trait BusDef[B <: BusDef.Bus] {
  /** Decoder component type for the bus */
  type DecoderType <: Component

  /** Returns bit width of address bus */
  def addressWidth: Int

  /** Returns bit width of data bus */
  def dataWidth: Int

  /** Returns an instance of the bus bundle */
  def createBus(): B

  /** Returns a RegIf bus interface for the given bus */
  def createBusInterface(
      bus: B,
      regPre: String = "",
      withSecFireWall: Boolean = false
  ): BusIf

  /** Creates a bus decoder for the given master and slave mappings */
  def createDecoder(master: B, slaves: Seq[(B, SizeMapping)]): DecoderType
}

/** APB3 bus definition */
case class Apb3BusDef(config: Apb3Config) extends BusDef[Apb3] {
  type DecoderType = Apb3Decoder

  def addressWidth = config.addressWidth

  def dataWidth = config.dataWidth

  def createBus() = Apb3(config)

  def createBusInterface(
      bus: Apb3,
      regPre: String = "",
      withSecFireWall: Boolean = false
  ) = Apb3BusInterface(
    bus,
    SizeMapping(0, 0),
    regPre = regPre,
    withSecFireWall = withSecFireWall
  )

  def createDecoder(master: Apb3, slaves: Seq[(Apb3, SizeMapping)]) = {
    Apb3Decoder(master, slaves)
  }
}

/** Wishbone bus definition */
case class WishboneBusDef(config: WishboneConfig) extends BusDef[Wishbone] {
  type DecoderType = WishboneDecoder

  def addressWidth = config.addressWidth

  def dataWidth = config.dataWidth

  def createBus() = Wishbone(config)

  def createBusInterface(
      bus: Wishbone,
      regPre: String = "",
      withSecFireWall: Boolean = false
  ) = WishboneBusInterface(
    bus,
    SizeMapping(0, 0),
    regPre = regPre,
    withSecFireWall = withSecFireWall
  )

  def createDecoder(master: Wishbone, slaves: Seq[(Wishbone, SizeMapping)]) = {
    WishboneDecoder(master, slaves)
  }
}
