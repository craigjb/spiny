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

package spiny

import spinal.core._
import spinal.lib._
import spinal.lib.blackbox.xilinx.s7.{MMCME2_BASE, BUFG}
import scala.collection.mutable.ArrayBuffer

case class ClockRequest(
  freq: HertzNumber,
  tolerance: Double = 0.0,
  phase: Double = 0.0
)

case class ClockGen(
  vcoMin: HertzNumber = 600 MHz,
  vcoMax: HertzNumber = 1200 MHz,
  inputFreqMin: HertzNumber = 10 MHz,
  outputFreqMin: HertzNumber = 4.69 MHz,
  outputFreqMax: HertzNumber = 800 MHz
) extends Component {
  val io = new Bundle {
    val locked = out(Bool())
  }

  private class PendingOutput(
    val req: ClockRequest,
    val clk: Bool,
    val rst: Bool,
    val cd: ClockDomain,
    val dividedFrom: Option[(PendingOutput, Int)] // (parent, divisor)
  )

  private case class SolvedOutput(
    mmcmIndex: Int,
    divide: Double,
    actualFreqHz: Double,
    phase: Double
  )

  private case class Solution(m: Double, d: Int, outputs: Seq[SolvedOutput])

  private val pending = ArrayBuffer[PendingOutput]()
  private var built = false

  def request(
    freq: HertzNumber,
    tolerance: Double = 0.0,
    phase: Double = 0.0
  ): ClockDomain = request(ClockRequest(
    freq = freq,
    tolerance = tolerance,
    phase = phase
  ))

  def request(req: ClockRequest): ClockDomain = {
    assert(!built, "Cannot call request() after build()")
    assert(pending.size < 6, "MMCM supports at most 6 clock outputs")
    assert(
      req.freq >= outputFreqMin && req.freq <= outputFreqMax,
      s"Requested frequency ${req.freq.toDouble / 1e6} MHz outside allowed range " +
      s"[${outputFreqMin.toDouble / 1e6}, ${outputFreqMax.toDouble / 1e6}] MHz"
    )
    val clk = Bool()
    val rst = Bool()
    val cd = ClockDomain(
      clock = clk,
      reset = rst,
      frequency = FixedFrequency(req.freq),
      config = ClockDomainConfig(resetKind = SYNC)
    )
    pending += new PendingOutput(req, clk, rst, cd, dividedFrom = None)
    cd
  }

  def requestDivided(parent: ClockDomain, divisor: Int, phase: Double = 0.0): ClockDomain = {
    assert(!built, "Cannot call requestDivided() after build()")
    assert(pending.size < 6, "MMCM supports at most 6 clock outputs")
    assert(divisor >= 2, "Divisor must be >= 2")
    val parentPending = pending.find(_.cd eq parent)
    assert(parentPending.isDefined, "Parent ClockDomain not found in pending requests")
    val parentReq = parentPending.get.req
    val freq: HertzNumber = (parentReq.freq.toDouble / divisor).Hz
    assert(
      freq >= outputFreqMin && freq <= outputFreqMax,
      s"Divided frequency ${freq.toDouble / 1e6} MHz outside allowed range " +
      s"[${outputFreqMin.toDouble / 1e6}, ${outputFreqMax.toDouble / 1e6}] MHz"
    )
    val clk = Bool()
    val rst = Bool()
    val cd = ClockDomain(
      clock = clk,
      reset = rst,
      frequency = FixedFrequency(freq),
      config = ClockDomainConfig(resetKind = SYNC)
    )
    pending += new PendingOutput(
      ClockRequest(freq, phase = phase), clk, rst, cd,
      dividedFrom = Some((parentPending.get, divisor))
    )
    cd
  }

  private def quantize125(v: Double): Double = Math.round(v * 8.0) / 8.0

  private def tryOutputs(
    fVco: Double,
    m: Double,
    d: Int,
    fractionalIdx: Option[Int]
  ): Option[(Solution, Double)] = {
    val startIdx = if (fractionalIdx.isDefined) 1 else 0
    val solved = scala.collection.mutable.Map[PendingOutput, SolvedOutput]()

    // Solve independent outputs
    val independent = pending.zipWithIndex
      .filter(_._1.dividedFrom.isEmpty)
      .foldLeft(Option((startIdx, 0.0))) {
        case (None, _) => None
        case (Some((nextIdx, totalErr)), (p, i)) =>
          val idealDiv = fVco / p.req.freq.toDouble
          val (divide, mmcmIdx, nextNext) = if (fractionalIdx.contains(i)) {
            (quantize125(idealDiv).max(1.0).min(128.0), 0, nextIdx)
          } else {
            (Math.round(idealDiv).toInt.max(1).min(128).toDouble, nextIdx, nextIdx + 1)
          }
          val actual = fVco / divide
          val relErr = Math.abs(actual - p.req.freq.toDouble) / p.req.freq.toDouble
          if (relErr > p.req.tolerance) None
          else {
            solved(p) = SolvedOutput(mmcmIdx, divide, actual, p.req.phase)
            Some((nextNext, totalErr + relErr))
          }
      }

    // Derive divided outputs from their parents
    independent.flatMap { case (nextIdx, totalErr) =>
      pending.filter(_.dividedFrom.isDefined)
        .foldLeft(Option((nextIdx, totalErr))) {
          case (None, _) => None
          case (Some((idx, err)), p) =>
            val (parent, divisor) = p.dividedFrom.get
            val divide = solved(parent).divide * divisor
            if (divide < 1.0 || divide > 128.0 || divide != Math.floor(divide)) None
            else {
              solved(p) = SolvedOutput(idx, divide, fVco / divide, p.req.phase)
              Some((idx + 1, err))
            }
        }
    }.map { case (_, totalErr) =>
      (Solution(m, d, pending.map(solved).toSeq), totalErr)
    }
  }

  private def solve(inputFreqHz: Double): Solution = {
    val vcoMinHz = vcoMin.toDouble
    val vcoMaxHz = vcoMax.toDouble

    var bestSolution: Option[Solution] = None
    var bestError: Double = Double.MaxValue

    def recordCandidate(sol: Solution, err: Double): Unit = {
      if (err < bestError) {
        bestError = err
        bestSolution = Some(sol)
      }
    }

    for (d <- 1 to 106) {
      val mMinRaw = vcoMinHz * d / inputFreqHz
      val mMaxRaw = vcoMaxHz * d / inputFreqHz
      val mMin = (Math.ceil(mMinRaw * 8.0) / 8.0).max(2.0)
      val mMax = (Math.floor(mMaxRaw * 8.0) / 8.0).min(64.0)
      if (mMin <= mMax) {
        val steps = ((mMax - mMin) / 0.125 + 0.5).toInt
        for (step <- 0 to steps) {
          val m = mMin + step * 0.125
          val fVco = inputFreqHz * m / d

          // Pass 1: all integer dividers
          val pass1 = tryOutputs(fVco, m, d, None)
          pass1.foreach { case (sol, err) => recordCandidate(sol, err) }

          // Pass 2: one fractional (only if pass 1 failed for this D, M)
          if (pass1.isEmpty) {
            for (fracIdx <- pending.indices if pending(fracIdx).dividedFrom.isEmpty) {
              tryOutputs(fVco, m, d, Some(fracIdx)).foreach {
                case (sol, err) => recordCandidate(sol, err)
              }
            }
          }
        }
      }
    }

    bestSolution.getOrElse {
      val requestStr = pending.zipWithIndex.map { case (p, i) =>
        s"  [$i] ${p.req.freq.toDouble / 1e6} MHz (tolerance=${p.req.tolerance}, phase=${p.req.phase})"
      }.mkString("\n")
      SpinalError(
        s"ClockGen: No MMCM solution found for input ${inputFreqHz / 1e6} MHz\n" +
        s"Requests:\n$requestStr\n" +
        s"VCO range: ${vcoMinHz / 1e6}-${vcoMaxHz / 1e6} MHz"
      )
    }
  }

  def build(): Unit = {
    assert(!built, "build() already called")
    assert(pending.nonEmpty, "No clock requests registered")
    built = true

    val inputFreqHz = ClockDomain.current.frequency.getValue.toDouble
    assert(
      inputFreqHz >= inputFreqMin.toDouble,
      s"Input frequency ${inputFreqHz / 1e6} MHz below minimum ${inputFreqMin.toDouble / 1e6} MHz"
    )
    val solution = solve(inputFreqHz)

    // Print solution summary
    val fVco = inputFreqHz * solution.m / solution.d
    println(f"[ClockGen] Input: ${inputFreqHz / 1e6}%.3f MHz, M=${solution.m}, D=${solution.d}, VCO=${fVco / 1e6}%.3f MHz")
    for ((out, i) <- solution.outputs.zipWithIndex) {
      val reqFreq = pending(i).req.freq.toDouble
      val error = (out.actualFreqHz - reqFreq) / reqFreq * 100.0
      println(f"[ClockGen]   Output $i -> CLKOUT${out.mmcmIndex}: ${out.actualFreqHz / 1e6}%.3f MHz (error: $error%.4f%%, divide: ${out.divide})")
    }

    // Map MMCM output index to solved output (defaults for unused outputs)
    val byMmcmIdx = solution.outputs.map(o => o.mmcmIndex -> o).toMap
    def divide(i: Int) = byMmcmIdx.get(i).map(_.divide).getOrElse(1.0)
    def phase(i: Int) = byMmcmIdx.get(i).map(_.phase).getOrElse(0.0)

    val inputPeriodNs = 1.0e9 / inputFreqHz
    val mmcm = MMCME2_BASE(
      CLKIN1_PERIOD = inputPeriodNs,
      CLKFBOUT_MULT_F = solution.m,
      DIVCLK_DIVIDE = solution.d.toDouble,
      CLKOUT0_DIVIDE_F = divide(0),
      CLKOUT1_DIVIDE = divide(1),
      CLKOUT2_DIVIDE = divide(2),
      CLKOUT3_DIVIDE = divide(3),
      CLKOUT4_DIVIDE = divide(4),
      CLKOUT5_DIVIDE = divide(5),
      CLKOUT0_PHASE = phase(0),
      CLKOUT1_PHASE = phase(1),
      CLKOUT2_PHASE = phase(2),
      CLKOUT3_PHASE = phase(3),
      CLKOUT4_PHASE = phase(4),
      CLKOUT5_PHASE = phase(5)
    )

    mmcm.CLKIN1 := ClockDomain.current.readClockWire
    mmcm.CLKFBIN := mmcm.CLKFBOUT
    mmcm.RST := ClockDomain.current.isResetActive
    mmcm.PWRDWN := False

    io.locked := mmcm.LOCKED

    val clkouts = Seq(
      mmcm.CLKOUT0, mmcm.CLKOUT1, mmcm.CLKOUT2,
      mmcm.CLKOUT3, mmcm.CLKOUT4, mmcm.CLKOUT5
    )

    for ((out, i) <- solution.outputs.zipWithIndex) {
      val bufgOut = BUFG.on(clkouts(out.mmcmIndex))
      pending(i).clk := bufgOut
      val cd = ClockDomain(bufgOut)
      val syncReset = cd(BufferCC(!mmcm.LOCKED, init = True))
      pending(i).rst := syncReset
    }
  }
}
