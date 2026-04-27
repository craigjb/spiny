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

package spiny.hdmi

import scala.collection.mutable

import spinal.core._
import spinal.lib._

import hdmi.TmdsChar

object Tmds {
  implicit class IntToBitSeq(val value: Int) extends AnyVal {
    def toBitSeq(len: Int): Seq[Boolean] =
      (0 until len).map(i => (value & (1 << i)) != 0)
  }

  implicit class BitsToInt(val bits: Seq[Boolean]) extends AnyVal {
    def bitsToInt: Int =
      bits.zipWithIndex.foldLeft(0) { case (acc, (b, i)) =>
        if (b) acc | (1 << i) else acc
      }
  }

  /** Software implementation of video encoding for testing and simulation
   *
   *  Returns (encoded bits, disparity delta)
   */
  def encodeVideo(data: Int, disparityCount: Int): (Seq[Boolean], Int) = {
    val dBits = data.toBitSeq(8)
    var qBits = mutable.Seq.fill(10)(false)

    val numOnes = dBits.count(b => b)
    if (numOnes > 4 || (numOnes == 4 && !dBits(0))) {
      // XNOR
      qBits(0) = dBits(0)
      for (i <- 1 to 7) {
        qBits(i) = !(qBits(i - 1) ^ dBits(i))
      }
      qBits(8) = false
    } else {
      // XOR
      qBits(0) = dBits(0)
      for (i <- 1 to 7) {
        qBits(i) = qBits(i - 1) ^ dBits(i)
      }
      qBits(8) = true
    }

    val numQOnes = qBits.take(8).count(b => b)
    val numQZeros = 8 - numQOnes
    if (disparityCount == 0 || numQOnes == numQZeros) {
      if (!qBits(8)) {
        qBits(9) = true
        for (i <- 0 to 7) {
          qBits(i) = !qBits(i)
        }
      }
      val numQOnes = qBits.take(8).count(b => b)
      val numQZeros = 8 - numQOnes
      if (qBits(9)) {
        (qBits, numQZeros - numQOnes)
      } else {
        (qBits, numQOnes - numQZeros)
      }
    } else {
      if (
        (disparityCount > 0 && numQOnes > numQZeros) ||
        (disparityCount < 0 && numQZeros > numQOnes)
      ) {
        qBits(9) = true
        for (i <- 0 to 7) {
          qBits(i) = !qBits(i)
        }
        val numQOnes = qBits.take(8).count(b => b)
        val numQZeros = 8 - numQOnes
        (qBits, (if (qBits(8)) 2 else 0) + (numQZeros - numQOnes))
      } else {
        qBits(9) = false
        val numQOnes = qBits.take(8).count(b => b)
        val numQZeros = 8 - numQOnes
        (qBits, (if (qBits(8)) -2 else 0) + (numQOnes - numQZeros))
      }
    }
  }

  /** Software implementation of video decoding for testing and simulation
   *
   *  Returns decoded 8-bit data
   */
  def decodeVideo(tmdsCharacter: Seq[Boolean]): Int = {
    val flippedOrNot = if (tmdsCharacter(9)) {
      tmdsCharacter.map(b => !b).toSeq
    } else {
      tmdsCharacter
    }

    val bits = if (tmdsCharacter(8)) {
      Seq(flippedOrNot(0)) ++ (1 to 7).map(i => flippedOrNot(i) ^ flippedOrNot(i - 1))
    } else {
      Seq(flippedOrNot(0)) ++ (1 to 7).map(i => !(flippedOrNot(i) ^ flippedOrNot(i - 1)))
    }
    bits.bitsToInt
  }

  val EncoderLatency = 2
  val DecoderLatency = 1
}

case class TmdsVideoEncoder() extends Component {
  val io = new Bundle {
    val input = in(Bits(8 bits))
    val output = out(TmdsChar())
    val resetDisparity = in(Bool())
  }

  // ==================================
  //  STAGE 0 
  // ==================================
  val numDataOnes = CountOne(io.input)
  val qMBits = Vec(Bool(), 9)
  qMBits(0) := io.input(0)
  when(numDataOnes > 4 || (numDataOnes === 4 && !io.input(0))) {
    for (i <- 1 to 7) {
      qMBits(i) := !(qMBits(i - 1) ^ io.input(i))
    }
    qMBits(8) := False
  } otherwise {
    for (i <- 1 to 7) {
      qMBits(i) := qMBits(i - 1) ^ io.input(i)
    }
    qMBits(8) := True
  }

  // ==================================
  //  STAGE 1
  // ==================================
  val qM = RegNext(qMBits.asBits)
  val disparityCount = RegInit(S(0, 5 bits))
  val resetDisparity = RegNext(io.resetDisparity)

  val numQOnes = CountOne(qM(7 downto 0))
  val numQZeros = U(8) - numQOnes
  val invert = Bool()
  when(disparityCount === 0 || numQOnes === numQZeros) {
    invert := ~qM(8)
  } otherwise {
    invert := (disparityCount > 0 && numQOnes > numQZeros) || 
              (disparityCount < 0 && numQZeros > numQOnes)
  }

  val dataBits = Mux(invert, ~qM(7 downto 0), qM(7 downto 0))
  io.output := RegNext(invert ## qM(8) ## dataBits)

  val disparityDelta = SInt(4 bits)
  when(disparityCount === 0 || numQOnes === numQZeros) {
    disparityDelta := Mux(invert,
      (numQZeros - numQOnes).asSInt,
      (numQOnes - numQZeros).asSInt
    )
  } otherwise {
    when(invert) {
      val offset = Mux(qM(8), S(2), S(0))
      disparityDelta := offset + (numQZeros - numQOnes).asSInt
    } otherwise {
      val offset = Mux(qM(8), S(0), S(-2))
      disparityDelta := offset + (numQOnes - numQZeros).asSInt
    }
  }

  when(!resetDisparity) {
    disparityCount := disparityCount + disparityDelta
  } otherwise {
    disparityCount := S(0)
  }
}

case class TmdsVideoDecoder() extends Component {
  val io = new Bundle {
    val input = in(TmdsChar())
    val output = out(Bits(8 bits))
  }

  val flippedOrNot = Bits(8 bits)
  when(io.input(9)) {
    flippedOrNot := ~io.input(7 downto 0)
  } otherwise {
    flippedOrNot := io.input(7 downto 0)
  }
  val output = Bits(8 bits)
  when(io.input(8)) {
    // XOR
    output(0) := flippedOrNot(0)
    for (i <- 1 to 7) {
      output(i) := flippedOrNot(i) ^ flippedOrNot(i - 1)
    }
  } otherwise {
    // XNOR
    output(0) := flippedOrNot(0)
    for (i <- 1 to 7) {
      output(i) := ~(flippedOrNot(i) ^ flippedOrNot(i - 1))
    }
  }

  io.output := RegNext(output)
}

case class TmdsControlEncoder() extends Component {
  val io = new Bundle {
    val input = in(Bits(2 bits))
    val output = out(TmdsChar())
  }

  val output = TmdsChar()
  switch(io.input) {
    is(B"00") { output := B"1101010100" }
    is(B"01") { output := B"0010101011" }
    is(B"10") { output := B"0101010100" }
    is(B"11") { output := B"1010101011" }
  }

  io.output := Delay(output, Tmds.EncoderLatency)
}

case class TmdsControlDecoder() extends Component {
  val io = new Bundle {
    val input = in(TmdsChar)
    val output = out(Bits(2 bits))
    val error = out(Bool())
  }

  val output = Bits(2 bits)
  val error = False
  switch(io.input) {
    is(B"1101010100") { output := B"00" }
    is(B"0010101011") { output := B"01" }
    is(B"0101010100") { output := B"10" }
    is(B"1010101011") { output := B"11" }
    default {
      output := B"00"
      error := True
    }
  }

  io.output := Delay(output, Tmds.DecoderLatency)
  io.error := Delay(error, Tmds.DecoderLatency)
}
