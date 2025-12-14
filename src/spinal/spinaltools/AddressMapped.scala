/*                                                                                           *\
**   /$$$$$$            /$$                     /$$ /$$$$$$$$                  /$$           **
**  /$$__  $$          |__/                    | $$|__  $$__/                 | $$           **
** | $$  \__/  /$$$$$$  /$$ /$$$$$$$   /$$$$$$ | $$   | $$  /$$$$$$   /$$$$$$ | $$  /$$$$$$$ **
** |  $$$$$$  /$$__  $$| $$| $$__  $$ |____  $$| $$   | $$ /$$__  $$ /$$__  $$| $$ /$$_____/ **
**  \____  $$| $$  \ $$| $$| $$  \ $$  /$$$$$$$| $$   | $$| $$  \ $$| $$  \ $$| $$|  $$$$$$  **
**  /$$  \ $$| $$  | $$| $$| $$  | $$ /$$__  $$| $$   | $$| $$  | $$| $$  | $$| $$ \____  $$ **
** |  $$$$$$/| $$$$$$$/| $$| $$  | $$|  $$$$$$$| $$   | $$|  $$$$$$/|  $$$$$$/| $$ /$$$$$$$/ **
**  \______/ | $$____/ |__/|__/  |__/ \_______/|__/   |__/ \______/  \______/ |__/|_______/  **
**           | $$                                                                            **
**           | $$                                                                            **
**           |__/                                                                            **
**                                                                                           **
** (c) Craig J Bishop, all rights reserved                                                   **
** MIT License                                                                               **
**                                                                                           **
** Permission is hereby granted, free of charge, to any person obtaining a copy of this      **
** software and associated documentation files (the "Software"), to deal in the Software     **
** without restriction, including without limitation the rights to use, copy, modify, merge, **
** publish, distribute, sublicense, and/or sell copies of the Software, and to permit        **
** persons to whom the Software is furnished to do so, subject to the following conditions:  **
**                                                                                           **
** The above copyright notice and this permission notice shall be included in all copies or  **
** substantial portions of the Software.                                                     **
**                                                                                           **
** THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,       **
** INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR  **
** PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE **
** FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR      **
** OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER    **
** DEALINGS IN THE SOFTWARE.                                                                 **
*\                                                                                           */

package spinaltools

import spinal.core._
import spinal.lib.bus.misc.SizeMapping

import spinaltools.BusDef
import spinaltools.Utils._

/** Mixin for address mapped components
 *
 *  Allows automatic generation of address maps
 *  and decoding for a bus.
 */
trait AddressMapped[B <: BusDef.Bus] {
  /** Returns which bus to map on */
  def mappedBus: B

  /** Returns minimum address region size to map
   *
   *  A bigger region may be mapped, but it will be at
   *  least this size.
   */
  def minMappedSize: BigInt
}

object AddressMapped {
  /** Maps components onto a bus with automatically generated base addresses
   *
   * All components must implement AddressMapped. To minimize decoding logic, creates
   * equal-sized, power-of-2 regions big enough for the biggest component.
   */
  def mapComponents[B <: BusDef.Bus](
    bus: B,
    busDef: BusDef[B],
    baseAddress: BigInt,
    components: Seq[AddressMapped[B]]
  ): Component = {
    // sanity check
    assert(baseAddress < BigInt(2).pow(busDef.addressWidth),
      "baseAddress is beyond bus address range")
    assert(!components.isEmpty, "components is empty")

    // find biggest power of 2 region size
    val regionSize = components.map(c => nextPowerOfTwo(c.minMappedSize)).max

    // create mappings for decoder
    val mappings = components.zipWithIndex.map { case(c, i) => 
      (c.mappedBus, SizeMapping(baseAddress + (i * regionSize), regionSize))
    }.toSeq

    // return the decoder
    busDef.createDecoder(master = bus, slaves = mappings)
  }
}
