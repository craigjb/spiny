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

package spiny.examples.blinky

import spinal.core._
import spinal.lib._
import spinal.core.sim._

import spiny.soc._
import spiny.peripheral._

import java.io.File
import org.rogach.scallop._

class Blinky(
  numLeds: Int = 16,
  numSwitches: Int = 16,
  firmwarePath: String = null
) extends Component {
  val io = new Bundle {
    val SYS_CLK = in(Bool())
    val CPU_RESET_N = in(Bool())
    val LEDS = out(Bits(numLeds bits))
    val SWITCHES = in(Bits(numSwitches bits))
  }

  noIoPrefix()

  val sysClkDomain = ClockDomain(
    clock = io.SYS_CLK,
    reset = ResetCtrl.asyncAssertSyncDeassert(
      input = !io.CPU_RESET_N,
      clockDomain = ClockDomain(io.SYS_CLK)
    ),
    frequency = FixedFrequency(100 MHz),
    config = ClockDomainConfig(
      resetKind = SYNC,
    )
  )

  val soc = sysClkDomain on new SpinySoC(
    cpuProfile = SpinyRv32iRustCpuProfile(),
    ramSize = 8 kB,
    firmwarePath = firmwarePath
  ) {
    val timer = new SpinyTimer(
      timerWidth = 32,
      prescaleWidth = 16,
      numCompares = 2,
      isMachineTimer = true,
    ).setName("Timer")

    val gpio = new SpinyGpio(
      Seq(
        SpinyGpioBankConfig(
          width = numLeds,
          direction = SpinyGpioDirection.Output,
          name = "leds"
        ),
        SpinyGpioBankConfig(
          width = numSwitches,
          direction = SpinyGpioDirection.Input,
          name = "switches"
        )
      )
    ).setName("Gpio")
    io.LEDS := gpio.getBankBits("leds")
    gpio.getBankBits("switches") := io.SWITCHES

    build(peripherals = Seq(
      timer,
      gpio
    ))
  }
}

object TopLevelVerilog extends App {
  object Conf extends ScallopConf(args) {
    val numLeds = opt[Int](default = Some(8))
    val numSwitches = opt[Int](default = Some(8))

    val firmware = opt[File]()
    validateFileExists(firmware)
    validateFileIsFile(firmware)

    verify()
  }
  println(f"[Blinky] TopLevelVerilog.Conf: ${Conf.summary}")

  val spinalReport = SpinalConfig(
    targetDirectory = "target/spinal",
    inlineRom = true
  ).generateVerilog(new Blinky(
    numLeds = Conf.numLeds(),
    numSwitches = Conf.numSwitches(),
    firmwarePath = Conf.firmware.map(f => f.getAbsolutePath()).getOrElse(null)
  ))

  val soc = spinalReport.toplevel.soc
  soc.dumpSvd("Blinky")
  soc.dumpHalCrate(
    path = "target/rust/blinky-hal",
    crateName = "blinky-hal",
    pacCrateName = "blinky-pac",
    pacCratePath = "../blinky-pac",
    spinyHalPath = "../../../../../rust/spiny-hal"
  )
  soc.dumpLinkerScript("target/spinal/memory.x")
}

object TopLevelSim extends App {
  object Conf extends ScallopConf(args) {
    val firmware = trailArg[File]()
    validateFileExists(firmware)
    validateFileIsFile(firmware)
    verify()
  }
  println(f"[Blinky] TopLevelSim.Conf: ${Conf.summary}")

  SimConfig
    .withWave
    .compile(new Blinky(
      firmwarePath = Conf.firmware.map(f => f.getAbsolutePath()).getOrElse(null)
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

      dut.io.SWITCHES #= 0xc3 

      clockDomain.forkStimulus()
      clockDomain.waitSampling(100000)
    }
}
