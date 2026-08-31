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

object DataRate extends Enumeration {
  type DataRate = Value
  val BUF, SDR, DDR = Value
}

object SerDesMode extends Enumeration {
  type SerDesMode = Value
  val MASTER, SLAVE = Value
}

case class OSERDESE2(
  dataRateOQ: DataRate.Value = DataRate.DDR,
  dataRateTQ: DataRate.Value = DataRate.SDR,
  dataWidth: Int = 4,
  serDesMode: SerDesMode.Value = SerDesMode.MASTER,
  triStateWidth: Int = 4
) extends BlackBox {
  dataRateOQ match {
    case DataRate.SDR => assert(Set(2, 3, 4, 5, 6, 7, 8).contains(dataWidth),
      "For SDR, dataWidth must be 2, 3, 4, 5, 6, 7, or 8")
    case DataRate.DDR => assert(Set(2, 4, 6, 8, 10, 14).contains(dataWidth),
      "For DDR, dataWidth must be 2, 4, 6, 8, 10, or 14")
    case _ => SpinalError("dataRateOQ must be SDR or DDR")
  }
  assert(Set(1, 4).contains(triStateWidth), "triStateWidth must be 1 or 4")

  addGeneric("DATA_RATE_OQ", dataRateOQ.toString)
  addGeneric("DATA_RATE_TQ", dataRateTQ.toString)
  addGeneric("DATA_WIDTH", dataWidth)
  addGeneric("SERDES_MODE", serDesMode.toString)
  addGeneric("TRISTATE_WIDTH", triStateWidth)

  val CLK = in Bool()
  val CLKDIV = in Bool()
  val D1 = in Bool()
  val D2 = in.Bool()
  val D3 = in.Bool()
  val D4 = in.Bool()
  val D5 = in.Bool()
  val D6 = in.Bool()
  val D7 = in.Bool()
  val D8 = in.Bool()
  val T1 = in Bool()
  val T2 = in.Bool()
  val T3 = in.Bool()
  val T4 = in.Bool()
  val TCE = in Bool()
  val OCE = in Bool()
  val TBYTEIN = in Bool() default(False)
  val RST = in Bool()
  val SHIFTIN1 = in Bool() default(False)
  val SHIFTIN2 = in Bool() default(False)
  val OQ = out Bool()
  val OFB = out Bool()
  val TQ = out Bool()
  val TFB = out Bool()
  val TBYTEOUT = out Bool()
  val SHIFTOUT1 = out Bool()
  val SHIFTOUT2 = out Bool()

  def D(i : Int) = i match {
    case 0 => D1
    case 1 => D2
    case 2 => D3
    case 3 => D4
    case 4 => D5
    case 5 => D6
    case 6 => D7
    case 7 => D8
  }

  def T(i : Int) = i match {
    case 0 => T1
    case 1 => T2
    case 2 => T3
    case 3 => T4
  }
}
