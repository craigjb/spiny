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
import spinal.lib.bus.amba4.axi._
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
    cpuProfile.axiConfig
  ).setName("MainRam")

  if (firmwarePath != null) {
    ram.initFromFile(firmwarePath)
  }

  var cpu: SpinyCpu = null
  var apb: SpinyApb3Interconnect = null

  def build(
    peripherals: Seq[SpinyPeripheral],
    mainBusSlaves: Seq[(SizeMapping, Axi4Bus)] = Seq(),
  ) {
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

    // connect peripheral interrupts to CPU
    peripheralsWithInterrupts.zipWithIndex.foreach { case (peripheral, idx) =>
      cpu.io.interrupts(idx) := peripheral.interrupt.get
    }

    // connect machine timer interrupt if present
    if (withMachineTimer) {
      cpu.io.machineTimerInterrupt := machineTimerPeripherals.head.machineTimerInterrupt.get
    }

    apb = SpinyApb3Interconnect(
      axiConfig = cpuProfile.axiConfig,
      baseAddress = peripheralsBaseAddress,
      peripherals = peripherals
    ).setName("Apb")

    // AXI4 crossbar: both iBus and dBus are masters
    val crossbar = Axi4CrossbarFactory()

    // RAM has two slave ports (dual-port BRAM) at the same address range
    crossbar.addSlave(ram.io.iBus, SizeMapping(ramBaseAddress, ram.byteCount))
    crossbar.addSlave(ram.io.dBus, SizeMapping(ramBaseAddress, ram.byteCount))
    crossbar.addSlave(apb.masterBus, SizeMapping(peripheralsBaseAddress, apb.mappedSize))
    mainBusSlaves.foreach { case (mapping, bus) =>
      crossbar.addSlave(bus, mapping)
    }

    // iBus only accesses RAM iBus port; dBus accesses everything else
    crossbar.addConnections(
      cpu.io.iBus -> List(ram.io.iBus),
      cpu.io.dBus -> (
        List(ram.io.dBus, apb.masterBus)
        ++ mainBusSlaves.map(_._2)
      )
    )
    crossbar.build()
  }

  def peripheralMappings: Seq[(SpinyPeripheral, SizeMapping)] = {
    assert(apb != null, "Must call build() on SpinySoC first")
    apb.mappings
  }

  def dumpSvd(name: String, path: String = "target/spinal/SpinySoC.svd") = {
    SpinySvd.dump(path, name, peripheralMappings)
  }

  def dumpHalCrate(
    path: String,
    crateName: String,
    pacCrateName: String,
    pacCratePath: String,
    spinyHalPath: String
  ): Unit = {
    assert(apb != null, "Must call build() on SpinySoC first")

    // Collect lib.rs modules from all peripherals
    val pacCrateRust = pacCrateName.replace("-", "_")
    val modules = peripheralMappings.flatMap { case (p, sm) =>
      p.halModuleCode(pacCrateRust, p.getName())
    }
    val libRs = "#![no_std]\n\n" + modules.mkString("\n\n") + "\n"

    // Merge dependencies from all peripherals, checking for conflicts
    val allDeps = peripheralMappings.flatMap { case (p, _) =>
      p.halDependencies.map { case (k, v) => (k, v, p.getName()) }
    }
    val deps = allDeps.groupBy(_._1).map { case (crate, entries) =>
      val versions = entries.map(_._2).distinct
      if (versions.size > 1) {
        val conflicts = entries.map { case (_, v, p) => s"$p: $v" }.mkString(", ")
        SpinalError(s"HAL dependency conflict for '$crate': $conflicts")
      }
      crate -> versions.head
    }

    // Generate Cargo.toml
    val pacDepKey = pacCrateName.replace("_", "-")
    val depsSection = deps.map { case (k, v) => s"""$k = $v""" }.mkString("\n")
    val cargoToml = s"""|[package]
                        |name = "$crateName"
                        |version = "0.1.0"
                        |edition = "2021"
                        |
                        |[dependencies]
                        |spiny-hal = { path = "$spinyHalPath" }
                        |$pacDepKey = { path = "$pacCratePath" }
                        |$depsSection
                        |""".stripMargin

    // Write crate
    val dir = new java.io.File(path)
    val srcDir = new java.io.File(dir, "src")
    srcDir.mkdirs()

    val libPw = new PrintWriter(new java.io.File(srcDir, "lib.rs"))
    libPw.write(libRs)
    libPw.close()

    val cargoPw = new PrintWriter(new java.io.File(dir, "Cargo.toml"))
    cargoPw.write(cargoToml)
    cargoPw.close()

    SpinalInfo(s"HAL crate dumped to: $path")
  }

  def dumpLinkerScript(path: String = "target/spinal/SpinySoC.x") = {
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
