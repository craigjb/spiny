package spiny.examples.blinky

import spinal.core._
import spinal.lib._
import spinal.core.sim._
import spinal.lib.bus.simple._
import spinal.lib.bus.amba3.apb._
import spinal.lib.bus.misc._

import spiny.cpu._
import spiny.peripheral._
import spiny.Utils._

class Blinky(sim: Boolean = false) extends Component {
  val io = new Bundle {
    val SYS_CLK = in(Bool())
    val CPU_RESET_N = in(Bool())
    val LEDS = out(Bits(16 bits))
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

    val gpio = new SpinyGpio(
      Seq(SpinyGpioBankConfig(
        width = 16,
        direction = SpinyGpioDirection.Output,
        name = "LEDS"
      ))
    )
    io.LEDS := gpio.getBankBits("LEDS")

    val apbBridge = PipelinedMemoryBusToApbBridge(
      Apb3Config(
        addressWidth = 12,
        dataWidth = 32
      ),
      pipelineBridge = true,
      pipelinedMemoryBusConfig = cpu.profile.busConfig
    )
    apbBridge.io.apb >> gpio.io.apb

    val decoder = PipelinedMemoryBusDecoder(
      busConfig = cpu.profile.busConfig,
      mappings = Seq(
        SizeMapping(0x0L, ram.byteCount),
        SizeMapping(0x10000000, 4 kB)
      )
    )
    cpu.io.dBus >> decoder.io.input
    decoder.io.outputs(0) >> ram.io.dBus
    decoder.io.outputs(1) >> apbBridge.io.pipelinedMemoryBus
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
