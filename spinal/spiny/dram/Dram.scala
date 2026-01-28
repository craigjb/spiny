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

package spiny.dram

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb._
import spinal.lib.bus.simple._

import spiny.peripheral.SpinyPeripheral

/**
 * SpinyDram - Wrapper around LiteDram BlackBox
 *
 * @param config LiteDRAM configuration
 * @param sim LiteDRAM sim mode with internal DRAM model
 * @param ctrlAddressWidth Address width for APB3 control bus
 */
case class SpinyDram(
    config: LiteDramConfig,
    sim: Boolean = false,
    ctrlAddressWidth: Int = 16
) extends Component with SpinyPeripheral {

  val apb3Config = Apb3Config(
    addressWidth = ctrlAddressWidth,
    dataWidth = 32
  )

  val io = new Bundle {
    // Status outputs
    val pllLocked = (!sim) generate (out Bool())
    val initDone = out Bool()
    val initError = out Bool()

    // APB3 control bus
    val apb = slave(Apb3(apb3Config))

    // Native ports
    val nativePorts = LiteDram.createNativePorts(config).map {
      case (name, port) => name -> slave(port)
    }

    // DDR physical interface
    val dram = (!sim).generate {
      master(LiteDram.createDramIo(config))
    }
  }

  // Ctrl bus is mappable as a SpinyPeripheral
  peripheralBus = io.apb
  peripheralMappedSize = BigInt(1) << ctrlAddressWidth

  val liteDram = LiteDram(config, sim = sim)
  if (!sim) {
    io.pllLocked := liteDram.io.pllLocked
  }
  io.initDone := liteDram.io.initDone
  io.initError := liteDram.io.initError

  if (sim) {
    liteDram.io.simTrace := True
  }

  // APB3 to Wishbone bridge (internal, non-bursting)
  val apbToWbBridge = new Area {
    val wb = liteDram.io.wbCtrl
    val apb = io.apb

    // Address translation: APB (Byte) -> WB (Word)
    // Drop bottom 2 bits for 32-bit word addressing
    wb.ADR := apb.PADDR(ctrlAddressWidth - 1 downto 2).resized

    wb.DAT_MOSI := apb.PWDATA
    apb.PRDATA := wb.DAT_MISO
    wb.CYC := apb.PSEL(0)
    // Only assert STB during APB access phase (PENABLE high)
    wb.STB := apb.PSEL(0) && apb.PENABLE
    wb.WE := apb.PWRITE

    // Full 32-bit word transfers
    wb.SEL := B"1111"

    // Classic (non-burst) cycle
    wb.CTI := B"000"
    wb.BTE := B"00"

    // Handshaking
    apb.PREADY := wb.ACK || wb.ERR
    apb.PSLVERROR := wb.ERR
  }

  // Connect native ports
  for ((portName, port) <- io.nativePorts) {
    val liteDramPort = liteDram.io.userPorts(portName).asInstanceOf[NativePort]
    liteDramPort <> port
  }

  // Connect physical interface
  if (!sim) {
    io.dram <> liteDram.io.dram
  }

  /**
   * User clock domain from LiteDram.
   * Use this for logic that interfaces with the DRAM controller.
   */
  def userClockDomain: ClockDomain = liteDram.userClockDomain

  /**
   * Type-safe accessor for a native port by name.
   */
  def nativePort(name: String): NativePort = {
    io.nativePorts.getOrElse(name,
      throw new IllegalArgumentException(
        s"Native port '$name' not found. Available: ${io.nativePorts.keys.mkString(", ")}"
      ))
  }

  /**
   * Create a PipelinedMemoryBus port using a bridge
   *
   * @param portName Name of the native port to bridge
   * @param busConfig PipelinedMemoryBus configuration (must match port data width)
   */
  def pipelinedMemoryBusPort(
    portName: String,
    busConfig: PipelinedMemoryBusConfig
  ): PipelinedMemoryBus = {
    val port = nativePort(portName)
    val bridge = PipelinedMemoryBusToNativePortBridge(port, busConfig)
    bridge.bus
  }
}

/**
 * Bridge from PipelinedMemoryBus to LiteDRAM NativePort.
 */
case class PipelinedMemoryBusToNativePortBridge(
    nativePort: NativePort,
    busConfig: PipelinedMemoryBusConfig
) extends Area {
  assert(nativePort.dataWidth == busConfig.dataWidth,
    "PipelinedMemoryBus port width must match native port width")

  val bus = PipelinedMemoryBus(busConfig)
  val addrOffset = log2Up(nativePort.dataWidth / 8)

  // Input stream only fires once both outputs have fired
  val (cmdStream, writeStreamFork) = StreamFork2(bus.cmd)

  // Only wait on writeStream when it's a write
  val writeStream = writeStreamFork.throwWhen(!writeStreamFork.payload.write)

  // Native port command channel
  nativePort.cmd.valid := cmdStream.valid
  nativePort.cmd.payload.we := cmdStream.payload.write

  val addrSize = nativePort.cmd.addr.getBitsWidth
  val resizedAddr = (cmdStream.payload.address >> addrOffset).resize(addrSize)
  
  // Workaround for LiteDRAM batching
  val dirtyBit = RegNextWhen(!resizedAddr.msb, cmdStream.valid)
  when (cmdStream.valid) {
    nativePort.cmd.payload.addr := resizedAddr
  } otherwise {
    nativePort.cmd.payload.addr := B(
      addrSize bits,
      (addrSize - 1) -> dirtyBit,
      default -> false
    ).asUInt
  }
  cmdStream.ready := nativePort.cmd.ready

  // Native port write channel (active during writes)
  nativePort.write.valid := writeStream.valid && writeStream.payload.write
  nativePort.write.payload.data := writeStream.payload.data
  nativePort.write.payload.we := writeStream.payload.mask
  writeStream.ready := nativePort.write.ready

  // Native port read channel
  nativePort.read.ready := True
  bus.rsp.valid := nativePort.read.valid
  bus.rsp.data := nativePort.read.payload
}
