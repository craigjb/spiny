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

import spiny.svd.SpinySvd

trait SpinyPeripheral {
  var peripheralBus: Apb3 = null
  var peripheralBusIf: Apb3BusInterface = null
  var peripheralMappedSize: BigInt = null

  /** Implementation typically comes from SpinalHDL Component base class */
  def getName(): String

  /** Creates primary peripheral bus interface for allocating registers */
  def createPeripheralBusInterface(bus: Apb3): Apb3BusInterface = {
    peripheralBus = bus
    // the SizeMapping parameter isn't used, so just pass all zeros
    peripheralBusIf = Apb3BusInterface(peripheralBus, SizeMapping(0, 0))
    peripheralMappedSize = 1 << bus.config.addressWidth
    peripheralBusIf
  }

  /** Makes sure the peripheral's allocated registers fit into the
   *  mapped address space 
   */
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

  /** Override to provide machine timer interrupt signal
   *
   *  Return Some(interrupt) to designate this peripheral as the
   *  machine timer. Will be wired to csrPlugin.timerInterrupt.
   *  Only one peripheral per SoC should provide this.
   *  Mutually exclusive with interrupt.
   */
  def machineTimerInterrupt: Option[Bool] = None

  /** Override to provide interrupt name
   *
   *  Used for UserInterruptPlugin and code generation
   */
  def interruptName: String = getName() + "Int"

  /** Override to customize SVD generation for this peripheral.
   *
   *  Default returns a single peripheral XML element using SpinySvd.peripheralXml.
   *  Override to return multiple peripherals (e.g., from an external SVD file).
   */
  def svdPeripherals(sizeMapping: SizeMapping): Seq[scala.xml.Elem] = {
    Seq(SpinySvd.peripheralXml(this, sizeMapping))
  }

  /** Override to provide Rust HAL module source for this peripheral.
   *
   *  Return Some(rustSource) with a complete `pub mod name { ... }` block,
   *  or None to skip HAL generation for this peripheral.
   *
   *  Clock frequency is available via ClockDomain.current.frequency.getValue.
   */
  def halModuleCode(pacCrate: String, name: String, baseAddress: BigInt): Option[String] = None

  /** Override to declare Rust crate dependencies needed by halModuleCode output.
   *  Returns Cargo.toml dependency lines: crateName -> versionSpec.
   *  These are merged across all peripherals into the HAL crate's Cargo.toml.
   */
  def halDependencies: Map[String, String] = Map.empty
}
