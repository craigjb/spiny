package spiny.examples.blinky

import spinal.core._
import spinal.lib._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi._

import spiny.vexriscv._
import spiny.Utils._
import spiny.bus.Apb3BusDef
import spiny.peripheral._

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

  val sysClkArea = new ClockingArea(sysClkDomain) {
    val vexRiscv = RiscvCpu(
      Rv32iRustProfile(withXilinxDebug = !sim),
      Axi4CpuBusDef
    )

    val ram = Axi4SharedOnChipRam(
      dataWidth = 32,
      byteCount = 4 kB,
      idWidth = 2
    )

    val firmware = read32BitMemFromFile("../../examples/blinky/fw/blinky.bin")
    ram.ram.init(
      firmware
        .padTo(ram.wordCount.toInt, 0.toBigInt)
        .map(w => B(w, 32 bits))
    )

    val apbBridge = Axi4SharedToApb3Bridge(
      addressWidth = 17,
      dataWidth = 32,
      idWidth = 2
    )

    val axiCrossbar = Axi4CrossbarFactory()
    val apbBase = 0x10000000L
    axiCrossbar.addSlaves(
      ram.io.axi -> (
        vexRiscv.profile.resetVector,
        nextPowerOfTwo(ram.byteCount)
      ),
      apbBridge.io.axi -> (
        apbBase,
        4 kB
      )
    )
    axiCrossbar.addConnections(
      vexRiscv.io.iBus -> List(ram.io.axi),
      vexRiscv.io.dBus -> List(ram.io.axi, apbBridge.io.axi)
    )
    axiCrossbar.addPipelining(apbBridge.io.axi)((crossbar, bridge) => {
      crossbar.sharedCmd.halfPipe() >> bridge.sharedCmd
      crossbar.writeData.halfPipe() >> bridge.writeData
      crossbar.writeRsp << bridge.writeRsp
      crossbar.readRsp << bridge.readRsp
    })
    axiCrossbar.build()

    val apbBusDef = Apb3BusDef(apbBridge.apbConfig)
    val gpio = new Gpio(
      apbBusDef,
      Seq(GpioBankConfig(
        width = 16,
        direction = GpioDirection.Output,
        name = "LEDS"
      ))
    )
    io.LEDS := gpio.getBits(0)

    apbBridge.io.apb <> gpio.io.bus
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
