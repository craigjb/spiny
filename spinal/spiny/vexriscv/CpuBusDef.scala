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

package spiny.vexriscv

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import vexriscv._
import vexriscv.plugin._

object CpuBusDef {
  type Bus = Bundle with IMasterSlave
}

sealed trait CpuBusDef[
  IBus <: CpuBusDef.Bus,
  DBus <: CpuBusDef.Bus
] {
  def createIBus(iBusPlugin: Plugin[VexRiscv]): IBus
  def createDBus(dBusPlugin: Plugin[VexRiscv]): DBus

  def connectIBus(iBusPlugin: Plugin[VexRiscv], iBus: IBus)
  def connectDBus(dBusPlugin: Plugin[VexRiscv], dBus: DBus)
}

object Axi4CpuBusDef extends CpuBusDef[
  Axi4ReadOnly,
  Axi4Shared
] {
  def createIBus(iBusPlugin: Plugin[VexRiscv]) = {
    val config = iBusPlugin match {
      case p: IBusSimplePlugin => IBusSimpleBus.getAxi4Config()
      case p: IBusCachedPlugin => p.config.getAxi4Config()
      case _ => SpinalError("Unknown IBus plugin type")
    }
    Axi4ReadOnly(config)
  }

  def createDBus(dBusPlugin: Plugin[VexRiscv]) = {
    val config = dBusPlugin match {
      case p: DBusSimplePlugin => IBusSimpleBus.getAxi4Config()
      case p: DBusCachedPlugin => p.config.getAxi4SharedConfig()
      case _ => SpinalError("Unknown DBus plugin type")
    }
    Axi4Shared(config)
  }

  def connectIBus(iBusPlugin: Plugin[VexRiscv], iBus: Axi4ReadOnly) {
    iBusPlugin match {
      case p: IBusSimplePlugin => iBus <> p.iBus.toAxi4ReadOnly()
      case p: IBusCachedPlugin => iBus <> p.iBus.toAxi4ReadOnly()
      case _ => SpinalError("Unknown IBus plugin type")
    }
  }

  def connectDBus(dBusPlugin: Plugin[VexRiscv], dBus: Axi4Shared) {
    dBusPlugin match {
      case p: DBusSimplePlugin => dBus <> p.dBus.toAxi4Shared()
      case p: DBusCachedPlugin => dBus <> p.dBus.toAxi4Shared()
      case _ => SpinalError("Unknown DBus plugin type")
    }
  }
}
