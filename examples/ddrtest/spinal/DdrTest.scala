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

import java.io.File
import org.rogach.scallop._

object DdrTestConfigs {
  val sim = LiteDramConfig(
    name = "Ddr3Ctrl",
    memType = DramMemType.Ddr3,
    moduleName = "SimModule",
    geometry = DramGeometry(numBanks = 2, numRows = 2048, numCols = 16),
    timings = DramTimings(
      tREFI = 64e6 / 8192,
      tWTR = DramTiming(cycles = Some(4), timeNs = Some(7.5)),
      tCCD = DramTiming(cycles = Some(4)),
      tRRD = Some(DramTiming(cycles = Some(4), timeNs = Some(10))),
      tZQCS = Some(DramTiming(cycles = Some(64), timeNs = Some(80))),
      tRP = 13.75, tRCD = 13.75, tWR = 13.75,
      tRFC = DramTiming(cycles = Some(208)),
      tFAW = Some(DramTiming(timeNs = Some(40))),
      tRAS = Some(35),
    ),
    numByteGroups = 1,
    numRanks = 1,
    phy = DramPhy.A7DDRPHY,
    fpgaSpeedgrade = -1,
    inputClkFreq = 100 MHz,
    userClkFreq = 80 MHz,
    iodelayClkFreq = 200 MHz,
    cmdBufferDepth = 16,
    withChipSelects = false,
    rttNom = Some("60ohm"),
    rttWr = Some("60ohm"),
    ron = Some("34ohm"),
  )

  val nexysVideo = LiteDramConfig(
    name = "Ddr3Ctrl",
    memType = DramMemType.Ddr3,
    moduleName = "MT41K256M16",
    geometry = DramGeometry(numBanks = 8, numRows = 32768, numCols = 1024),
    timings = DramTimings(
      tREFI = 64e6 / 8192,
      tWTR = DramTiming(cycles = Some(4), timeNs = Some(7.5)),
      tCCD = DramTiming(cycles = Some(4)),
      tRRD = Some(DramTiming(cycles = Some(4), timeNs = Some(10))),
      tZQCS = Some(DramTiming(cycles = Some(64), timeNs = Some(80))),
      tRP = 13.75, tRCD = 13.75, tWR = 13.75,
      tRFC = DramTiming(cycles = Some(208)),
      tFAW = Some(DramTiming(timeNs = Some(40))),
      tRAS = Some(35),
    ),
    numByteGroups = 2,
    numRanks = 1,
    phy = DramPhy.A7DDRPHY,
    fpgaSpeedgrade = -1,
    inputClkFreq = 100 MHz,
    userClkFreq = 80 MHz,
    iodelayClkFreq = 200 MHz,
    cmdBufferDepth = 16,
    withChipSelects = false,
    rttNom = Some("60ohm"),
    rttWr = Some("60ohm"),
    ron = Some("34ohm"),
  )

  val nexysA7 = LiteDramConfig(
    name = "Ddr2Ctrl",
    memType = DramMemType.Ddr2,
    moduleName = "MT47H64M16",
    geometry = DramGeometry(numBanks = 8, numRows = 8192, numCols = 1024),
    timings = DramTimings(
      tREFI = 64e6 / 8192,
      tWTR = DramTiming(timeNs = Some(7.5)),
      tCCD = DramTiming(cycles = Some(2)),
      tRP = 15, tRCD = 15, tWR = 15,
      tRFC = DramTiming(timeNs = Some(127.5)),
    ),
    numByteGroups = 2,
    numRanks = 1,
    phy = DramPhy.A7DDRPHY,
    fpgaSpeedgrade = -1,
    inputClkFreq = 100 MHz,
    userClkFreq = 80 MHz,
    iodelayClkFreq = 200 MHz,
    cmdBufferDepth = 16,
  )

  def fromTarget(target: String): LiteDramConfig = target match {
    case "sim" => sim
    case "nexys_video" => nexysVideo
    case "nexys_a7" => nexysA7
    case _ => throw new IllegalArgumentException(
      s"Unknown target: $target. Available: nexys_video, nexys_a7")
  }
}

class DdrTest(
  dramConfig: LiteDramConfig,
  firmwarePath: String = null,
  dramSvdPath: Option[String] = None,
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
  val dram = inputClkDomain on SpinyDram(
    dramConfig,
    sim = sim,
    svdPath = dramSvdPath
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

    val dramPort = dram.axi4Port("port0", idWidth = 4, cpuProfile.axiConfig)
    val dramBus = Axi4Shared(cpuProfile.axiConfig)
    dramBus.pipelined(
      arw = StreamPipe.FULL,
      w = StreamPipe.FULL,
      b = StreamPipe.FULL,
      r = StreamPipe.FULL
    ).toAxi4() >> dramPort

    val dramDataBase = BigInt(0x20000000)
    dram.dataBaseAddress = Some(dramDataBase)

    build(
      peripherals = Seq(gpio, dram),
      mainBusSlaves = Seq(
        (SizeMapping(dramDataBase, dram.ramSize), dramBus)
      )
    )
  }

  dram.build()
}

object TopLevelVerilog extends App {
  object Conf extends ScallopConf(args) {
    val target = trailArg[String]()
    val firmware = opt[File]()
    validateFileExists(firmware)
    validateFileIsFile(firmware)
    val dramSvd = opt[File]()
    validateFileExists(dramSvd)
    validateFileIsFile(dramSvd)
    verify()
  }
  println(f"[DdrTest] TopLevelVerilog.Conf: ${Conf.summary}")

  val dramConfig = DdrTestConfigs.fromTarget(Conf.target())

  val spinalReport = SpinalConfig(
    targetDirectory = "target/spinal",
    inlineRom = true
  ).generateVerilog(new DdrTest(
    dramConfig = dramConfig,
    firmwarePath = Conf.firmware.map(f => f.getAbsolutePath()).getOrElse(null),
    dramSvdPath = Conf.dramSvd.map(f => f.getAbsolutePath()).toOption,
    sim = Conf.target() == "sim"
  ))

  spinalReport.toplevel.dram.dumpConfig("target/spinal/litedram_config.yaml")

  val soc = spinalReport.toplevel.soc
  println(soc.peripheralMappings)
  soc.dumpSvd("target/spinal/DdrTest.svd", "DdrTest")
  soc.dumpLinkerScript("target/spinal/memory.x")
  soc.dumpHalCrate(
    "target/rust/ddrtest-hal", "ddrtest-hal",
    "ddrtest-pac", "../ddrtest-pac", "../../../../../rust/spiny-hal"
  )
}

object TopLevelSim extends App {
  object Conf extends ScallopConf(args) {
    val dramRtl = trailArg[File]()
    validateFileExists(dramRtl)
    validateFileIsFile(dramRtl)
    val firmware = trailArg[File]()
    validateFileExists(firmware)
    validateFileIsFile(firmware)
    verify()
  }
  println(f"[DdrTest] TopLevelSim.Conf: ${Conf.summary}")

  SimConfig
    .withVerilator
    .withWave
    .addRtl(Conf.dramRtl().getAbsolutePath())
    .addSimulatorFlag("-Wno-CASEINCOMPLETE")
    .addSimulatorFlag("-Wno-COMBDLY")
    .compile(new DdrTest(
      dramConfig = DdrTestConfigs.sim,
      firmwarePath = Conf.firmware().getAbsolutePath(),
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
      clockDomain.waitSamplingWhere(dut.io.LEDS.toInt == 0xAA)
      clockDomain.waitSampling(10)
      clockDomain.waitSamplingWhere(dut.io.LEDS.toInt == 0xBB)

      // wait for mem test
      val memSize = dut.dram.ramSize
      println(s"Waiting on DDR mem test. Memory size: ${memSize}")
      SimTimeout(6 ms)
      val numChunks = 8
      for (i <- 1 to numChunks) {
        val expected = (1 << i) - 1
        clockDomain.waitSamplingWhere(dut.io.LEDS.toInt == expected)
        println(s"$i of $numChunks chunks done")
      }
    }
}
