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

import java.io.{FileInputStream, FileWriter}
import scala.collection.JavaConverters._

import org.yaml.snakeyaml.{Yaml, DumperOptions}

import spinal.core._

/** DDR memory types supported by LiteDRAM */
sealed trait DramMemType {
  def yamlName: String
  def dataRate: Int // 1 for SDR, 2 for DDR
}
object DramMemType {
  case object Sdr extends DramMemType { val yamlName = "SDR"; val dataRate = 1 }
  case object Ddr2 extends DramMemType { val yamlName = "DDR2"; val dataRate = 2 }
  case object Ddr3 extends DramMemType { val yamlName = "DDR3"; val dataRate = 2 }
  case object Ddr4 extends DramMemType { val yamlName = "DDR4"; val dataRate = 2 }

  def fromString(s: String): DramMemType = s match {
    case "SDR" => Sdr
    case "DDR2" => Ddr2
    case "DDR3" => Ddr3
    case "DDR4" => Ddr4
    case _ => throw new IllegalArgumentException(s"Unknown memory type: $s")
  }
}

/** PHY type for LiteDRAM */
sealed trait DramPhy {
  def name: String
  def nphases: Int
}
object DramPhy {
  case object A7DDRPHY extends DramPhy { val name = "A7DDRPHY"; val nphases = 4 }
  case class Custom(name: String, nphases: Int) extends DramPhy

  def fromString(s: String): DramPhy = s match {
    case "A7DDRPHY" => A7DDRPHY
    case _ => throw new IllegalArgumentException(
      s"Unknown PHY: $s (use DramPhy.Custom for non-standard PHYs)")
  }
}

/** AXI user port configuration */
case class UserPortConfig(
  dataWidth: Int,
  idWidth: Int
)

/** DRAM geometry (required for all modules to determine IO widths) */
case class DramGeometry(
  numBanks: Int,
  numRows: Int,
  numCols: Int
) {
  import spinal.core._

  /** Calculate address width from row count */
  def addressWidth: Int = log2Up(numRows)

  /** Calculate bank address width from bank count */
  def bankAddressWidth: Int = log2Up(numBanks)
}

/** Timing specified as cycles, nanoseconds, or both.
 *  LiteDRAM takes the more restrictive of the two when both are given.
 */
case class DramTiming(cycles: Option[Int] = None, timeNs: Option[Double] = None)

/** DRAM timing parameters (flat — LiteDRAM's tech/speedgrade split is handled in toYaml) */
case class DramTimings(
  tREFI: Double,                         // Refresh interval (ns)
  tWTR: DramTiming,                      // Write-to-Read
  tCCD: DramTiming,                      // Column-to-Column
  tRRD: Option[DramTiming] = None,       // Row-to-Row
  tZQCS: Option[DramTiming] = None,      // ZQ Calibration Short
  tRP: Double,                           // Row Precharge (ns)
  tRCD: Double,                          // RAS-to-CAS Delay (ns)
  tWR: Double,                           // Write Recovery (ns)
  tRFC: DramTiming,                      // Refresh-to-Activate
  tFAW: Option[DramTiming] = None,       // Four Activate Window
  tRAS: Option[Double] = None,           // Row Active Strobe (ns)
)

/**
 * LiteDRAM configuration
 *
 * Defines all parameters needed for blackbox IO generation and YAML config
 * output for litedram_gen.py. User ports default to empty; they are populated
 * dynamically by SpinyDram.axi4Port() and passed to the BlackBox at build time.
 */
case class LiteDramConfig(
  name: String = "litedram_core",
  memType: DramMemType,
  moduleName: String = "",
  geometry: DramGeometry,
  timings: DramTimings,
  numByteGroups: Int,
  numRanks: Int,
  phy: DramPhy = DramPhy.A7DDRPHY,
  fpgaSpeedgrade: Int = 0,
  inputClkFreq: HertzNumber = 100e6 Hz,
  userClkFreq: HertzNumber,
  iodelayClkFreq: HertzNumber = 200e6 Hz,
  extraCmdLatency: Int = 0,
  cmdBufferDepth: Int = 16,
  withChipSelects: Boolean = true,
  userPorts: Map[String, UserPortConfig] = Map.empty
) {

  /** Controller internal data width = DDR bus width * PHY phases */
  def controllerDataWidth: Int = numByteGroups * 8 * phy.nphases

  /** DDR data width in bits (number of DQ pins) */
  def ddrDataWidth: Int = numByteGroups * 8

  /** Native crossbar data width inside LiteDRAM.
   *  Equals dfi_databits * nphases = dq_width * dataRate * nphases.
   *  User port data_width should equal this to avoid internal converters.
   */
  def nativePortDataWidth: Int = ddrDataWidth * memType.dataRate * phy.nphases

  /** Address width (bits needed to address rows) */
  def addressWidth: Int = geometry.addressWidth

  /** Bank address width (bits needed to address banks) */
  def bankAddressWidth: Int = geometry.bankAddressWidth

  /** AXI port address width (byte-addressed, no word-size subtraction) */
  def axiPortAddressWidth: Int = {
    log2Up(geometry.numRows) + log2Up(geometry.numCols) +
      log2Up(geometry.numBanks) + log2Up(numByteGroups)
  }

  /** Return a copy with user ports populated */
  def withPorts(ports: Map[String, UserPortConfig]): LiteDramConfig =
    copy(userPorts = ports)

  private def dramTimingToYaml(t: DramTiming): java.util.List[Any] = {
    val list = new java.util.ArrayList[Any](2)
    list.add(t.cycles.map(Int.box).orNull)
    list.add(t.timeNs.map(Double.box).orNull)
    list
  }

  /** Write config as YAML in the format litedram_gen.py expects */
  def toYaml(path: String): Unit = {
    val options = new DumperOptions()
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK)
    val yaml = new Yaml(options)

    val data = new java.util.LinkedHashMap[String, Any]()
    data.put("name", name)
    data.put("fpga_speedgrade", Int.box(fpgaSpeedgrade))
    data.put("type", memType.yamlName)
    val moduleMap = new java.util.LinkedHashMap[String, Any]()
    moduleMap.put("name", moduleName)

    val timingsMap = new java.util.LinkedHashMap[String, Any]()
    timingsMap.put("tREFI", Double.box(timings.tREFI))
    timingsMap.put("tWTR", dramTimingToYaml(timings.tWTR))
    timingsMap.put("tCCD", dramTimingToYaml(timings.tCCD))
    timings.tRRD.foreach(t => timingsMap.put("tRRD", dramTimingToYaml(t)))
    timings.tZQCS.foreach(t => timingsMap.put("tZQCS", dramTimingToYaml(t)))
    timingsMap.put("tRP", Double.box(timings.tRP))
    timingsMap.put("tRCD", Double.box(timings.tRCD))
    timingsMap.put("tWR", Double.box(timings.tWR))
    timingsMap.put("tRFC", dramTimingToYaml(timings.tRFC))
    timings.tFAW.foreach(t => timingsMap.put("tFAW", dramTimingToYaml(t)))
    timings.tRAS.foreach(v => timingsMap.put("tRAS", Double.box(v)))

    moduleMap.put("timings", timingsMap)
    data.put("dram_module", moduleMap)

    val geom = new java.util.LinkedHashMap[String, Any]()
    geom.put("num_banks", Int.box(geometry.numBanks))
    geom.put("num_rows", Int.box(geometry.numRows))
    geom.put("num_cols", Int.box(geometry.numCols))
    data.put("dram_geometry", geom)

    data.put("extra_cmd_latency", Int.box(extraCmdLatency))
    data.put("num_byte_groups", Int.box(numByteGroups))
    data.put("num_ranks", Int.box(numRanks))
    data.put("phy", phy.name)
    data.put("input_clk_freq", Double.box(inputClkFreq.toDouble))
    data.put("user_clk_freq", Double.box(userClkFreq.toDouble))
    data.put("iodelay_clk_freq", Double.box(iodelayClkFreq.toDouble))
    data.put("cmd_buffer_depth", Int.box(cmdBufferDepth))
    data.put("with_chip_selects", Boolean.box(withChipSelects))

    val ports = new java.util.LinkedHashMap[String, Any]()
    for ((portName, portConfig) <- userPorts) {
      val port = new java.util.LinkedHashMap[String, Any]()
      port.put("type", "axi")
      port.put("data_width", Int.box(portConfig.dataWidth))
      port.put("id_width", Int.box(portConfig.idWidth))
      ports.put(portName, port)
    }
    data.put("user_ports", ports)

    val writer = new FileWriter(path)
    yaml.dump(data, writer)
    writer.close()
    SpinalInfo(s"LiteDRAM config dumped to: $path")
  }
}

object LiteDramConfig {

  private def getInt(map: java.util.Map[String, Any], key: String): Int = {
    map.get(key) match {
      case i: java.lang.Integer => i.intValue()
      case d: java.lang.Double => d.intValue()
      case s: String => s.toInt
      case null => throw new IllegalArgumentException(s"Missing required field: $key")
      case x => throw new IllegalArgumentException(s"Invalid type for $key: ${x.getClass}")
    }
  }

  private def getOptInt(map: java.util.Map[String, Any], key: String, default: Int): Int = {
    Option(map.get(key)) match {
      case Some(i: java.lang.Integer) => i.intValue()
      case Some(d: java.lang.Double) => d.intValue()
      case Some(s: String) => s.toInt
      case Some(x) => throw new IllegalArgumentException(s"Invalid type for $key: ${x.getClass}")
      case None => default
    }
  }

  private def getString(map: java.util.Map[String, Any], key: String): String = {
    map.get(key) match {
      case s: String => s
      case null => throw new IllegalArgumentException(s"Missing required field: $key")
      case x => x.toString
    }
  }

  private def getOptString(map: java.util.Map[String, Any], key: String, default: String): String = {
    Option(map.get(key)) match {
      case Some(s: String) => s
      case Some(x) => x.toString
      case None => default
    }
  }

  private def getDouble(map: java.util.Map[String, Any], key: String): Double = {
    map.get(key) match {
      case d: java.lang.Double => d.doubleValue()
      case i: java.lang.Integer => i.doubleValue()
      case s: String => s.toDouble
      case null => throw new IllegalArgumentException(s"Missing required field: $key")
      case x => throw new IllegalArgumentException(s"Invalid type for $key: ${x.getClass}")
    }
  }

  private def getOptDouble(map: java.util.Map[String, Any], key: String, default: Double): Double = {
    Option(map.get(key)) match {
      case Some(d: java.lang.Double) => d.doubleValue()
      case Some(i: java.lang.Integer) => i.doubleValue()
      case Some(s: String) => s.toDouble
      case Some(x) => throw new IllegalArgumentException(s"Invalid type for $key: ${x.getClass}")
      case None => default
    }
  }

  private def getOptBool(map: java.util.Map[String, Any], key: String, default: Boolean): Boolean = {
    Option(map.get(key)) match {
      case Some(b: java.lang.Boolean) => b.booleanValue()
      case Some(x) => throw new IllegalArgumentException(s"Invalid type for $key: ${x.getClass}, expected boolean")
      case None => default
    }
  }

  private def parseDramTimingList(list: java.util.List[Any]): DramTiming = {
    val cycles = Option(list.get(0)).map(v => v.asInstanceOf[java.lang.Number].intValue())
    val timeNs = Option(list.get(1)).map(v => v.asInstanceOf[java.lang.Number].doubleValue())
    DramTiming(cycles, timeNs)
  }

  private def getDramTiming(map: java.util.Map[String, Any], key: String): DramTiming = {
    map.get(key) match {
      case list: java.util.List[_] => parseDramTimingList(list.asInstanceOf[java.util.List[Any]])
      case null => throw new IllegalArgumentException(s"Missing required field: $key")
      case x => throw new IllegalArgumentException(s"Invalid type for $key: ${x.getClass}")
    }
  }

  private def getOptDramTiming(map: java.util.Map[String, Any], key: String): Option[DramTiming] = {
    Option(map.get(key)).map {
      case list: java.util.List[_] => parseDramTimingList(list.asInstanceOf[java.util.List[Any]])
      case x => throw new IllegalArgumentException(s"Invalid type for $key: ${x.getClass}")
    }
  }

  /**
   * Load configuration from YAML file.
   *
   * Reads the same YAML format that fusesoc/litedram_gen.py expects.
   */
  def fromYaml(path: String): LiteDramConfig = {
    val yaml = new Yaml()
    val input = new FileInputStream(path)
    val data = yaml.load(input).asInstanceOf[java.util.Map[String, Any]]
    input.close()

    val name = getOptString(data, "name", "litedram_core")
    val memType = DramMemType.fromString(getString(data, "type"))
    val moduleData = data.get("dram_module").asInstanceOf[java.util.Map[String, Any]]
    val moduleName = getString(moduleData, "name")
    val timingsData = moduleData.get("timings").asInstanceOf[java.util.Map[String, Any]]

    val timings = DramTimings(
      tREFI = getDouble(timingsData, "tREFI"),
      tWTR = getDramTiming(timingsData, "tWTR"),
      tCCD = getDramTiming(timingsData, "tCCD"),
      tRRD = getOptDramTiming(timingsData, "tRRD"),
      tZQCS = getOptDramTiming(timingsData, "tZQCS"),
      tRP = getDouble(timingsData, "tRP"),
      tRCD = getDouble(timingsData, "tRCD"),
      tWR = getDouble(timingsData, "tWR"),
      tRFC = getDramTiming(timingsData, "tRFC"),
      tFAW = getOptDramTiming(timingsData, "tFAW"),
      tRAS = Option(timingsData.get("tRAS")).map(v => v.asInstanceOf[java.lang.Number].doubleValue()),
    )
    val numByteGroups = getInt(data, "num_byte_groups")
    val numRanks = getInt(data, "num_ranks")
    val userClkFreq = getDouble(data, "user_clk_freq") Hz
    val withChipSelects = getOptBool(data, "with_chip_selects", default = true)

    val phyStr = getOptString(data, "phy", "A7DDRPHY")
    val phy = DramPhy.fromString(phyStr)
    val fpgaSpeedgrade = getOptInt(data, "fpga_speedgrade", 0)
    val inputClkFreq = getOptDouble(data, "input_clk_freq", 100e6) Hz
    val iodelayClkFreq = getOptDouble(data, "iodelay_clk_freq", 200e6) Hz
    val extraCmdLatency = getOptInt(data, "extra_cmd_latency", 0)
    val cmdBufferDepth = getOptInt(data, "cmd_buffer_depth", 16)

    // Parse geometry (always required)
    val geomMap = data.get("dram_geometry").asInstanceOf[java.util.Map[String, Any]]
    val geometry = DramGeometry(
      numBanks = getInt(geomMap, "num_banks"),
      numRows = getInt(geomMap, "num_rows"),
      numCols = getInt(geomMap, "num_cols")
    )

    // Parse user ports (AXI only)
    val portsMap = data.get("user_ports").asInstanceOf[java.util.Map[String, Any]]
    val userPorts = portsMap.asScala.collect { case (portName, portData) =>
      val portMap = portData.asInstanceOf[java.util.Map[String, Any]]
      val portType = getString(portMap, "type")
      require(portType == "axi", s"Only AXI user ports are supported, got: $portType")
      val dataWidth = getInt(portMap, "data_width")
      val idWidth = getInt(portMap, "id_width")
      portName -> UserPortConfig(dataWidth, idWidth)
    }.toMap

    LiteDramConfig(
      name = name,
      memType = memType,
      moduleName = moduleName,
      geometry = geometry,
      timings = timings,
      numByteGroups = numByteGroups,
      numRanks = numRanks,
      phy = phy,
      fpgaSpeedgrade = fpgaSpeedgrade,
      inputClkFreq = inputClkFreq,
      userClkFreq = userClkFreq,
      iodelayClkFreq = iodelayClkFreq,
      extraCmdLatency = extraCmdLatency,
      cmdBufferDepth = cmdBufferDepth,
      withChipSelects = withChipSelects,
      userPorts = userPorts
    )
  }
}
