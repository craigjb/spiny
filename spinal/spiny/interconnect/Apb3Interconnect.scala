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

package spiny.interconnect

import spinal.core._
import spinal.lib._
import spinal.lib.bus.simple._
import spinal.lib.bus.amba3.apb._
import spinal.lib.bus.misc._

import spiny.peripheral._
import spiny.Utils._

case class SpinyApb3Interconnect(
  busConfig: PipelinedMemoryBusConfig,
  baseAddress: BigInt,
  peripherals: Seq[SpinyPeripheral]
) extends Area {
  assert(!peripherals.isEmpty,
    "Empty peripherals for SpinyApb3Interconnect")

  /** Size of each peripheral's address space */
  val mappingSize = nextPowerOfTwo(
    peripherals.map(p => p.peripheralMappedSize).max - 1
  )

  /** Total mapped size of all peripherals */
  val mappedSize = mappingSize * peripherals.length

  val addressWidth = log2Up(mappedSize) + 1
  val alignmentMask = (BigInt(1) << addressWidth) - 1
  assert(
    (alignmentMask & baseAddress) == 0,
    "SpinyApb3Interconnect baseAddress must be above addressWidth " +
      s"(${addressWidth} bits)"
  )

  val apb3Config = Apb3Config(
    addressWidth = addressWidth,
    dataWidth = 32
  )

  val bridge = PipelinedMemoryBusToApbBridge(
    apb3Config,
    pipelineBridge = true,
    pipelinedMemoryBusConfig = busConfig
  )

  /** SizeMapping for each peripheral */
  val mappings = peripherals.zipWithIndex.map { case(p, i) =>
    (p, SizeMapping(i * mappingSize, mappingSize))
  }.toSeq

  val decoder = Apb3Decoder(
    master = bridge.io.apb,
    slaves = mappings.map{ case(p, sm) => (p.peripheralBus, sm) }
  )

  /** Bus that drives the APB3 bridge */
  def masterBus: PipelinedMemoryBus = {
    bridge.io.pipelinedMemoryBus
  }
}
