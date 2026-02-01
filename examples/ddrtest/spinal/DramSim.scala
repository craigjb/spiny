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
import spinal.core.sim._
import spinal.lib.bus.simple._
import spinal.lib.bus.amba3.apb._
import spinal.lib.bus.amba3.apb.sim._

import spiny.dram._
import spiny.sim._

case class DramSim() extends Component {
  val dramConfig = LiteDramConfig.fromYaml("data/Ddr2Sim.yaml")
  val dramCtrl = SpinyDram(dramConfig, sim = true).setName("Dram")

  val nativePort = dramCtrl.nativePort("port0")

  val pmbConfig = PipelinedMemoryBusConfig(addressWidth = 32, dataWidth = 32)
  val pmbPort = dramCtrl.pipelinedMemoryBusPort("port1", pmbConfig)

  val io = new Bundle {
    val apb = slave(Apb3(dramCtrl.apb3Config))
    val port = slave(cloneOf(nativePort))
    val pmb = slave(cloneOf(pmbPort))
  }

  val userClockDomain = dramCtrl.userClockDomain

  dramCtrl.io.initDone.toIo().setName("INIT_DONE")
  dramCtrl.io.initError.toIo().setName("INIT_ERROR")
  io.apb >> dramCtrl.io.apb
  io.port <> nativePort

  io.pmb >> pmbPort
}

object DramSim extends App {
  val CsrDdrCtrlInitDoneAddr = 0x0000
  val CsrDdrCtrlInitErrorAddr = 0x0004

  val CsrSdramDfiiControlAddr = 0x800
  val DfiiControlSel = 0x01
  val DfiiControlCke = 0x02
  val DfiiControlOdt = 0x04
  val DfiiControlResetN = 0x08

  val CsrSdramDfiiPi0CommandAddr = 0x804
  val DfiiCmdCs = 0x01
  val DfiiCmdWe = 0x02
  val DfiiCmdCas = 0x04
  val DfiiCmdRas = 0x08

  val CsrSdramDfiiPi0CommandIssue = 0x808
  val CsrSdramDfiiPi0AddressAddr = 0x80C
  val CsrSdramDfiiPi0BAddressAddr = 0x810

  SimConfig
    .withVerilator
    .withWave
    .addRtl("target/litedram/Ddr2Sim.v")
    // Disable two lints in Verilator
    // Otherwise LiteDRAM has too many warnings
    .addSimulatorFlag("-Wno-CASEINCOMPLETE")
    .addSimulatorFlag("-Wno-COMBDLY")
    .compile(DramSim())
    .doSim { dut =>
      dut.clockDomain.forkStimulus(100 MHz)
      val userClk = dut.userClockDomain
      val apbDriver = Apb3Driver(dut.io.apb, dut.clockDomain)
      val portDriver = DramNativePortDriver(dut.io.port, dut.clockDomain)
      val busDriver = PipelinedMemoryBusDriver(dut.io.pmb, dut.clockDomain)
      dut.clockDomain.waitSampling(10)

      def command_p0(addr: BigInt, baddr: BigInt, cmd: BigInt) {
        apbDriver.write(CsrSdramDfiiPi0AddressAddr, addr)
        apbDriver.write(CsrSdramDfiiPi0BAddressAddr, baddr)
        apbDriver.write(CsrSdramDfiiPi0CommandAddr, cmd)
        apbDriver.write(CsrSdramDfiiPi0CommandIssue, 1)
      }

      println("[DRAM Init] Switch DFII to software control")
      apbDriver.write(
        CsrSdramDfiiControlAddr, 
        DfiiControlCke | DfiiControlOdt | DfiiControlResetN
      )

      println("[DRAM Init] Bring CKE high")
      apbDriver.write(CsrSdramDfiiPi0AddressAddr, 0x0)
      apbDriver.write(CsrSdramDfiiPi0BAddressAddr, 0)
      apbDriver.write(
        CsrSdramDfiiControlAddr, 
        DfiiControlCke | DfiiControlOdt | DfiiControlResetN
      )
      dut.clockDomain.waitSampling(20000)

      println("[DRAM Init] Clear init done and error")
      apbDriver.write(CsrDdrCtrlInitDoneAddr, 0)
      apbDriver.write(CsrDdrCtrlInitErrorAddr, 0)

      println("[DRAM Init Sequence] Precharge all")
      command_p0(0x400, 0, DfiiCmdRas | DfiiCmdWe | DfiiCmdCs)
      
      println("[DRAM Init Sequence] Load extended mode register 3")
      command_p0(0x0, 3, DfiiCmdRas | DfiiCmdCas | DfiiCmdWe | DfiiCmdCs)

      println("[DRAM Init Sequence] Load extended mode register 2")
      command_p0(0x0, 2, DfiiCmdRas | DfiiCmdCas | DfiiCmdWe | DfiiCmdCs)

      println("[DRAM Init Sequence] Load extended mode register")
      command_p0(0x0, 1, DfiiCmdRas | DfiiCmdCas | DfiiCmdWe | DfiiCmdCs)

      println("[DRAM Init Sequence] Reset DLL, CL=3, BL=4")
      command_p0(0x532, 0, DfiiCmdRas | DfiiCmdCas | DfiiCmdWe | DfiiCmdCs)
      dut.clockDomain.waitSampling(200)

      println("[DRAM Init Sequence] Precharge all")
      command_p0(0x400, 0, DfiiCmdRas | DfiiCmdWe | DfiiCmdCs)

      println("[DRAM Init Sequence] Auto refresh")
      command_p0(0x0, 0, DfiiCmdRas | DfiiCmdCas | DfiiCmdCs)
      dut.clockDomain.waitSampling(10)

      println("[DRAM Init Sequence] Auto refresh")
      command_p0(0x0, 0, DfiiCmdRas | DfiiCmdCas | DfiiCmdCs)
      dut.clockDomain.waitSampling(10)

      println("[DRAM Init Sequence] Load mode register, CL=3, BL=4")
      command_p0(0x432, 0, DfiiCmdRas | DfiiCmdCas | DfiiCmdWe | DfiiCmdCs)
      dut.clockDomain.waitSampling(200)

      println("[DRAM Init Sequence] Load extended mode register, OCD default")
      command_p0(0x380, 1, DfiiCmdRas | DfiiCmdCas | DfiiCmdWe | DfiiCmdCs)

      println("[DRAM Init Sequence] Load extended mode register, OCD exit")
      command_p0(0x0, 1, DfiiCmdRas | DfiiCmdCas | DfiiCmdWe | DfiiCmdCs)

      println("[DRAM Init] Switch DFII to hardware control")
      apbDriver.write(CsrSdramDfiiControlAddr, DfiiControlSel)

      println("[DRAM Init] Set init done bit")
      apbDriver.write(CsrDdrCtrlInitDoneAddr, 1)

      println("[DRAM Init] Read back init done bit")
      val initBit = apbDriver.read(CsrDdrCtrlInitDoneAddr)
      println(f"[DRAM Init] init done bit = ${initBit}")

      dut.clockDomain.waitSampling(200)

      portDriver.write(0x0, BigInt("13371337", 16))
      portDriver.write(0x1, BigInt("DEADBEEF", 16))
      portDriver.write(0x2, BigInt("80087388", 16))
      portDriver.write(0x3, BigInt("BEEFDEAD", 16))

      dut.clockDomain.waitSampling(10)

      portDriver.issueRead(0x0)
      portDriver.issueRead(0x1)
      portDriver.issueRead(0x2)
      portDriver.issueRead(0x3)

      dut.clockDomain.waitSampling(30)

      busDriver.write(0x10, BigInt("01234567", 16))
      busDriver.write(0x14, BigInt("12345678", 16))
      busDriver.write(0x18, BigInt("23456789", 16))
      busDriver.write(0x1C, BigInt("34567890", 16))

      dut.clockDomain.waitSampling(10)

      busDriver.issueRead(0x10)
      busDriver.issueRead(0x14)
      busDriver.issueRead(0x18)
      busDriver.issueRead(0x1C)

      dut.clockDomain.waitSampling(30)

      busDriver.read(0x0)

      dut.clockDomain.waitSampling(100)
    }
}
