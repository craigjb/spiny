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
import spinal.lib.bus.amba4.axi._
import spinal.core.sim._

import spiny.soc._
import spiny.peripheral._
import spiny.dram._

class DdrTest(
  dramConfigPath: String,
  dramSvdPath: String = null,
  firmwarePath: String = null,
  sim: Boolean = false
) extends Component {
  val io = new Bundle {
    val SYS_CLK = in(Bool())
    val CPU_RESET_N = in(Bool())
    val LEDS = out(Bits(8 bits))
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
  val dram = inputClkDomain on SpinyDram(
    dramConfig,
    svdPath = Option(dramSvdPath),
    sim = sim
  ).setName("Dram")

  // Expose DDR physical interface
  if (!sim) {
    dram.io.dram.toIo().setName("dram")
  }

  // SoC runs on user clock domain
  val cpuProfile = SpinyRv32iRustCpuProfile() 
  val soc = dram.userClockDomain on new SpinySoC(
    cpuProfile = cpuProfile,
    ramSize = 16 kB,
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
    io.LEDS := gpio.getBankBits("leds")

    val dramPort = dram.axi4Port("port0")

    val upsizer = Axi4Upsizer(
      inputConfig = cpuProfile.axiConfig,
      outputConfig = cpuProfile.axiConfig.copy(
        dataWidth = dramPort.config.dataWidth
      ),
      readPendingQueueSize = 4
    )
    upsizer.io.output >> dramPort
    val dramBus = cloneOf(upsizer.io.input)
    dramBus.pipelined(StreamPipe.FULL) >> upsizer.io.input

    build(
      peripherals = Seq(gpio, dram),
      mainBusAxi4Slaves = Seq(
        (SizeMapping(0x20000000, dram.ramSize), dramBus)
      )
    )
  }
}

object TopLevelVerilog extends App {
  if (args.length < 1) {
    println("[DdrTest] usage: <dramConfigPath> [dramSvdPath] [firmwarePath]")
    throw new Exception("Missing dramConfigPath")
  }
  val dramConfigPath = args(0)
  println(f"[DdrTest] using DRAM config: ${dramConfigPath}")

  val dramSvdPath = if (args.length >= 2) {
    println(f"[DdrTest] using DRAM SVD: ${args(1)}")
    args(1)
  } else {
    null
  }

  val firmwarePath = if (args.length >= 3) {
    println(f"[DdrTest] using firmware: ${args(2)}")
    args(2)
  } else {
    null
  }

  val spinalReport = SpinalConfig(
    targetDirectory = "target/spinal",
    inlineRom = true
  ).generateVerilog(new DdrTest(
    dramConfigPath = dramConfigPath,
    dramSvdPath = dramSvdPath,
    firmwarePath = firmwarePath
  ))

  val soc = spinalReport.toplevel.soc
  println(soc.peripheralMappings)
  soc.dumpSvd("target/spinal/DdrTest.svd", "DdrTest")
  soc.dumpLinkerScript("target/spinal/memory.x")
}

object TopLevelSim extends App {
  if (args.length < 3) {
    println("[DdrTest] usage: <dramConfigPath> <dramVerilogPath> <firmwarePath>")
    throw new Exception("Missing arguments")
  }
  val dramConfigPath = args(0)
  println(f"[DdrTest] using DRAM config: ${dramConfigPath}")
  val dramVerilogPath = args(1)
  println(f"[DdrTest] using DRAM verilog: ${dramVerilogPath}")
  val firmwarePath = args(2)
  println(f"[DdrTest] using firmware : ${firmwarePath}")

  SimConfig
    .withVerilator
    .withWave
    .addRtl(dramVerilogPath)
    .addSimulatorFlag("-Wno-CASEINCOMPLETE")
    .addSimulatorFlag("-Wno-COMBDLY")
    .compile(new DdrTest(
      dramConfigPath = dramConfigPath,
      firmwarePath = firmwarePath,
      sim = true
    ))
    .doSim { dut =>
      val clockDomain = ClockDomain(
        clock = dut.io.SYS_CLK,
        reset = dut.io.CPU_RESET_N,
        frequency = FixedFrequency(100 MHz),
        config = ClockDomainConfig(
          resetActiveLevel = LOW
        )
      )

      clockDomain.forkStimulus()
      clockDomain.waitSamplingWhere(dut.io.LEDS.toInt == 0xFF)
      clockDomain.waitSampling(10)
      clockDomain.waitSamplingWhere(dut.io.LEDS.toInt == 0xFF)
      clockDomain.waitSampling(10)
      clockDomain.waitSamplingWhere(dut.io.LEDS.toInt == 0xFF)
    }
}
