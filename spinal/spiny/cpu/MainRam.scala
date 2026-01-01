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

package spiny.cpu

import spinal.core._
import spinal.lib._
import spinal.lib.bus.simple._
import spinal.lib.bus.amba4.axi._

import vexriscv.plugin._

import spiny.Utils._

class SpinyMainRam(
  size: BigInt,
  busConfig: PipelinedMemoryBusConfig,
) extends Component {
  val io = new Bundle {
    val iBus = slave(PipelinedMemoryBus(busConfig))
    val dBus = slave(PipelinedMemoryBus(busConfig))
  }
  assert(size % 4 == 0, "size must be an even multiple of words")
  assert(busConfig.dataWidth == 32,
    "SpinyMainRam only supports 32-bit iBus")

  val byteCount = size
  val wordCount = byteCount / 4
  val mem = Mem(Bits(32 bits), wordCount)

  val iBusPort = new Area {
    io.iBus.rsp.valid := RegNext(
      io.iBus.cmd.fire && !io.iBus.cmd.write
    ) init(False)
    io.iBus.rsp.data := mem.readWriteSync(
      address = (io.iBus.cmd.address >> 2).resized,
      data = io.iBus.cmd.data,
      enable  = io.iBus.cmd.valid,
      write  = io.iBus.cmd.write,
      mask  = io.iBus.cmd.mask
    )
    io.iBus.cmd.ready := True
  }

  val dBusPort = new Area {
    io.dBus.rsp.valid := RegNext(
      io.dBus.cmd.fire && !io.dBus.cmd.write
    ) init(False)
    io.dBus.rsp.data := mem.readWriteSync(
      address = (io.dBus.cmd.address >> 2).resized,
      data = io.dBus.cmd.data,
      enable  = io.dBus.cmd.valid,
      write  = io.dBus.cmd.write,
      mask  = io.dBus.cmd.mask
    )
    io.dBus.cmd.ready := True
  }

  def initFromFile(path: String) = {
    val firmware = read32BitMemFromFile(path)
    mem.init(
      firmware
        .padTo(wordCount.toInt, 0.toBigInt)
        .map(w => B(w, 32 bits))
    )
  }
}
