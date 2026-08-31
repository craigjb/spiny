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

package spiny.examples.dptest

import java.io.File
import org.rogach.scallop._

import spinal.core._
import spinal.lib._
import spinal.lib.blackbox.xilinx.s7._

import spiny.ClockGen
import spiny.platform.xilinx._
import spiny.soc._
import spiny.peripheral._
import spiny.displayport._

class DpTest(
  numLeds: Int = 8,
  socFreq: HertzNumber = 70.MHz,
  firmwarePath: String = null
) extends Component {
  val io = new Bundle {
    val SYS_CLK = in(Bool())
    val CPU_RESET_N = in(Bool())
    val LEDS = out(Bits(numLeds bits))
    val HPD = in(Bool())
    val AUX_P = inout(Analog(Bool))
    val AUX_N = inout(Analog(Bool))
    val UNUSED_P = in(Bool())
    val UNUSED_N = in(Bool())

    // enough to watch a transaction on a scope, the rest goes over defmt
    val DBG_AUX_FILTERED = out(Bool())
    val DBG_AUX_WRITE_EN = out(Bool())
  }

  noIoPrefix()

  val inputClkDomain = ClockDomain(
    clock = io.SYS_CLK,
    reset = io.CPU_RESET_N,
    config = ClockDomainConfig(resetActiveLevel = LOW),
    frequency = FixedFrequency(100 MHz)
  )

  val clockGen = inputClkDomain on ClockGen()
  val socClkDomain = clockGen.request(socFreq)
  clockGen.build()

  val soc = socClkDomain on new SpinySoC(
    cpuProfile = SpinyRv32iRustCpuProfile(withXilinxDebug = true),
    ramSize = 32 kB,
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
        )
      )
    ).setName("Gpio")
    io.LEDS := gpio.getBankBits("leds")

    val displayPort = new SpinyDisplayPortSource().setName("DisplayPort")
    displayPort.io.hpd := io.HPD

    val auxIoBuf = IOBUFDS.on(displayPort.io.aux, io.AUX_P, io.AUX_N)

    // pull the filtered line so captures are not full of glitches 
    // that AuxRx already rejects
    io.DBG_AUX_FILTERED := displayPort.phy.rx.readValue.pull()
    io.DBG_AUX_WRITE_EN := displayPort.io.aux.writeEnable

    build(peripherals = Seq(
      timer,
      gpio,
      displayPort
    ))
  }
}

object TopLevelVerilog extends App {
  object Conf extends ScallopConf(args) {
    val numLeds = opt[Int](default = Some(8))
    val freqMhz = opt[BigDecimal](required = true)
    val firmware = opt[File]()
    validateFileExists(firmware)
    validateFileIsFile(firmware)

    verify()
  }
  println(f"[DpTest] TopLevelVerilog.Conf: ${Conf.summary}")

  val spinalReport = SpinalConfig(
    targetDirectory = "target/spinal",
    inlineRom = true
  ).generateVerilog(new DpTest(
    numLeds = Conf.numLeds(),
    socFreq = Conf.freqMhz().MHz,
    firmwarePath = Conf.firmware.map(f => f.getAbsolutePath()).getOrElse(null)
  ))

  val soc = spinalReport.toplevel.soc
  println(soc.peripheralMappings)
  soc.dumpSvd("DpTest")
  soc.dumpLinkerScript()
  soc.dumpHalCrate(
    "target/rust/dptest-hal", "dptest-hal",
    "dptest-pac", "../dptest-pac", "../../../../../rust/spiny-hal"
  )
}
