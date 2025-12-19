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
import spinal.lib.blackbox.xilinx.s7.BSCANE2
import vexriscv._

class RiscvCpu[
  IBus <: CpuBusDef.Bus,
  DBus <: CpuBusDef.Bus
](
  profile: RiscvProfile,
  cpuBusDef: CpuBusDef[IBus, DBus],
) extends Component {
  val io = new Bundle {
    val iBus = cpuBusDef.createIBus(profile.iBusPlugin)
    val dBus = cpuBusDef.createDBus(profile.dBusPlugin)
  }

  val debugReset = False
  val cpuClkDomain = ClockDomain(
    clock = ClockDomain.current.readClockWire,
    reset = debugReset || ClockDomain.current.isResetActive,
    frequency = ClockDomain.current.frequency
  )

  val cpuClkArea = new ClockingArea(cpuClkDomain) {
    val cpu = new VexRiscv(VexRiscvConfig(profile.toPlugins))
    cpuBusDef.connectIBus(profile.iBusPlugin, io.iBus)
    cpuBusDef.connectDBus(profile.dBusPlugin, io.dBus)

    if (profile.withXilinxDebug) {
      val jtagTap = BSCANE2(userId = 4)
      profile.jtagClockDomain.get.clock := jtagTap.TCK
      profile.debugPlugin.get.jtagInstruction <> 
        jtagTap.toJtagTapInstructionCtrl()

      profile.debugClockDomain.get.clock := ClockDomain.current.readClockWire
      debugReset.setWhen(profile.debugPlugin.get.ndmreset)
    }
  }
}
