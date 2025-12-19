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

import scala.collection.mutable.ArrayBuffer

import spinal.core._
import spinal.lib._
import spinal.lib.cpu.riscv.debug.DebugTransportModuleParameter
import spinal.lib.blackbox.xilinx.s7.BSCANE2
import spinal.lib.bus.amba4.axi._
import vexriscv._
import vexriscv.plugin._

trait RiscvProfile {
  def resetVector: BigInt
  def withXilinxDebug: Boolean
  def iBusPlugin: Plugin[VexRiscv]
  def dBusPlugin: Plugin[VexRiscv]
  def csrPlugin: CsrPlugin

  def debugClockDomain: Option[ClockDomain]
  def jtagClockDomain: Option[ClockDomain]
  def debugPlugin: Option[EmbeddedRiscvJtag]

  def toPlugins: Seq[Plugin[VexRiscv]]
}

case class Rv32iRust(
  resetVector: BigInt = 0x0L,
  withXilinxDebug: Boolean = false,
) {
    val iBusPlugin = new IBusSimplePlugin(
    resetVector = resetVector,
    cmdForkOnSecondStage = false,
    cmdForkPersistence = false,
  )

  val dBusPlugin = new DBusSimplePlugin()

  val csrPlugin = new CsrPlugin(
    config = CsrPluginConfig.smallest.copy(
      misaExtensionsInit = 70,
      misaAccess = CsrAccess.READ_ONLY,
      mtvecInit = 0x0,
      mtvecAccess = CsrAccess.READ_WRITE,
      ebreakGen = true,
      withPrivilegedDebug = true,
      wfiGenAsWait = true
    )
  )

  val debugClockDomain = Option.when(withXilinxDebug)(
    ClockDomain.internal("debugClock", withReset = false))

  val jtagClockDomain = Option.when(withXilinxDebug)(
    ClockDomain.internal("jtagClock", withReset=false))
  val debugPlugin = Option.when(withXilinxDebug)(
    new EmbeddedRiscvJtag(
      p = DebugTransportModuleParameter(
        addressWidth = 7,
        version = 1,
        idle = 7
      ),
      debugCd = debugClockDomain.get,
      jtagCd = jtagClockDomain.get,
      withTunneling = true,
      withTap = false,
    )
  )

  def toPlugins = Seq(
      iBusPlugin,
      dBusPlugin,
      new DecoderSimplePlugin(
        catchIllegalInstruction = true
      ),
      new RegFilePlugin(
        regFileReadyKind = plugin.SYNC,
      ),
      new IntAluPlugin,
      new SrcPlugin(
        separatedAddSub = false,
        executeInsertion = true
      ),
      new LightShifterPlugin,
      new HazardSimplePlugin(
        bypassExecute           = true,
        bypassMemory            = true,
        bypassWriteBack         = true,
        bypassWriteBackBuffer   = true,
      ),
      new BranchPlugin(
        earlyBranch = false,
      ),
      csrPlugin
    ) ++ debugPlugin
}
