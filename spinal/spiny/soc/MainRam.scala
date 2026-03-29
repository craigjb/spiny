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

package spiny.soc

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

import spiny.Utils._

class SpinyMainRam(
  size: BigInt,
  axiConfig: Axi4Config,
) extends Component {
  val byteCount = size
  val wordCount = byteCount / (axiConfig.dataWidth / 8)
  val ram = Mem(axiConfig.dataType, wordCount.toInt)

  val io = new Bundle {
    val iBus = slave(Axi4Shared(axiConfig))
    val dBus = slave(Axi4Shared(axiConfig))
  }

  val iBusPort = Axi4SharedOnChipRamPort(axiConfig, ram)
  io.iBus >> iBusPort

  val dBusPort = Axi4SharedOnChipRamPort(axiConfig, ram)
  io.dBus >> dBusPort

  def initFromFile(path: String) = {
    val firmware = read32BitMemFromFile(path)
    ram.init(
      firmware
        .padTo(wordCount.toInt, 0.toBigInt)
        .map(w => B(w, 32 bits))
    )
  }
}
