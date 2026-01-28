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

import spiny.dram._

case class DramNativePortDriver(
  port: NativePort,
  clockDomain: ClockDomain
) {
    port.cmd.valid #= false
    port.write.valid #= false
    port.read.ready #= true

    /* Issues write transaction */
    def write(address: BigInt, data: BigInt) {
      port.write.valid #= true
      port.write.payload.we #= 0xf
      port.write.payload.data #= data

      port.cmd.valid #= true
      port.cmd.payload.addr #= address
      port.cmd.payload.we #= true

      var cmdFired = false
      var writeFired = false
      while (!(cmdFired && writeFired)) {
        clockDomain.waitSamplingWhere(
          port.cmd.ready.toBoolean || port.write.ready.toBoolean)
        if (port.cmd.ready.toBoolean) {
          cmdFired = true
          port.cmd.valid #= false
          port.cmd.payload.we #= false
        }
        if (port.write.ready.toBoolean) {
          writeFired = true
          port.write.valid #= false
        }
      }
      port.cmd.valid #= false
      port.write.valid #= false
    }

    /** Issues read command, does not wait for response */
    def issueRead(address: BigInt) {
      port.cmd.valid #= true
      port.cmd.payload.addr #= address
      port.cmd.payload.we #= false

      clockDomain.waitSamplingWhere(port.cmd.ready.toBoolean)
      port.cmd.valid #= false
      port.cmd.payload.addr #= address + 1
    }

    /** Issues read transaction returns response */
    def read(address: BigInt): BigInt = {
      issueRead(address)
      clockDomain.waitSamplingWhere(port.read.valid.toBoolean)
      port.read.payload.toBigInt
    }
  }
