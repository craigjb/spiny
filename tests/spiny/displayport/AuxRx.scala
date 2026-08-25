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

package spiny.displayport

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

import spiny._
import spiny.SimClockDomainExt._

object AuxRxSpec {
  // long enough for the receiver to see the line as stopped
  val AbortGap = 10 us
  val Packets = Seq(
    Seq(0xde, 0xad, 0xbe, 0xef),
    Seq(0xa, 0xb, 0xc, 0xd, 0xe, 0xf),
    Seq(0xab),
    Seq(0xff, 0x00, 0xff, 0x00),
    Seq(0x00, 0xff, 0x00, 0xff)
  )
}

class AuxRxSpec extends AnyFunSuite {
  import AuxSim._
  import AuxRxSpec._

  for (clockFreq <- ClockFreqs) {
    test(f"AuxRx should properly decode packets @ $clockFreq%s") {
      SpinySimConfig("AuxRx_Packets", clockFreq)
        .compile(AuxRx(dataRate = DataRate))
        .doSim { dut =>
          val dataTimeout = dut.clockDomain.cycles(400 us)
          dut.clockDomain.forkStimulus()

          val checkerThread = fork {
            val checker = AuxRxChecker(dut, dataTimeout)
            for (packet <- Packets) {
              checker.checkPacket(packet)
            }
          }

          dut.io.readEnable #= true
          val errors = AuxRxErrorMonitor(dut)
          val driver = AuxRxDriver(dut.io.read)
          for (packet <- Packets) {
            driver.packet(packet)
          }
          sleep(1 us)
          checkerThread.join()
          errors.assertNone("receiving good packets")
        }
    }

    test(f"AuxRx should handle 30 ns jitter @ $clockFreq%s") {
      SpinySimConfig("AuxRx_Jitter", clockFreq)
        .compile(AuxRx(dataRate = DataRate))
        .doSim { dut =>
          val dataTimeout = dut.clockDomain.cycles(400 us)
          dut.clockDomain.forkStimulus()

          val checkerThread = fork {
            val checker = AuxRxChecker(dut, dataTimeout)
            for (packet <- Packets) {
              checker.checkPacket(packet)
            }
          }

          dut.io.readEnable #= true
          val driver = AuxRxDriver(dut.io.read, maxJitter = 30 ns)
          for (packet <- Packets) {
            driver.packet(packet)
          }
          sleep(1 us)
          checkerThread.join()
        }
    }
  }

  for (clockFreq <- Seq(61 MHz, 66.6 MHz, 100 MHz)) {
    // max glitch size that can be handled depends on filter
    val rawTaps = (50.ns / clockFreq.toTime).toInt
    val taps = if (rawTaps % 2 == 0) rawTaps - 1 else rawTaps
    val maxEdgesAllowed = taps / 2
    val maxGlitch = ((clockFreq.toTime * maxEdgesAllowed) - (0.1 ns))

    test(f"AuxRx should handle glitches <$maxGlitch%.2s @ $clockFreq%s") {
      SpinySimConfig("AuxRx_Glitches", clockFreq)
        .compile(AuxRx(dataRate = DataRate))
        .doSim { dut =>
          val dataTimeout = dut.clockDomain.cycles(400 us)
          dut.clockDomain.forkStimulus()

          val checkerThread = fork {
            val checker = AuxRxChecker(dut, dataTimeout)
            for (packet <- Packets) {
              checker.checkPacket(packet)
            }
          }

          dut.io.readEnable #= true
          val driver = AuxRxDriver(dut.io.read, maxGlitch)
          for (packet <- Packets) {
            driver.packet(packet)
          }
          sleep(1 us)
          checkerThread.join()

        }
    }
  }

  // Per Table 3-3 a source may use any unit interval from 0.4 to 0.6 µs, so
  // the receiver measures the rate from the sync pulses rather than assuming
  // the nominal one. These are the extremes the spec allows, plus the middle.
  for (unitInterval <- Seq(0.40 us, 0.45 us, 0.50 us, 0.55 us, 0.60 us)) {
    test(f"AuxRx should decode a source at $unitInterval%.0s UI") {
      SpinySimConfig(f"AuxRx_UnitInterval_$unitInterval%.0S", 100 MHz)
        .compile(AuxRx(dataRate = DataRate))
        .doSim { dut =>
          val dataTimeout = dut.clockDomain.cycles(400 us)
          dut.clockDomain.forkStimulus()

          val checkerThread = fork {
            val checker = AuxRxChecker(dut, dataTimeout)
            for (packet <- Packets) {
              checker.checkPacket(packet)
            }
          }

          dut.io.readEnable #= true
          val errors = AuxRxErrorMonitor(dut)
          val driver = AuxRxDriver(dut.io.read, bitPeriod = unitInterval * 2)
          for (packet <- Packets) {
            driver.packet(packet)
          }
          sleep(1 us)
          checkerThread.join()
          errors.assertNone(f"receiving a $unitInterval%.0s UI source")
        }
    }
  }

  test("AuxRx should abort when readEnable deasserts") {
    SpinySimConfig("AuxRx_ReadEnableAbort", 100 MHz)
      .compile(AuxRx(dataRate = DataRate))
      .doSim { dut =>
        val dataTimeout = dut.clockDomain.cycles(400 us)
        dut.clockDomain.forkStimulus()

        // nothing may be received between the abort and the next packet
        var expectQuiet = true
        var receivedWhileQuiet = Option.empty[Int]
        fork {
          while (true) {
            dut.clockDomain.waitSampling()
            if (expectQuiet && dut.io.data.valid.toBoolean) {
              receivedWhileQuiet = Some(dut.io.data.fragment.toInt)
            }
          }
        }

        dut.io.readEnable #= true
        val driver = AuxRxDriver(dut.io.read)

        // a byte is latched, but only emitted once the next one arrives
        driver.preCharge()
        driver.sync()
        driver.syncEnd()
        driver.data(0xde)

        // drop readEnable in the middle of a packet
        dut.io.readEnable #= false
        dut.io.read #= false
        sleep(AbortGap)

        // raise it again once the line is released
        dut.io.readEnable #= true
        sleep(AbortGap)

        assert(
          receivedWhileQuiet.isEmpty,
          f"AuxRx emitted 0x${receivedWhileQuiet.getOrElse(0)}%02x " +
            "left over from the aborted packet"
        )

        // the next packet should still be received normally
        expectQuiet = false
        val checkerThread = fork {
          AuxRxChecker(dut, dataTimeout).checkPacket(Packets.head)
        }
        driver.packet(Packets.head)
        sleep(1 us)
        checkerThread.join()
      }
  }

  // dropped packets must be reported, otherwise the layer above cannot
  // tell a corrupt reply from one that is still on its way
  for ((name, corrupt) <- Seq[(String, AuxRxDriver => Unit)](
    ("stopped mid byte", { driver =>
      driver.data(0xde)
      // three bits is not a whole byte
      driver.bit(true)
      driver.bit(false)
      driver.bit(true)
    }),
    ("sent no data", { driver => }),
    ("sent malformed interval", { driver =>
      driver.data(0xde)
      // neither a half bit nor a whole bit, so it decodes as nothing
      driver.auxRead #= false
      sleep(AuxSim.BitPeriod * 0.75)
      driver.auxRead #= true
      sleep(AuxSim.BitPeriod * 0.75)
    })
  )) {
    test(f"AuxRx should report an error when the source $name") {
      SpinySimConfig(f"AuxRx_Error_${name.replace(' ', '_')}", 100 MHz)
        .compile(AuxRx(dataRate = DataRate))
        .doSim { dut =>
          val dataTimeout = dut.clockDomain.cycles(400 us)
          dut.clockDomain.forkStimulus()

          var received = 0
          fork {
            while (true) {
              dut.clockDomain.waitSampling()
              if (dut.io.data.valid.toBoolean) {
                received += 1
              }
            }
          }

          dut.io.readEnable #= true
          val errors = AuxRxErrorMonitor(dut)
          val driver = AuxRxDriver(dut.io.read)

          driver.preCharge()
          driver.sync()
          driver.syncEnd()
          corrupt(driver)
          driver.stop()
          sleep(AbortGap)

          errors.assertOne(name)
          assert(
            received == 0,
            s"AuxRx emitted $received byte(s) from a dropped packet"
          )

          // and the receiver recovers for the next packet
          val checkerThread = fork {
            AuxRxChecker(dut, dataTimeout).checkPacket(Packets.head)
          }
          driver.packet(Packets.head)
          sleep(1 us)
          checkerThread.join()
        }
    }
  }
}
