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
    mainBusSlaves: Seq[(SizeMapping, Axi4Shared)] = Seq()
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
      cpu.io.dBus -> (List(ram.io.dBus, apb.masterBus) ++ mainBusSlaves.map(_._2))
    )
    crossbar.build()
  }

  def peripheralMappings: Seq[(SpinyPeripheral, SizeMapping)] = {
    assert(apb != null, "Must call build() on SpinySoC first")
    apb.mappings
  }

  def dumpSvd(path: String, name: String) = {
    SpinySvd.dump(path, name, peripheralMappings)
  }

  def dumpHalJson(path: String, name: String, sysClkFreqHz: Long) = {
    val peripheralEntries = peripheralMappings.flatMap { case (p, sm) =>
      val desc = p.halDescription
      if (desc.isEmpty) None
      else {
        desc("name") = p.getName()
        desc("base_address") = f"0x${sm.base}%x"
        Some(desc)
      }
    }

    def toJson(value: Any, indent: Int = 2): String = {
      val pad = " " * indent
      val innerPad = " " * (indent + 2)
      value match {
        case m: scala.collection.mutable.LinkedHashMap[_, _] =>
          val entries = m.map { case (k, v) =>
            f"""${innerPad}"${k}": ${toJson(v, indent + 2)}"""
          }.mkString(",\n")
          f"{\n${entries}\n${pad}}"
        case s: Seq[_] =>
          val entries = s.map(v => f"${innerPad}${toJson(v, indent + 2)}")
            .mkString(",\n")
          f"[\n${entries}\n${pad}]"
        case s: String => f""""${s}""""
        case b: Boolean => b.toString
        case n: Number => n.toString
        case other => f""""${other}""""
      }
    }

    val soc = scala.collection.mutable.LinkedHashMap[String, Any](
      "name" -> name,
      "sys_clk_freq_hz" -> sysClkFreqHz
    )
    val root = scala.collection.mutable.LinkedHashMap[String, Any](
      "soc" -> soc,
      "peripherals" -> peripheralEntries
    )

    val json = toJson(root, 0)
    val pw = new PrintWriter(path)
    pw.write(json)
    pw.write("\n")
    pw.close()
    SpinalInfo(s"HAL JSON dumped to: ${path}")
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
