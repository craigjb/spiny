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

package spiny.graphics

import spinal.core._
import spinal.lib._

object PalettePixel {
  def apply(width: BitCount) = UInt(width)
  def apply(c: Int, width: BitCount) = U(c, width)
  def apply(paletteSize: Int) = UInt(log2Up(paletteSize) bits)
  def apply(c: Int, paletteSize: Int) = U(c, log2Up(paletteSize) bits)
}

object RgbPixel {
  def apply(r: Int, g: Int, b: Int): RgbPixel = {
      val pixel = RgbPixel()
      pixel.r := r
      pixel.g := g
      pixel.b := b
      pixel
  }

  def apply(r: UInt, g: UInt, b: UInt): RgbPixel = {
      val pixel = RgbPixel()
      pixel.r := r
      pixel.g := g
      pixel.b := b
      pixel
  }
}

case class RgbPixel() extends Bundle {
  val r = UInt(8 bits)
  val g = UInt(8 bits)
  val b = UInt(8 bits)

  def channel(i: Int): UInt = {
      i match {
        case 0 => b
        case 1 => g
        case 2 => r
        case default => SpinalError("RgbPixel only has channels 0-2")
      }
  }
}
