package spiny.examples.blinky

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

import spiny.vexriscv._
import spiny.Utils.nextPowerOfTwo
import spiny.bus.Apb3BusDef
import spiny.peripheral._

class Blinky extends Component {
  val io = new Bundle {
    val SYS_CLK = in(Bool())
    val CPU_RESET_N = in(Bool())
    val LEDS = out(Bits(16 bits))
  }

  noIoPrefix()

  val sysClkDomain = ClockDomain(
    clock = io.SYS_CLK,
    reset = !io.CPU_RESET_N,
    frequency = FixedFrequency(100 MHz)
  )

  val sysClkArea = new ClockingArea(sysClkDomain) {
    val vexRiscv = RiscvCpu(
      Rv32iRustProfile(withXilinxDebug = true),
      Axi4CpuBusDef
    )

    val ram = Axi4SharedOnChipRam(
      dataWidth = 32,
      byteCount = 4 kB,
      idWidth = 2
    )

    val apbBridge = Axi4SharedToApb3Bridge(
      addressWidth = 32,
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

object TopLevelVerilog {
  def main(args: Array[String]): Unit = {
    val spinalReport = SpinalConfig(
      targetDirectory = "examples/blinky/target/spinal",
      inlineRom = true
    ).generateVerilog(new Blinky())
  }
}
