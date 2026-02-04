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

package spiny.examples.ddrtest

import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc._

import spiny.soc._
import spiny.peripheral._
import spiny.dram._

class DdrTest(
  dramConfigPath: String,
  firmwarePath: String = null
) extends Component {
  val io = new Bundle {
    val SYS_CLK = in(Bool())
    val CPU_RESET_N = in(Bool())
  }

  noIoPrefix()

  // Input clock domain for LiteDram's PLL
  val inputClkDomain = ClockDomain(
    clock = io.SYS_CLK,
    reset = !io.CPU_RESET_N,
    config = ClockDomainConfig(resetActiveLevel = HIGH)
  )

  // Instantiate SpinyDram in input clock domain 
  val dramConfig = LiteDramConfig.fromYaml(dramConfigPath)
  val dram = inputClkDomain on SpinyDram(dramConfig).setName("Dram")

  // Expose DDR physical interface
  dram.io.dram.toIo().setName("dram")

  // SoC runs on user clock domain
  val cpuProfile = SpinyRv32iRustCpuProfile(withXilinxDebug = true) 
  val soc = dram.userClockDomain on new SpinySoC(
    cpuProfile = cpuProfile,
    ramSize = 4 kB,
    firmwarePath = firmwarePath
  ) {
    val gpio = new SpinyGpio(
      Seq(
        SpinyGpioBankConfig(
          width = 8,
          direction = SpinyGpioDirection.Output,
          name = "leds"
        )
      )
    ).setName("Gpio")
    gpio.getBankBits("leds").toIo().setName("LEDS")

    val dramPort = dram.pipelinedMemoryBusPort(
      "port0",
      cpuProfile.busConfig
    )
    val dramBus = cloneOf(dramPort)
    dramBus.cmdM2sPipe().rspPipe() >> dramPort

    build(
      peripherals = Seq(gpio, dram),
      mainBusSlaves = Seq(
        (SizeMapping(0x20000000, dram.ramSize), dramBus)
      )
    )
  }
}

object TopLevelVerilog extends App {
  if (args.length < 1) {
    println("[DdrTest] usage: <dramConfigPath> [firmwarePath]")
    throw new Exception("Missing dramConfigPath")
  }
  val dramConfigPath = args(0)
  println(f"[DdrTest] using DRAM config: ${dramConfigPath}")

  val firmwarePath = if (args.length >= 2) {
    println(f"[DdrTest] using firmware: ${args(1)}")
    args(1)
  } else {
    null
  }

  val spinalReport = SpinalConfig(
    targetDirectory = "target/spinal",
    inlineRom = true
  ).generateVerilog(new DdrTest(
    dramConfigPath = dramConfigPath,
    firmwarePath = firmwarePath
  ))

  val soc = spinalReport.toplevel.soc
  println(soc.peripheralMappings)
  soc.dumpSvd("target/spinal/DdrTest.svd", "DdrTest")
  soc.dumpLinkerScript("target/spinal/memory.x")
}
