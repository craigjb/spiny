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

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb._
import spinal.lib.bus.regif._

/** Timer peripheral
 *
 *  A configurable timer with prescaler, multiple compare channels, and interrupts.
 *
 *  Features:
 *  - Configurable timer and prescaler width
 *  - Multiple compare channels (configurable)
 *  - Overflow and compare match interrupts
 *  - Interrupt masking
 *  - Write-1-to-clear interrupt status
 *
 *  @param timerWidth Width of the timer counter in bits (max 32)
 *  @param prescaleWidth Width of the prescaler in bits (max 32)
 *  @param numCompares Number of compare channels
 *  @param prescalerResetValue Initial value for the prescaler
 *  @param isMachineTimer If true, use as machine timer (wired to csrPlugin.timerInterrupt)
 *  @param simSpeedUp Simulation speedup factor (divides prescaler value, 1 = no speedup)
 *  @param addressWidth Address width for APB3 bus
 */
class SpinyTimer(
    timerWidth: Int = 16,
    prescaleWidth: Int = 16,
    numCompares: Int = 2,
    prescalerResetValue: Int = 0,
    isMachineTimer: Boolean = false,
    simSpeedUp: Int = 1,
    addressWidth: Int = 8
) extends Component with SpinyPeripheral {
  assert(timerWidth <= 32, "timerWidth must be <= 32")
  assert(prescaleWidth <= 32, "prescaleWidth must be <= 32")
  assert(simSpeedUp > 0, "simSpeedUp must be > 0")

  val apb3Config = Apb3Config(
    addressWidth = addressWidth,
    dataWidth = 32
  )

  val io = new Bundle {
    val apb = slave(Apb3(apb3Config))
    val interrupt = out Bool ()
  }

  val busIf = createPeripheralBusInterface(io.apb)

  val prescaler = new Area {
    val prescaleReg = busIf
      .newRegAt(0x4, doc = "Prescale")
      .setName("prescale")
    val value = prescaleReg.field(
      UInt(prescaleWidth bits),
      AccessType.WO,
      resetValue = prescalerResetValue,
      doc = "Timer prescale divisor"
    )

    // Apply simulation speedup by dividing prescaler value
    val scaledValue = if (simSpeedUp > 1) {
      value / simSpeedUp
    } else {
      value
    }

    val counter = RegInit(U(0, prescaleWidth bits))
    counter := counter + 1

    val clear = Bool()
    val out = counter === scaledValue
    when(out || clear) {
      counter := 0
    }
  }

  val controlReg = busIf
    .newReg(doc = "Control")
    .setName("control")
  val enable = controlReg.field(
    Bool(),
    AccessType.RW,
    resetValue = 0,
    doc = "Timer enable"
  )
  val clear = controlReg.field(
    Bool(),
    AccessType.W1P,
    resetValue = 0,
    doc = "Clear prescaler and counter"
  )
  val interruptEnable = controlReg.field(
    Bool(),
    AccessType.RW,
    resetValue = 0,
    doc = "Interrupt enable"
  )

  val counterReg = busIf
    .newReg(doc = "Counter")
    .setName("counter")
  val counter = counterReg.field(
    UInt(timerWidth bits),
    AccessType.RW,
    resetValue = 0,
    doc = "Counter value"
  )

  val overflow = enable && (counter === U(0, timerWidth bits) - 1)
  when(enable && prescaler.out) {
    counter := counter + 1
  } elsewhen (overflow) {
    counter := 0
  }
  when(clear) {
    counter := 0
    clear := False
  }
  prescaler.clear := clear

  val interruptStatus = busIf
    .newReg(doc = "Interrupt status")
    .setName("interruptStatus")
  val overflowStatus = interruptStatus.field(
    Bool(),
    AccessType.W1C,
    resetValue = 0,
    doc = "Overflow interrupt status (set to clear)"
  )
  overflowStatus.setWhen(overflow)

  val interruptMask = busIf
    .newReg(doc = "Interrupt mask")
    .setName("interruptMask")
  val overflowMask = interruptMask.field(
    Bool(),
    AccessType.RW,
    resetValue = 1,
    doc = "Mask overflow interrupt"
  )
  val maskedOverflowStatus = overflowStatus && !overflowMask

  val compares = (0 until numCompares).map(i => {
    new Area {
      val reg = busIf
        .newReg(doc = f"Compare $i")
        .setName(f"compare$i")
      val value = reg.field(
        UInt(timerWidth bits),
        AccessType.RW,
        resetValue = 0,
        doc = f"Compare $i value"
      )
      val fire = enable && (counter === value) && prescaler.out

      val status = interruptStatus
        .field(
          Bool(),
          AccessType.W1C,
          resetValue = 0,
          doc = f"Compare$i interrupt status (set to clear)"
        )(SymbolName(f"compare${i}Status"))
      status.setWhen(fire)

      val mask = interruptMask.field(
        Bool(),
        AccessType.RW,
        resetValue = 1,
        doc = f"Mask compare$i interrupt"
      )(SymbolName(f"compare${i}Mask"))

      val maskedStatus = status && !mask
    }.setName(f"compare$i")
  })

  val maskedCompareFire =
    compares.foldLeft(False)((acc, x) => acc || x.maskedStatus)
  io.interrupt := interruptEnable && (maskedCompareFire || maskedOverflowStatus)

  checkPeripheralMapping()

  override def interrupt: Option[Bool] =
    if (isMachineTimer) None else Some(io.interrupt)

  override def machineTimerInterrupt: Option[Bool] =
    if (isMachineTimer) Some(io.interrupt) else None
}
