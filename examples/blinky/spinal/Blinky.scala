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

class Blinky(
  sim: Boolean = false,
  firmwarePath: String = null
) extends Component {
  val io = new Bundle {
    val SYS_CLK = in(Bool())
    val CPU_RESET_N = in(Bool())
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
    cpuProfile = SpinyRv32iRustCpuProfile(withXilinxDebug = !sim),
    ramSize = 4 kB,
    firmwarePath = firmwarePath
  ) {
    val timer = new SpinyTimer(
      timerWidth = 32,
      prescaleWidth = 16,
      numCompares = 1,
      isMachineTimer = true,
      simSpeedUp = if (sim) 10000 else 1
    ).setName("Timer")

    val gpio = new SpinyGpio(
      Seq(
        SpinyGpioBankConfig(
          width = 16,
          direction = SpinyGpioDirection.Output,
          name = "leds"
        ),
        SpinyGpioBankConfig(
          width = 16,
          direction = SpinyGpioDirection.Input,
          name = "switches"
        )
      )
    ).setName("Gpio")
    gpio.getBankBits("leds").toIo().setName("LEDS")
    gpio.getBankBits("switches").toIo().setName("SWITCHES")

    build(peripherals = Seq(
      timer,
      gpio
    ))
  }
}

object TopLevelVerilog extends App {
  val firmwarePath = if (args.length == 1) {
    println(f"[Blinky] using firmware: ${args(0)}")
    args(0)
  } else {
    null
  }

  val spinalReport = SpinalConfig(
    targetDirectory = "target/spinal",
    inlineRom = true
  ).generateVerilog(new Blinky(
    sim = false,
    firmwarePath = firmwarePath
  ))

  val soc = spinalReport.toplevel.soc
  soc.dumpSvd("target/spinal/Blinky.svd", "Blinky")
  soc.dumpLinkerScript("target/spinal/memory.x")
}

object TopLevelSim extends App {
  val firmwarePath = if (args.length == 1) {
    println(f"[Blinky Sim] using firmware: ${args(0)}")
    args(0)
  } else {
    null
  }

  SimConfig
    .withWave
    .compile(new Blinky(sim = true, firmwarePath = firmwarePath))
    .doSim { dut =>
      val clockDomain = ClockDomain(
        clock = dut.io.SYS_CLK,
        reset = dut.io.CPU_RESET_N,
        config = ClockDomainConfig(
          resetActiveLevel = LOW
        )
      )

      clockDomain.forkStimulus(period = 10)
      clockDomain.waitSampling(100000)
    }
}
