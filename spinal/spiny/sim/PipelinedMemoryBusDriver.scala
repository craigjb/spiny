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

package spiny.sim

import spinal.core._
import spinal.lib._
import spinal.core.sim._
import spinal.lib.bus.simple._

/** Simulation driver for PipelinedMemoryBus */
case class PipelinedMemoryBusDriver(
  bus: PipelinedMemoryBus,
  clockDomain: ClockDomain
) {
    bus.cmd.valid #= false

    /** Issues write transaction */
    def write(address: BigInt, data: BigInt) {
      bus.cmd.valid #= true
      bus.cmd.payload.write #= true
      bus.cmd.payload.address #= address
      bus.cmd.payload.data #= data
      bus.cmd.payload.mask #= 0xf

      clockDomain.waitSamplingWhere(bus.cmd.ready.toBoolean)
      bus.cmd.valid #= false
    }

    /** Issues read command, does not wait for response */
    def issueRead(address: BigInt) {
      bus.cmd.valid #= true
      bus.cmd.payload.write #= false
      bus.cmd.payload.address #= address
      bus.cmd.payload.mask #= 0xf

      clockDomain.waitSamplingWhere(bus.cmd.ready.toBoolean)
      bus.cmd.valid #= false
    }

    /** Issues read transaction returns response */
    def read(address: BigInt): BigInt = {
      issueRead(address)
      clockDomain.waitSamplingWhere(bus.rsp.valid.toBoolean)
      bus.rsp.payload.data.toBigInt
    }

  }
