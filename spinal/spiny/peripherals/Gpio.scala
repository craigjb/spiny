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

package spiny.peripherals

import spinal.core._
import spinal.lib._
import spinal.lib.io.TriStateArray
import spinal.lib.bus.regif._

import spiny.bus._
import spiny.Utils.nextPowerOfTwo
import spinal.lib.bus.misc.SizeMapping

sealed trait GpioDirection
object GpioDirection {
  case object Input extends GpioDirection
  case object Output extends GpioDirection
  case object InOut extends GpioDirection
}

case class GpioBankConfig(
  width: Int,
  direction: GpioDirection,
  name: String = null
) {
  def numRegisters: Int = {
    direction match {
      case GpioDirection.Input => 1
      case GpioDirection.Output => 1
      case GpioDirection.InOut => 3
    }
  }
}

object Gpio {
  val DefaultBankNames = Seq(
    "A", "B", "C", "D", "E", "F", "G", "H", "I", "J",
    "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T",
    "U", "V", "W", "X", "Y", "Z"
  )
}

class Gpio[B <: BusDef.Bus](
  busDef: BusDef[B],
  bankConfigs: Seq[GpioBankConfig]
) extends Component with AddressMapped[B] {
  import Gpio._

  assert(!bankConfigs.isEmpty, "No GpioBankConfigs given")

  val io = new Bundle {
    val bus = slave(busDef.createBus())

    val banks = bankConfigs.zipWithIndex.map { case(bankConf, i) => 
      assert(bankConf.width <= busDef.dataWidth,
        "Gpio bank width is larger than bus data width")

      val signal = bankConf.direction match {
        case GpioDirection.Input => in Bits(bankConf.width bits)
        case GpioDirection.Output => out Bits(bankConf.width bits)
        case GpioDirection.InOut => master(TriStateArray(bankConf.width bits))
      }

      val name = if (bankConf.name == null) {
        DefaultBankNames(i)
      } else {
        bankConf.name
      }

      signal.setName(name)
      signal
    }
  }

  def mappedBus = io.bus

  def minMappedSize = {
    nextPowerOfTwo(bankConfigs.map(c => c.numRegisters).sum)
  }

  val busIf = busDef.createBusInterface(io.bus, SizeMapping(0, minMappedSize))

  (io.banks, bankConfigs).zipped.foreach { (signal, bankConf) => 
    bankConf.direction match {
      case GpioDirection.Input => {
        val port = signal.asInstanceOf[Bits]
        val read = busIf.newReg(s"Bank ${port.name} read")
        val value = read.field(
          Bits(bankConf.width bits),
          AccessType.RO,
          doc = "Read value"
        )
        value := port
      }
      case GpioDirection.Output => {
        val port = signal.asInstanceOf[Bits]
        val write = busIf.newReg(s"Bank ${port.name} write")
        val value = write.field(
          Bits(bankConf.width bits),
          AccessType.RW,
          doc = "Write value"
        )
        port := value
      }
      case GpioDirection.InOut => {
        val port = signal.asInstanceOf[TriStateArray]

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
}
