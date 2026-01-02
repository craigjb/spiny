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
import spinal.lib.bus.simple._
import spinal.lib.bus.amba3.apb._
import spinal.lib.bus.misc._

import spiny.cpu._
import spiny.peripheral._
import spiny.interconnect._
import spiny.Utils._

class Blinky(sim: Boolean = false) extends Component {
  val io = new Bundle {
    val SYS_CLK = in(Bool())
    val CPU_RESET_N = in(Bool())
    val LEDS = out(Bits(16 bits))
    val SWITCHES = in(Bits(16 bits))
  }

  noIoPrefix()

  val resetClockDomain = ClockDomain(
    clock = io.SYS_CLK,
    config = ClockDomainConfig(
          resetKind = BOOT
    )
  )

  val syncReset = Bool()
  val resetArea = new ClockingArea(resetClockDomain) {
    syncReset := !BufferCC(io.CPU_RESET_N)
  }

  val sysClkDomain = ClockDomain(
    clock = io.SYS_CLK,
    reset = syncReset,
    frequency = FixedFrequency(100 MHz),
    config = ClockDomainConfig(
      resetKind = SYNC,
    )
  )

  sysClkDomain on {
    val cpu = SpinyCpu(SpinyRv32iRustCpuProfile(
        withXilinxDebug = !sim
    ))

    val ram = new SpinyMainRam(
      size = 4 kB,
      cpu.profile.busConfig
    )
    ram.initFromFile("../../examples/blinky/fw/blinky.bin")
    cpu.io.iBus <> ram.io.iBus

    val gpio0 = new SpinyGpio(
      Seq(SpinyGpioBankConfig(
        width = 16,
        direction = SpinyGpioDirection.Output,
        name = "LEDS"
      ))
    )
    io.LEDS := gpio0.getBankBits("LEDS")

    val gpio1 = new SpinyGpio(
      Seq(SpinyGpioBankConfig(
        width = 16,
        direction = SpinyGpioDirection.Input,
        name = "SWITCHES"
      ))
    )
    gpio1.getBankBits("SWITCHES") := io.SWITCHES

    val apb = SpinyApb3Interconnect(
      busConfig = cpu.profile.busConfig,
      baseAddress = 0x10000000,
      peripherals = Seq(gpio0, gpio1)
    )

    val decoder = PipelinedMemoryBusDecoder(
      busConfig = cpu.profile.busConfig,
      mappings = Seq(
        SizeMapping(0x0L, ram.byteCount),
        SizeMapping(apb.baseAddress, apb.mappedSize)
      )
    )
    cpu.io.dBus >> decoder.io.input
    decoder.io.outputs(0) >> ram.io.dBus
    decoder.io.outputs(1) >> apb.masterBus
  }
}

object TopLevelVerilog extends App{
  val spinalReport = SpinalConfig(
    inlineRom = true
  ).generateVerilog(new Blinky())
}

object TopLevelSim extends App {
  SimConfig
    .withWave
    .compile(new Blinky(sim = true))
    .doSim { dut =>
      val clockDomain = ClockDomain(
        clock = dut.io.SYS_CLK,
        reset = dut.io.CPU_RESET_N,
        config = ClockDomainConfig(
          resetActiveLevel = LOW
        )
      )
      clockDomain.forkStimulus(period = 10)
      clockDomain.waitSampling(32768)
    }
}
