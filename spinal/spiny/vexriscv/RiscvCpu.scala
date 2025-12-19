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

import spiny.Utils._

case class RiscvCpu[
  IBus <: CpuBusDef.Bus,
  DBus <: CpuBusDef.Bus
](
  profile: RiscvProfile,
  cpuBusDef: CpuBusDef[IBus, DBus],
) extends Component {
  val io = new Bundle {
    val iBus = master(cpuBusDef.createIBus(profile.iBusPlugin))
    val dBus = master(cpuBusDef.createDBus(profile.dBusPlugin))
  }

  val jtagTap = Option.when(profile.withXilinxDebug)({
    profile.debugPlugin.get.debugCd = ClockDomain.current
    val tap = BSCANE2(userId = 4)
    val jtagClockDomain = ClockDomain(clock = tap.TCK)
    profile.debugPlugin.get.jtagCd = jtagClockDomain
    tap
  })

  val debugReset = False
  val debugRstArea = new ResetArea(debugReset, true) {
    val cpu = new VexRiscv(VexRiscvConfig(profile.toPlugins))
    cpuBusDef.connectIBus(profile.iBusPlugin, io.iBus)
    cpuBusDef.connectDBus(profile.dBusPlugin, io.dBus)

    profile.csrPlugin.externalInterrupt := False
    profile.csrPlugin.timerInterrupt := False

    if (profile.withXilinxDebug) {
      profile.debugPlugin.get.jtagInstruction <> 
        jtagTap.get.toJtagTapInstructionCtrl()
      debugReset.setWhen(profile.debugPlugin.get.ndmreset)
    }
  }
}
