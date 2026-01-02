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

package spiny.peripheral

import scala.collection.mutable

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb._
import spinal.lib.io.TriStateArray
import spinal.lib.bus.regif._

import spiny.Utils.nextPowerOfTwo

sealed trait SpinyGpioDirection
object SpinyGpioDirection {
  case object Input extends SpinyGpioDirection
  case object Output extends SpinyGpioDirection
  case object InOut extends SpinyGpioDirection
}

/** GPIO bank configuration
 *
 *  width: number of pins
 *  direction: input, output, or in-out (software-controlled)
 *  name: bank name
 */
case class SpinyGpioBankConfig(
  name: String,
  width: Int,
  direction: SpinyGpioDirection
)

/** GPIO peripheral
 *
 *  Supports multiple banks with independent direction config per bank.
 *  IO signals are available in io.banks
 *  InOut GPIO use TriStateArray.
 */
class SpinyGpio(
  bankConfigs: Seq[SpinyGpioBankConfig],
  addressWidth: Int = 12
) extends Component with SpinyPeripheral {
  assert(!bankConfigs.isEmpty, "No SpinyGpioBankConfigs given")
  val bankConfigMap = mutable.LinkedHashMap[String, SpinyGpioBankConfig]()

  val apb3Config = Apb3Config(
    addressWidth = addressWidth,
    dataWidth = 32
  )

  val io = new Bundle {
    val apb = slave(Apb3(apb3Config))
    val banks = mutable.LinkedHashMap[String, Data]()

    bankConfigs.zipWithIndex.foreach { case(bankConf, i) => 
      assert(bankConf.width <= apb.config.dataWidth,
        "Gpio bank width is larger than bus data width")

      val signal = bankConf.direction match {
        case SpinyGpioDirection.Input => in Bits(bankConf.width bits)
        case SpinyGpioDirection.Output => out Bits(bankConf.width bits)
        case SpinyGpioDirection.InOut => master(TriStateArray(bankConf.width bits))
      }
      signal.setName(bankConf.name)
      bankConfigMap(bankConf.name) = bankConf
      banks(bankConf.name) = signal
    }
  }

  val busIf = peripheralBusInterface()

  bankConfigs.foreach { bankConf => 
    bankConf.direction match {
      case SpinyGpioDirection.Input => {
        val port = io.banks(bankConf.name).asInstanceOf[Bits]
        val read = busIf.newReg(s"Bank ${port.name} read")
        val value = read.field(
          Bits(bankConf.width bits),
          AccessType.RO,
          doc = "Read value"
        )
        value := port
      }
      case SpinyGpioDirection.Output => {
        val port = io.banks(bankConf.name).asInstanceOf[Bits]
        val write = busIf.newReg(s"Bank ${port.name} write")
        val value = write.field(
          Bits(bankConf.width bits),
          AccessType.RW,
          doc = "Write value"
        )
        port := value
      }
      case SpinyGpioDirection.InOut => {
        val port = io.banks(bankConf.name).asInstanceOf[TriStateArray]

        val read = busIf.newReg(s"Bank ${port.name} read")
        val readValue = read.field(
          Bits(bankConf.width bits),
          AccessType.RO,
          doc = "Read value"
        ).setName("value")
        readValue := port.read

        val dir = busIf.newReg(s"Bank ${port.name} direction")
        val dirValue = dir.field(
          Bits(bankConf.width bits),
          AccessType.RW,
          doc = "Direction (1 = Output, 0 = Input)"
        ).setName("value")
        port.writeEnable := dirValue

        val write = busIf.newReg(s"Bank ${port.name} write")
        val writeValue = write.field(
          Bits(bankConf.width bits),
          AccessType.RW,
          doc = "Write value"
        ).setName("value")
        port.write := writeValue
      }
    }
  }

  /** Returns Bits for an Input or Output bank
   *
   * Helper to do a safe cast
   */
  def getBankBits(bank: String): Bits = {
    assert(
      io.banks.contains(bank),
      s"GPIO Bank $bank does not exist"
    )
    val config = bankConfigMap(bank)
    assert(
      (config.direction == SpinyGpioDirection.Input ||
        config.direction == SpinyGpioDirection.Output),
      s"GPIO Bank $bank is configured as ${config.direction}, " +
        "but getBits() requires Input or Output.")
    io.banks(bank).asInstanceOf[Bits]
  }

  /** Returns TriStateArray for an InOut bank
   *
   * Helper to do a safe cast
   */
  def getBankTriState(bank: String): TriStateArray = {
    assert(
      io.banks.contains(bank),
      s"GPIO Bank $bank does not exist"
    )
    val config = bankConfigMap(bank)
    assert(
      config.direction == SpinyGpioDirection.InOut,
      s"GPIO Bank $bank is configured as ${config.direction}, " +
        "but getTriState() requires InOut.")
    io.banks(bank).asInstanceOf[TriStateArray]
  }

  def peripheralBus = io.apb
  def peripheralMappedSize = 1 << addressWidth

  assert(peripheralMappedSize >= busIf.getMappedSize,
    s"GPIO addressWidth must be >= ${log2Up(busIf.getMappedSize)}")
}
