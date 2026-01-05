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

import java.io.PrintWriter
import scala.xml._

import spinal.core._
import spinal.lib.bus.regif._
import spinal.lib.bus.misc._

import spiny.peripheral._

object SpinySvd {
  def generate(
    name: String,
    peripheralMappings: Seq[(SpinyPeripheral, SizeMapping)]
  ): String = {
    val rawXml = deviceXml(name, peripheralMappings)
    val printer = new PrettyPrinter(80, 2)
    val prettyXml = printer.format(rawXml)
    f"""|<?xml version="1.0" encoding="utf-8"?>
        |<!-- Generated from SpinalHDL. Don't edit. -->
        |$prettyXml""".stripMargin
  }

  def dump(
    path: String,
    name: String,
    peripheralMappings: Seq[(SpinyPeripheral, SizeMapping)]
  ) {
    val pw = new PrintWriter(path)
    pw.write(generate(name, peripheralMappings))
    pw.close()
    SpinalInfo(s"SVD dumped to: ${path} ")
  }

  def deviceXml(
      name: String,
      peripheralMappings: Seq[(SpinyPeripheral, SizeMapping)]
  ): Elem = {
    <device schemaVersion="1.0" xmlns:xs="http://www.w3.org/2001/XMLSchema-instance" xs:noNamespaceSchemaLocation="CMSIS-SVD_Schema_1_0.xsd">
      <name>{ name }</name>
      <peripherals>
        {peripheralMappings.map { case (p, sm) => peripheralXml(p, sm) } }
      </peripherals>
    </device>
  } 

  def peripheralXml(
    peripheral: SpinyPeripheral,
    sizeMapping: SizeMapping
  ): Elem =  {
    val regSlices = peripheral.peripheralBusIf.slices.filter(
      slice => slice.isInstanceOf[RegInst]
    )

    <peripheral>
      <name>{ peripheral.getName() }</name>
      <description></description>
      <baseAddress>{ f"0x${sizeMapping.base}%x" }</baseAddress>
      <registers>
        { regSlices.map(rs => regSliceXml(rs)) }
      </registers>
    </peripheral>
  }

  def regSliceXml(reg: RegSlice): Elem = {
    val svdFields = reg
      .getFields()
      .filter(_.getAccessType != AccessType.NA)

    val resetValue = reg
      .getFields()
      .flatMap(fd =>
        fd.getSection()
          .map(bit =>
            (bit, fd.getResetValue.testBit(bit - fd.getSection.min))
          )
      )
      .foldLeft(BigInt(0)) { case (value, (bitPos, set)) =>
        if (set) { value.setBit(bitPos) }
        else { value }
      }

    val resetMask = reg
      .getFields()
      .filter(_.getAccessType != AccessType.NA)
      .flatMap(_.getSection())
      .foldLeft(BigInt(0))((mask, bit) => mask.setBit(bit))

    <register>
      <name>{ reg.getName() }</name>
      <description>{ reg.getDoc() }</description>
      <addressOffset>{ f"0x${reg.getAddr()}%x" }</addressOffset>
      <size>{ f"${reg.getSize() * 8}" }</size>
      <resetValue>{ f"0x$resetValue%x" }</resetValue>
      <resetMask>{ f"0x$resetMask%x" }</resetMask>
      <fields>
        { svdFields.map(f => fieldXml(f)) }
      </fields>
    </register>
  }

  def fieldXml(field: Field): Elem = {
    <field>
      <name>{ f"${field.getName()}%s" }</name>
      <description>{ f"${field.getDoc()}%s" }</description>
      <bitRange>{ f"[${field.getSection.max}%d:${field.getSection.min}%d]" }</bitRange>
      <access>{ access(field.getAccessType()) }</access>
      <modifiedWriteValues>{ modifiedWriteValues(field.getAccessType()) }</modifiedWriteValues>
      <readAction>{ readAction(field.getAccessType()) }</readAction>
    </field>
  }
  
  def access(accessType: AccessType): String = {
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

  def modifiedWriteValues(accessType: AccessType): String = {
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

  def readAction(accessType: AccessType): String = {
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
