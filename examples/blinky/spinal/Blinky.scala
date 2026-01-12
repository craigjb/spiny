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
import spinal.lib.blackbox.xilinx.s7._

import spiny.soc._
import spiny.peripheral._

class Blinky(
  sim: Boolean = false,
  firmwarePath: String = null
) extends Component {
  val io = new Bundle {
    val SYS_CLK = in(Bool())
    val CPU_RESET_N = in(Bool())
    val RMII_PHY_RESET_N = out(Bool())
    val RMII_CLK = out(Bool())
  }

  noIoPrefix()

  val clkInFreq = 100 MHz
  val clkInPeriodNs = clkInFreq.toTime.toBigDecimal / 1e-9
  val mmcm = MMCME2_BASE(
    CLKIN1_PERIOD = clkInPeriodNs.toDouble,
    CLKFBOUT_MULT_F = 10.0,
    CLKOUT0_DIVIDE_F = 10.0,
    CLKOUT1_DIVIDE = 20.0,
    CLKOUT2_DIVIDE = 20.0,
    CLKOUT2_PHASE = -90.0
  )
  mmcm.CLKIN1 := io.SYS_CLK
  mmcm.CLKFBIN := BUFG.on(mmcm.CLKFBOUT)
  mmcm.RST := !io.CPU_RESET_N
  mmcm.PWRDWN := False
  val rawReset = !mmcm.LOCKED

  val sysClk = BUFG.on(mmcm.CLKOUT0)
  val sysClkDomain = ClockDomain(
    clock = sysClk,
    reset = ResetCtrl.asyncAssertSyncDeassert(
      input = rawReset,
      clockDomain = ClockDomain(sysClk)
    ),
    frequency = FixedFrequency(100 MHz),
    config = ClockDomainConfig(
      resetKind = SYNC,
    )
  )

  val rmiiClk = BUFG.on(mmcm.CLKOUT1)
  val rmiiClkDomain = ClockDomain(
    clock = rmiiClk,
    reset = ResetCtrl.asyncAssertSyncDeassert(
      input = rawReset,
      clockDomain = ClockDomain(rmiiClk)
    ),
    frequency = FixedFrequency(50 MHz),
    config = ClockDomainConfig(
      resetKind = SYNC,
    )
  )

  val rmiiClkFwdDomain = ClockDomain(BUFG.on(mmcm.CLKOUT2))

  val soc = sysClkDomain on new SpinySoC(
    cpuProfile = SpinyRv32iRustCpuProfile(withXilinxDebug = !sim),
    ramSize = 4 kB,
    firmwarePath = firmwarePath
  ) {
    val timer = new SpinyTimer(
      timerWidth = 32,
      prescaleWidth = 16,
      numCompares = 1,
      isMachineTimer = true
    ).setName("Timer")

    val gpio0 = new SpinyGpio(
      Seq(SpinyGpioBankConfig(
        width = 16,
        direction = SpinyGpioDirection.Output,
        name = "leds"
      ))
    ).setName("Gpio0")
    gpio0.getBankBits("leds").toIo().setName("LEDS")

    val gpio1 = new SpinyGpio(
      Seq(SpinyGpioBankConfig(
        width = 16,
        direction = SpinyGpioDirection.Input,
        name = "switches"
      ))
    ).setName("Gpio1")
    gpio1.getBankBits("switches").toIo().setName("SWITCHES")

    val eth = new SpinyEthernet(
      rxCd = rmiiClkDomain,
      txCd = rmiiClkDomain
    ).setName("Eth")
    eth.io.rmii.toIo().setName("RMII")
    io.RMII_PHY_RESET_N := !eth.io.phyReset
    io.RMII_CLK := eth.rmiiClkOutXilinxOddr(oddrCd = rmiiClkFwdDomain)

    build(peripherals = Seq(
      timer,
      gpio0,
      gpio1,
      eth
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
