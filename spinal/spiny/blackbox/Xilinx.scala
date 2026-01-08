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

package spiny.blackbox.xilinx

import spinal.core._

object DdrClkEdge extends Enumeration {
  type DdrClkEdge = Value
  val OPPOSITE_EDGE, SAME_EDGE = Value
}

object SetResetType extends Enumeration {
  type SetResetType = Value
    val SYNC, ASYNC = Value
}

case class ODDR(
  ddrClkEdge: DdrClkEdge.Value = DdrClkEdge.OPPOSITE_EDGE,
  init: Boolean = false, 
  setResetType: SetResetType.Value = SetResetType.SYNC
) extends BlackBox {
  addGeneric("DDR_CLK_EDGE", ddrClkEdge.toString)
  addGeneric("INIT", if (init) { 1 } else { 0 })
  addGeneric("SRTYPE", setResetType.toString)

  val Q  = out Bool()
  val C  = in Bool()
  val CE = in Bool()
  val D1 = in Bool()
  val D2 = in Bool()
  val R  = in Bool()
  val S  = in Bool()
}
