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

import scala.collection.mutable

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb._
import spinal.lib.bus.amba4.axi._

import spiny.peripheral.SpinyPeripheral
import spiny.svd.SpinySvd
import spinal.lib.bus.misc.SizeMapping

/**
 * SpinyDram - Wrapper around LiteDram BlackBox with dynamic port addition
 *
 * User ports are registered via axi4Port() calls (with automatic width
 * adaptation) and the LiteDram BlackBox is created in build().
 *
 * axi4Port() uses rework to create slave ports on SpinyDram's boundary,
 * which is necessary for hierarchy: the upsizer (in the parent component)
 * connects to SpinyDram's port, and build() connects the port to the
 * BlackBox (inside SpinyDram). This is the same pattern used by
 * Axi4CrossbarFactory.build() in SpinalHDL.
 *
 * @param config LiteDRAM configuration (without user ports)
 * @param sim LiteDRAM sim mode with internal DRAM model
 * @param ctrlAddressWidth Address width for APB3 control bus
 * @param svdPath Optional path to LiteDRAM SVD for register descriptions
 */
case class SpinyDram(
    config: LiteDramConfig,
    sim: Boolean = false,
    ctrlAddressWidth: Int = 16,
    svdPath: Option[String] = None
) extends Component with SpinyPeripheral {

  val apb3Config = Apb3Config(
    addressWidth = ctrlAddressWidth,
    dataWidth = 32
  )

  val io = new Bundle {
    val pllLocked = (!sim) generate (out Bool())
    val initDone = out Bool()
    val initError = out Bool()
    val apb = slave(Apb3(apb3Config))
    val dram = (!sim).generate {
      master(LiteDram.createDramIo(config))
    }
  }

  // Ctrl bus is mappable as a SpinyPeripheral
  peripheralBus = io.apb
  peripheralMappedSize = BigInt(1) << ctrlAddressWidth

  // User clock domain (signals connected to BlackBox in build())
  val userClk = Bool()
  val userRst = Bool()
  val userClockDomain = ClockDomain(
    clock = userClk,
    reset = userRst,
    frequency = FixedFrequency(config.userClkFreq),
    config = ClockDomainConfig(clockEdge = RISING, resetKind = SYNC)
  )

  /** Total RAM size in bytes, based on DRAM geometry and byte groups. */
  def ramSize: BigInt = {
    BigInt(config.geometry.numBanks) *
      config.geometry.numRows *
      config.geometry.numCols *
      config.numByteGroups
  }

  // --- Dynamic port collection ---
  private case class AxiPortDef(idWidth: Int, rawPort: Axi4)
  private val axiPortDefs = mutable.LinkedHashMap[String, AxiPortDef]()

  /**
   * Add an AXI port with automatic width adaptation.
   *
   * Uses rework to create a slave port on SpinyDram's boundary (required
   * for hierarchy — the upsizer lives in the caller's component, and the
   * BlackBox lives inside SpinyDram). Inserts an Axi4Upsizer/Downsizer
   * in the caller's clock domain and returns the bus-width Axi4 for
   * connection to the crossbar.
   *
   * Must be called before build() (i.e., during SoC body).
   */
  def axi4Port(name: String, idWidth: Int, busConfig: Axi4Config): Axi4 = {
    require(!axiPortDefs.contains(name), s"AXI port '$name' already defined")

    val rawAxiConfig = Axi4Config(
      addressWidth = config.axiPortAddressWidth,
      dataWidth = config.nativePortDataWidth,
      idWidth = idWidth,
      useLock = false, useRegion = false,
      useCache = false, useProt = false, useQos = false
    )

    // Create slave port on SpinyDram's boundary
    val rawPort = this.rework {
      slave(Axi4(rawAxiConfig)).setName(name)
    }
    axiPortDefs(name) = AxiPortDef(idWidth, rawPort)

    // Width adaptation (created in caller's Component/clock domain)
    if (busConfig.dataWidth == config.nativePortDataWidth) {
      rawPort
    } else if (busConfig.dataWidth < config.nativePortDataWidth) {
      val outputConfig = busConfig.copy(dataWidth = config.nativePortDataWidth)
      val upsizer = Axi4Upsizer(busConfig, outputConfig, readPendingQueueSize = 4)
      upsizer.io.output >> rawPort
      upsizer.io.input
    } else {
      val outputConfig = busConfig.copy(dataWidth = config.nativePortDataWidth)
      val downsizer = Axi4Downsizer(busConfig, outputConfig)
      downsizer.io.output >> rawPort
      downsizer.io.input
    }
  }

  /**
   * Finalize: create BlackBox, connect all ports.
   * Call after all axi4Port() calls are complete.
   */
  def build(): Unit = this.rework {
    val fullConfig = liteDramFullConfig
    val liteDram = LiteDram(fullConfig, sim)

    // Connect clocks
    userClk := liteDram.io.userClk
    userRst := liteDram.io.userRst

    // Connect status
    if (!sim) io.pllLocked := liteDram.io.pllLocked
    io.initDone := liteDram.io.initDone
    io.initError := liteDram.io.initError

    if (sim) liteDram.io.simTrace := True

    // APB3 to Wishbone bridge (internal, non-bursting)
    val wb = liteDram.io.wbCtrl
    val apb = io.apb
    wb.ADR := apb.PADDR(ctrlAddressWidth - 1 downto 2).resized
    wb.DAT_MOSI := apb.PWDATA
    apb.PRDATA := wb.DAT_MISO
    wb.CYC := apb.PSEL(0)
    wb.STB := apb.PSEL(0) && apb.PENABLE
    wb.WE := apb.PWRITE
    wb.SEL := B"1111"
    wb.CTI := B"000"
    wb.BTE := B"00"
    apb.PREADY := wb.ACK || wb.ERR
    apb.PSLVERROR := wb.ERR

    // Connect AXI ports to BlackBox
    for ((portName, portDef) <- axiPortDefs) {
      liteDram.io.axiUserPorts(portName) <> portDef.rawPort
    }

    // Connect physical DDR
    if (!sim) io.dram <> liteDram.io.dram
  }

  /** Computed full config with ports (for BlackBox and YAML generation) */
  private def liteDramFullConfig: LiteDramConfig = config.withPorts(
    axiPortDefs.map { case (name, d) =>
      name -> UserPortConfig(config.nativePortDataWidth, d.idWidth)
    }.toMap
  )

  /** Dump LiteDRAM config YAML (call after build, e.g. post-elaboration) */
  def dumpConfig(path: String): Unit = liteDramFullConfig.toYaml(path)

  override def svdPeripherals(sizeMapping: SizeMapping): Seq[scala.xml.Elem] = {
    svdPath match {
      case Some(path) =>
        SpinySvd.parseAndRebasePeripherals(path, sizeMapping.base)
      case None =>
        super.svdPeripherals(sizeMapping)
    }
  }
}
