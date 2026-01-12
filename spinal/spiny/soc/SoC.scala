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

import java.io.PrintWriter

import spinal.core._
import spinal.lib._
import spinal.lib.bus.simple._
import spinal.lib.bus.misc._

import spiny.peripheral._
import spiny.svd._

class SpinySoC(
  cpuProfile: SpinyCpuProfile,
  ramSize: BigInt,
  ramBaseAddress: BigInt = 0x0,
  firmwarePath: String = null,
  peripheralsBaseAddress: BigInt = 0x10000000
) extends Area {
  val ram = new SpinyMainRam(
    size = ramSize,
    cpuProfile.busConfig
  ).setName("MainRam")

  if (firmwarePath != null) {
    ram.initFromFile(firmwarePath)
  }

  var cpu: SpinyCpu = null
  var apb: SpinyApb3Interconnect = null

  def build(peripherals: Seq[SpinyPeripheral]) {
    // extract machine timer interrupt if present
    val machineTimerPeripherals = peripherals.filter(_.machineTimerInterrupt.isDefined)
    assert(machineTimerPeripherals.length <= 1,
      "Only one peripheral can provide machineTimerInterrupt")
    val withMachineTimer = machineTimerPeripherals.nonEmpty

    // extract interrupt descriptors from peripherals
    val peripheralsWithInterrupts = peripherals
      .filter(_.interrupt.isDefined)
    val interrupts = peripheralsWithInterrupts
      .zipWithIndex.map { case (peripheral, idx) =>
        SpinyCpuInterrupt(
          name = peripheral.interruptName,
          code = 16 + idx  // Start at code 16 for user interrupts
        )
    }

    // create CPU with interrupt descriptors
    cpu = SpinyCpu(cpuProfile, interrupts, withMachineTimer).setName("Cpu")
    cpu.io.iBus <> ram.io.iBus

    // connect peripheral interrupts to CPU
    peripheralsWithInterrupts.zipWithIndex.foreach { case (peripheral, idx) =>
      cpu.io.interrupts(idx) := peripheral.interrupt.get
    }

    // connect machine timer interrupt if present
    if (withMachineTimer) {
      cpu.io.machineTimerInterrupt := machineTimerPeripherals.head.machineTimerInterrupt.get
    }

    apb = SpinyApb3Interconnect(
      busConfig = cpuProfile.busConfig,
      baseAddress = 0x10000000,
      peripherals = peripherals
    ).setName("Apb")

    val decoder = PipelinedMemoryBusDecoder(
      busConfig = cpuProfile.busConfig,
      mappings = Seq(
        SizeMapping(ramBaseAddress, ram.byteCount),
        SizeMapping(apb.baseAddress, apb.mappedSize)
      )
    ).setName("Decoder")

    cpu.io.dBus >> decoder.io.input
    decoder.io.outputs(0) >> ram.io.dBus
    decoder.io.outputs(1) >> apb.masterBus
  }

  def peripheralMappings: Seq[(SpinyPeripheral, SizeMapping)] = {
    assert(apb != null, "Must call build() on SpinySoC first")
    apb.mappings.map { case(p, sm) =>
      (p, SizeMapping(sm.base + peripheralsBaseAddress, sm.size))
    }.toSeq
  }

  def dumpSvd(path: String, name: String) = {
    SpinySvd.dump(path, name, peripheralMappings)
  }

  def dumpLinkerScript(path: String) = {
    val linkerScript = 
      f"""|MEMORY
          |{
          |  RAM : ORIGIN = 0x${ramBaseAddress}%x, LENGTH = ${ramSize}
          |}
          |
          |REGION_ALIAS("REGION_TEXT", RAM);
          |REGION_ALIAS("REGION_RODATA", RAM);
          |REGION_ALIAS("REGION_DATA", RAM);
          |REGION_ALIAS("REGION_BSS", RAM);
          |REGION_ALIAS("REGION_HEAP", RAM);
          |REGION_ALIAS("REGION_STACK", RAM);""".stripMargin

    val pw = new PrintWriter(path)
    pw.write(linkerScript)
    pw.close()
    SpinalInfo(s"Linker script dumped to: ${path}")
  }
}
