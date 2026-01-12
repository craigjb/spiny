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

import scala.collection.mutable.ArrayBuffer

import spinal.core._
import spinal.lib._
import spinal.lib.bus.simple._
import spinal.lib.blackbox.xilinx.s7.BSCANE2
import spinal.lib.bus.amba4.axi._
import vexriscv._
import vexriscv.plugin._

import spiny.Utils._

/** Interrupt descriptor for SpinyCpu
 *
 *  @param name Interrupt name
 *  @param code Interrupt code (CSR exception code)
 */
case class SpinyCpuInterrupt(name: String, code: Int)

case class SpinyCpu(
  profile: SpinyCpuProfile,
  interruptDescs: Seq[SpinyCpuInterrupt] = Seq()
) extends Component {
  import SpinyCpu._

  val io = new Bundle {
    val iBus = master(PipelinedMemoryBus(profile.busConfig))
    val dBus = master(PipelinedMemoryBus(profile.busConfig))
    val interrupts = in Vec(Bool(), interruptDescs.length)
  }

  // Create UserInterruptPlugins from interrupt descriptors
  val interruptPlugins = ArrayBuffer[UserInterruptPlugin]()
  interruptDescs.foreach { intDesc =>
    val plugin = new UserInterruptPlugin(
      interruptName = intDesc.name,
      code = intDesc.code
    )
    interruptPlugins += plugin
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
    val cpu = new VexRiscv(
      VexRiscvConfig(profile.toPlugins ++ interruptPlugins)
    )
    profile.iBus <> io.iBus
    profile.dBus <> io.dBus

    profile.csrPlugin.externalInterrupt := False
    profile.csrPlugin.timerInterrupt := False

    // Wire up interrupt signals to UserInterruptPlugins
    interruptPlugins.zipWithIndex.foreach { case (plugin, idx) =>
      plugin.interrupt := io.interrupts(idx)
    }

    if (profile.withXilinxDebug) {
      profile.debugPlugin.get.jtagInstruction <>
        jtagTap.get.toJtagTapInstructionCtrl()
      val ndmResetCC = BufferCC(profile.debugPlugin.get.ndmreset)
      debugReset.setWhen(ndmResetCC)
    }
  }
  debugRstArea.setName("")
}
