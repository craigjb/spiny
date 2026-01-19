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
import java.io.FileInputStream
import org.yaml.snakeyaml.Yaml
import scala.collection.JavaConverters._

/** DDR memory types supported by LiteDRAM */
sealed trait DramMemType
object DramMemType {
  case object Sdr extends DramMemType
  case object Ddr2 extends DramMemType
  case object Ddr3 extends DramMemType
  case object Ddr4 extends DramMemType

  def fromString(s: String): DramMemType = s match {
    case "SDR" => Sdr
    case "DDR2" => Ddr2
    case "DDR3" => Ddr3
    case "DDR4" => Ddr4
    case _ => throw new IllegalArgumentException(s"Unknown memory type: $s")
  }
}

/** User port interface types */
sealed trait UserPortType
object UserPortType {
  case object Native extends UserPortType
  case object Axi extends UserPortType
  case object Wishbone extends UserPortType
  case object Avalon extends UserPortType
  case object Fifo extends UserPortType

  def fromString(s: String): UserPortType = s match {
    case "native" => Native
    case "axi" => Axi
    case "wishbone" => Wishbone
    case "avalon" => Avalon
    case "fifo" => Fifo
    case _ => throw new IllegalArgumentException(s"Unknown port type: $s")
  }
}

/** User port configuration */
case class UserPortConfig(
  portType: UserPortType,
  dataWidth: Int
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

/**
 * LiteDRAM configuration
 *
 * Only includes parameters needed to determine IO ports for blackbox generation.
 * Full parameters are in the FuseSoC generator (see fusesoc/litedram_gen.py).
 */
case class LiteDramConfig(
  name: String = "litedram_core",  // Module name (from YAML root)
  memType: DramMemType,
  numByteGroups: Int,
  numRanks: Int,
  geometry: DramGeometry,          // Always required (even for built-in modules)
  userClkFreq: HertzNumber,
  userPorts: Map[String, UserPortConfig]
) {

  /**
   * DDR data width in bits (number of DQ pins)
   */
  def ddrDataWidth: Int = numByteGroups * 8

  /**
   * Address width (bits needed to address rows)
   */
  def addressWidth: Int = geometry.addressWidth

  /**
   * Bank address width (bits needed to address banks)
   */
  def bankAddressWidth: Int = geometry.bankAddressWidth
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

  /**
   * Load configuration from YAML file.
   *
   * Only parses fields needed to determine IO ports.
   * Reads the same YAML format that fusesoc/litedram_gen.py expects.
   */
  def fromYaml(path: String): LiteDramConfig = {
    val yaml = new Yaml()
    val input = new FileInputStream(path)
    val data = yaml.load(input).asInstanceOf[java.util.Map[String, Any]]
    input.close()

    val name = getOptString(data, "name", "litedram_core")
    val memType = DramMemType.fromString(getString(data, "type"))
    val numByteGroups = getInt(data, "num_byte_groups")
    val numRanks = getInt(data, "num_ranks")
    val userClkFreq = getDouble(data, "user_clk_freq") Hz

    // Parse geometry (always required)
    val geomMap = data.get("dram_geometry").asInstanceOf[java.util.Map[String, Any]]
    val geometry = DramGeometry(
      numBanks = getInt(geomMap, "num_banks"),
      numRows = getInt(geomMap, "num_rows"),
      numCols = getInt(geomMap, "num_cols")
    )

    // Parse user ports
    val portsMap = data.get("user_ports").asInstanceOf[java.util.Map[String, Any]]
    val userPorts = portsMap.asScala.map { case (portName, portData) =>
      val portMap = portData.asInstanceOf[java.util.Map[String, Any]]
      val portType = UserPortType.fromString(getString(portMap, "type"))
      val dataWidth = getInt(portMap, "data_width")
      portName -> UserPortConfig(portType, dataWidth)
    }.toMap

    LiteDramConfig(
      name = name,
      memType = memType,
      numByteGroups = numByteGroups,
      numRanks = numRanks,
      geometry = geometry,
      userClkFreq = userClkFreq,
      userPorts = userPorts
    )
  }
}
