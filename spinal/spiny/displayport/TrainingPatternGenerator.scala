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

import spinal.core._
import spinal.lib._

object TrainingPatternGenerator {
  /** Training pattern 1 is D10.2, which 8b/10b encoding turns into a square
   *  wave that the sink clock locks onto
   */
  val D10_2 = 0x4a

  /** D11.6, the filler between the commas of training pattern 2 */
  val D11_6 = 0xcb

  /** K28.5, the comma a sink aligns its symbol boundaries to */
  val K28_5 = 0xbc

  /** One symbol of a training pattern, as the 8b/10b encoder takes it
   *
   *  @param value The 8 bit character
   *  @param isK True for a control character, driving TXCHARISK or its
   *         equivalent on whichever transceiver transmits it
   */
  case class TrainingSymbol(value: Int, isK: Boolean = false)

  /** Training pattern 2, the ten symbol sequence of the DisplayPort spec
   *
   *  The spec writes the commas as K28.5- and K28.5+, and an encoder
   *  produces that pairing on its own: K28.5 carries +2 disparity while
   *  D11.6 and D10.2 are neutral, so a sequence entered with the running
   *  disparity negative alternates the two commas and ends negative again,
   *  ready to repeat. 
   */
  val TrainingPattern2Symbols: Seq[TrainingSymbol] = Seq(
    TrainingSymbol(K28_5, isK = true),
    TrainingSymbol(D11_6),
    TrainingSymbol(K28_5, isK = true),
    TrainingSymbol(D11_6)
  ) ++ Seq.fill(6)(TrainingSymbol(D10_2))
}

/** DisplayPort training pattern generator for TP1 and TP2
 *
 *  Training patterns are fixed, unscrambled and unframed. Output symbols are
 *  the 8 bit characters that an 8b/10b encoder takes in. This should run in
 *  the transmitter clock domain. 
 *
 *  @param symbolsPerCycle Symbols the transmit datapath carries each cycle
 * @groupname ports SpinalHDL IO Ports
 * @groupprio ports 0
 */
case class TrainingPatternGenerator(symbolsPerCycle: Int) extends Component {
  import TrainingPatternGenerator._

  assert(TrainingPattern2Symbols.length % symbolsPerCycle == 0,
    s"training pattern 2's ${TrainingPattern2Symbols.length} symbols do not " +
      s"divide into cycles of $symbolsPerCycle")

  val io = new Bundle {
    /** Which pattern to transmit, synchronous to this component's clock
     *  @group ports
     */
    val pattern = in(MainLinkPattern())

    /** One character per slot of the transmit datapath
     *  @group ports
     */
    val symbol = out(Vec(Bits(8 bits), symbolsPerCycle))

    /** Which of those characters are control characters
     *  @group ports
     */
    val isK = out(Vec(Bool(), symbolsPerCycle))

    /** Nothing is being transmitted, so the driver can go electrically idle
     *  @group ports
     */
    val quiet = out Bool ()
  }

  // TP2 is a sequence rather than one repeated character, so it walks the
  // pattern table. TP1 is just a constant.
  val steps = TrainingPattern2Symbols.length / symbolsPerCycle
  val step = Counter(steps)
  when(io.pattern === MainLinkPattern.TrainingPattern2) {
    step.increment()
  } otherwise {
    step.clear()
  }

  io.quiet := RegNext(io.pattern === MainLinkPattern.Quiet) init (True)

  for (slot <- 0 until symbolsPerCycle) {
    // the symbols this slot transmits, one per step of the sequence
    val column = (0 until steps).map(s =>
      TrainingPattern2Symbols(s * symbolsPerCycle + slot))
    val values = Vec(column.map(s => B(s.value, 8 bits)))
    val controls = Vec(column.map(s => Bool(s.isK)))

    val symbol = RegInit(B(0, 8 bits))
    val isK = RegInit(False)

    switch(io.pattern) {
      is(MainLinkPattern.TrainingPattern1) {
        symbol := D10_2
        isK := False
      }
      is(MainLinkPattern.TrainingPattern2) {
        symbol := values(step)
        isK := controls(step)
      }
      default {
        symbol := 0
        isK := False
      }
    }

    io.symbol(slot) := symbol
    io.isK(slot) := isK
  }
}
