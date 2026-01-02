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

import scala.collection.mutable.ArrayBuffer

import spinal.core._
import spinal.lib._
import spinal.lib.cpu.riscv.debug.DebugTransportModuleParameter
import spinal.lib.bus.simple._
import spinal.lib.bus.amba4.axi._
import vexriscv._
import vexriscv.plugin._

import spiny.Utils._

trait SpinyCpuProfile {
  def resetVector: BigInt
  def withXilinxDebug: Boolean

  def csrPlugin: CsrPlugin
  def debugPlugin: Option[EmbeddedRiscvJtag]

  def busConfig: PipelinedMemoryBusConfig
  def iBus: PipelinedMemoryBus
  def dBus: PipelinedMemoryBus

  def toPlugins: Seq[Plugin[VexRiscv]]
}

/** Small RV32i profile that runs bare-metal Rust 
 *
 * This profile is configured for IPC with Rust, so it enables bypasses
 * and the full barrel shifter.
 *
 * withXilinxDebug adds a BSCANE2 JTAG tap accessible via the 
 * FPGA configuration cable
 */
case class SpinyRv32iRustCpuProfile(
  resetVector: BigInt = 0x0L,
  withXilinxDebug: Boolean = false,
) extends SpinyCpuProfile {
  val iBusPlugin = new IBusSimplePlugin(
    resetVector = resetVector,
    cmdForkOnSecondStage = false,
    // required for AXI
    cmdForkPersistence = true,
  )
  def busConfig = IBusSimpleBus.getPipelinedMemoryBusConfig()
  def iBus = iBusPlugin.iBus.toPipelinedMemoryBus()
  val dBusPlugin = new DBusSimplePlugin()
  def dBus = dBusPlugin.dBus.toPipelinedMemoryBus()

  val csrPlugin = new CsrPlugin(
    config = CsrPluginConfig.smallest.copy(
      misaExtensionsInit = 70,
      misaAccess = CsrAccess.READ_ONLY,
      mtvecInit = 0x0,
      mtvecAccess = CsrAccess.READ_WRITE,
      ebreakGen = true,
      withPrivilegedDebug = withXilinxDebug,
      wfiGenAsWait = true
    )
  )

  val debugPlugin = Option.when(withXilinxDebug)(
    new EmbeddedRiscvJtag(
      p = DebugTransportModuleParameter(
        addressWidth = 7,
        version = 1,
        idle = 7
      ),
      debugCd = null,
      jtagCd = null,
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
      new FullBarrelShifterPlugin,
      new HazardSimplePlugin(
        bypassExecute = true,
        bypassMemory = true,
        bypassWriteBack = true,
        bypassWriteBackBuffer = true,
      ),
      new BranchPlugin(
        earlyBranch = false,
      ),
      csrPlugin
    ) ++ debugPlugin
}
