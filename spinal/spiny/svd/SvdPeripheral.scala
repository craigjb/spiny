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

package spiny.svd

import spinal.lib.bus.regif._

case class SvdPeripheral(
    name: String,
    baseAddress: BigInt,
    busIf: BusIf,
    description: String = ""
) {
  val regSlices = busIf.slices.filter(slice => slice.isInstanceOf[RegInst])

  def body(): String = {
    f"""|  <peripheral>
        |    <name>${name}</name>
        |    <description>${description}</description>
        |    <baseAddress>0x$baseAddress%x</baseAddress>
        |    <registers>
        |${regSlices.map(_.toSvd).mkString("\n")}
        |    </registers>
        |  </peripheral>""".stripMargin
  }

  implicit class RegSliceExtend(reg: RegSlice) {
    def toSvd: String = {
      val svdFields = reg
        .getFields()
        .filter(_.getAccessType != AccessType.NA)

      f"""|      <register>
          |        <name>${reg.getName()}</name>
          |        <description>${reg.getDoc()}</description>
          |        <addressOffset>0x${reg.getAddr()}%x</addressOffset>
          |        <size>${reg.getSize() * 8}</size>
          |        <resetValue>0x$resetValue%x</resetValue>
          |        <resetMask>0x$resetMask%x</resetMask>
          |        <fields>
          |${svdFields.map(_.toSvd).mkString("\n")}
          |        </fields>
          |      </register>""".stripMargin
    }

    def resetValue: BigInt = {
      reg
        .getFields()
        .flatMap(fd =>
          fd.getSection()
            .map(bit =>
              (bit, fd.getResetValue.testBit(bit - fd.getSection.min))
            )
        )
        .foldLeft(BigInt(0))({ case (value, (bitPos, set)) =>
          if (set) { value.setBit(bitPos) }
          else { value }
        })
    }

    def resetMask: BigInt = {
      reg
        .getFields()
        .filter(_.getAccessType != AccessType.NA)
        .flatMap(_.getSection())
        .foldLeft(BigInt(0))((mask, bit) => mask.setBit(bit))
    }
  }

  implicit class FieldDescrExtend(fd: Field) {
    val access = mapAccess(fd.getAccessType())
    val modifiedWriteValues = mapModifiedWriteValues(fd.getAccessType())
    val readAction = mapReadAction(fd.getAccessType())

    def toSvd: String = {
      f"""|          <field>
          |            <name>${fd.getName()}%s</name>
          |            <description>${fd.getDoc()}%s</description>
          |            <bitRange>[${fd.getSection.max}%d:${fd.getSection.min}%d]</bitRange>
          |            <access>$access</access>
          |            <modifiedWriteValues>$modifiedWriteValues</modifiedWriteValues>
          |            <readAction>$readAction</readAction>
          |          </field>""".stripMargin
    }

    private def mapAccess(accessType: AccessType) = {
      import AccessType._

      accessType match {
        case RO | RC | RS | ROV => "read-only"
        case RW | WRC | WRS | WC | WS | WSRC | WCRS | W1C | W1S | W1T | W0C |
            W0S | W0T | W1SRC | W1CRS | W0SRC | W0CRS | W1P | W0P | HSRW |
            RWHS | W1CHS | W1SHS =>
          "read-write"
        case WO | W0C | WOS => "write-only"
        case W1             => "read-writeOnce"
        case WO1            => "writeOnce"
        case _ => throw new Exception("Unknown access type for SVD")
      }
    }

    private def mapModifiedWriteValues(accessType: AccessType) = {
      import AccessType._

      accessType match {
        case RO | RW | RC | RS | WRC | WRS | WO | W1 | WO1 | W1P | W0P | HSRW |
            RWHS | ROV =>
          "modify"
        case WC | WCRS | WOC     => "clear"
        case WS | WSRC | WOS     => "set"
        case W1C | W1CRS | W1CHS => "oneToClear"
        case W1S | W1SRC | W1SHS => "oneToSet"
        case W1T                 => "oneToToggle"
        case W0C | W0CRS         => "zeroToClear"
        case W0S | W0SRC         => "zeroToSet"
        case W0T                 => "zeroToToggle"
        case _ => throw new Exception("Unknown access type for SVD")
      }
    }

    private def mapReadAction(accessType: AccessType) = {
      import AccessType._

      accessType match {
        case RO | RW | WC | WS | W1C | W1S | W1T | W0C | W0S | W0T | WO | WOC |
            WOS | W1 | WO1 | W1P | W0P | HSRW | RWHS | W1CHS | W1SHS | ROV =>
          "modify"
        case RS | WRS | WCRS | W1CRS | W0CRS => "set"
        case RC | WRC | WSRC | W1SRC | W0SRC => "clear"
        case _ =>
          throw new Exception(f"Unknown access type for SVD: $accessType")
      }
    }
  }
}
