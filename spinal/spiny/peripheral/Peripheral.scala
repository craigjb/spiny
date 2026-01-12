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

package spiny.peripheral

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb._
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.regif._

trait SpinyPeripheral {
  var peripheralBus: Apb3 = null
  var peripheralBusIf: Apb3BusInterface = null
  var peripheralMappedSize: BigInt = null

  def getName(): String

  def createPeripheralBusInterface(bus: Apb3): Apb3BusInterface = {
    peripheralBus = bus
    peripheralBusIf = Apb3BusInterface(peripheralBus, SizeMapping(0, 0))
    peripheralMappedSize = 1 << bus.config.addressWidth
    peripheralBusIf
  }

  def checkPeripheralMapping() {
    assert(peripheralMappedSize >= peripheralBusIf.getMappedSize,
      "Peripheral addressWidth must be >= " +
      s"${log2Up(peripheralBusIf.getMappedSize)}")
  }

  /** Override to provide interrupt signal
   *
   *  Return Some(interrupt) if peripheral has interrupt support,
   *  None otherwise
   */
  def interrupt: Option[Bool] = None

  /** Override to provide interrupt name
   *
   *  Used for UserInterruptPlugin and code generation
   */
  def interruptName: String = getName() + "Int"
}
