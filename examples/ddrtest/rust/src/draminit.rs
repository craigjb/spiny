//                           /$$
//                          |__/
//        /$$$$$$$  /$$$$$$  /$$ /$$$$$$$  /$$   /$$
//       /$$_____/ /$$__  $$| $$| $$__  $$| $$  | $$
//      |  $$$$$$ | $$  \ $$| $$| $$  \ $$| $$  | $$   (c) Craig J Bishop
//       \____  $$| $$  | $$| $$| $$  | $$| $$  | $$   All rights reserved
//       /$$$$$$$/| $$$$$$$/| $$| $$  | $$|  $$$$$$$
//      |_______/ | $$____/ |__/|__/  |__/ \____  $$   MIT License
//                | $$                     /$$  | $$
//                | $$                    |  $$$$$$/
//                |__/                     \______/
//
// Permission is hereby granted, free of charge, to any person obtaining a
// copy of this software and associated documentation files (the
// "Software"), to deal in the Software without restriction, including
// without limitation the rights to use, copy, modify, merge, publish,
// distribute, sublicense, and/or sell copies of the Software, and to permit
// persons to whom the Software is furnished to do so, subject to the
// following conditions:
//
// The above copyright notice and this permission notice shall be included
// in all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
// OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
// MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
// NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
// DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
// OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE
// USE OR OTHER DEALINGS IN THE SOFTWARE.

#[cfg(not(feature = "sim"))]
use ddrtest_pac::Ddrphy;
use ddrtest_pac::{Ddrctrl, Sdram};

#[derive(Clone, Copy)]
#[repr(u8)]
enum Phase {
    P0 = 0,
    P1 = 1,
    P2 = 2,
    P3 = 3,
}

const ALL_PHASES: [Phase; 4] = [Phase::P0, Phase::P1, Phase::P2, Phase::P3];
const RDPHASE: Phase = Phase::P2;
const WRPHASE: Phase = Phase::P3;

const NUM_LANES: usize = 2;
const NUM_BITSLIPS: usize = 8;
const NUM_DELAYS: usize = 32;

const LFSR_TAPS: u32 = 0x80200003;
const LFSR_SEEDS: [u32; 3] = [42, 84, 36];
const MAX_ERRORS: i32 = 96;
const LANE_MASKS: [u32; NUM_LANES] = [0x00FF00FF, 0xFF00FF00];

// DDR3 mode register values (Nexys Video)
const MR0_ADDR: u32 = 0x930; // CL=7, BL=8
const MR1_ADDR: u32 = 0x006;
const MR2_ADDR: u32 = 0x200; // CWL=5
const MR3_ADDR: u32 = 0x000;
const ZQCAL_ADDR: u32 = 0x400;

// Delay constants for riscv::asm::delay(), which runs ~2 cycles per count.
// At 100MHz sys_clk: delay(50_000) ~ 1ms.
#[cfg(feature = "sim")]
const DELAY_SHORT: u32 = 10;
#[cfg(not(feature = "sim"))]
const DELAY_SHORT: u32 = 50;

#[cfg(feature = "sim")]
const DELAY_100US: u32 = 10;
#[cfg(not(feature = "sim"))]
const DELAY_100US: u32 = 5_000;

#[cfg(feature = "sim")]
const DELAY_1MS: u32 = 10;
#[cfg(not(feature = "sim"))]
const DELAY_1MS: u32 = 50_000;

#[cfg(feature = "sim")]
const DELAY_20MS: u32 = 10;
#[cfg(not(feature = "sim"))]
const DELAY_20MS: u32 = 1_000_000;

/// Write data to the wrdata register for the given DFI phase.
fn write_phase_wrdata(sdram: &Sdram, phase: Phase, data: u32) {
    match phase {
        Phase::P0 => sdram.dfii_pi0_wrdata().write(|w| unsafe { w.bits(data) }),
        Phase::P1 => sdram.dfii_pi1_wrdata().write(|w| unsafe { w.bits(data) }),
        Phase::P2 => sdram.dfii_pi2_wrdata().write(|w| unsafe { w.bits(data) }),
        Phase::P3 => sdram.dfii_pi3_wrdata().write(|w| unsafe { w.bits(data) }),
    };
}

/// Read data from the rddata register for the given DFI phase.
fn read_phase_rddata(sdram: &Sdram, phase: Phase) -> u32 {
    match phase {
        Phase::P0 => sdram.dfii_pi0_rddata().read().bits(),
        Phase::P1 => sdram.dfii_pi1_rddata().read().bits(),
        Phase::P2 => sdram.dfii_pi2_rddata().read().bits(),
        Phase::P3 => sdram.dfii_pi3_rddata().read().bits(),
    }
}

/// Switch DFII to software control mode (CKE | ODT | RESET_N).
///
/// Note: dfii_control has reset value is 0x01 (SEL set), so write() starts
/// with SEL=1. Must explicitly clear it to hand control to software.
fn dfii_sw_control(sdram: &Sdram) {
    sdram.dfii_control().write(|w| {
        w.sel()
            .clear_bit()
            .cke()
            .set_bit()
            .odt()
            .set_bit()
            .reset_n()
            .set_bit()
    });
}

/// Switch DFII to hardware control mode (SEL | CKE | ODT | RESET_N).
fn dfii_hw_control(sdram: &Sdram) {
    sdram.dfii_control().write(|w| {
        w.sel()
            .set_bit()
            .cke()
            .set_bit()
            .odt()
            .set_bit()
            .reset_n()
            .set_bit()
    });
}

/// 32-bit Galois LFSR step (taps: 0x80200003).
fn lfsr32(prev: u32) -> u32 {
    let lsb = prev & 1;
    let mut next = prev >> 1;
    if lsb != 0 {
        next ^= LFSR_TAPS;
    }
    next
}

/// Generate a 4-phase PRNG test pattern from a seed.
///
/// Returns one 32-bit word per phase, each built from 32 consecutive LFSR
/// output bits.
fn generate_pattern(seed: u32) -> [u32; 4] {
    let mut pattern = [0u32; 4];
    let mut prv = seed;
    for p in 0..4 {
        let mut word = 0u32;
        for bit in 0..32 {
            prv = lfsr32(prv);
            word |= (prv & 1) << bit;
        }
        pattern[p] = word;
    }
    pattern
}

/// Write a test pattern to DRAM via DFI, read it back, and count bit errors
/// for a single byte lane.
fn dfi_write_read_check(sdram: &Sdram, lane: usize, seed: u32) -> u32 {
    let pattern = generate_pattern(seed);

    // Activate row 0, bank 0 on P0
    sdram.dfii_pi0_address().write(|w| unsafe { w.bits(0) });
    sdram.dfii_pi0_baddress().write(|w| unsafe { w.bits(0) });
    sdram
        .dfii_pi0_command()
        .write(|w| w.ras().set_bit().cs().set_bit());
    sdram
        .dfii_pi0_command_issue()
        .write(|w| unsafe { w.bits(1) });
    riscv::asm::delay(DELAY_SHORT);

    // Write pattern to all phases
    for (i, &phase) in ALL_PHASES.iter().enumerate() {
        write_phase_wrdata(sdram, phase, pattern[i]);
    }

    // Issue write on WRPHASE (P3)
    sdram.dfii_pi3_address().write(|w| unsafe { w.bits(0) });
    sdram.dfii_pi3_baddress().write(|w| unsafe { w.bits(0) });
    sdram.dfii_pi3_command().write(|w| {
        w.cas()
            .set_bit()
            .we()
            .set_bit()
            .cs()
            .set_bit()
            .wren()
            .set_bit()
    });
    sdram
        .dfii_pi3_command_issue()
        .write(|w| unsafe { w.bits(1) });
    riscv::asm::delay(DELAY_SHORT);

    // Issue read on RDPHASE (P2)
    sdram.dfii_pi2_address().write(|w| unsafe { w.bits(0) });
    sdram.dfii_pi2_baddress().write(|w| unsafe { w.bits(0) });
    sdram
        .dfii_pi2_command()
        .write(|w| w.cas().set_bit().cs().set_bit().rden().set_bit());
    sdram
        .dfii_pi2_command_issue()
        .write(|w| unsafe { w.bits(1) });
    riscv::asm::delay(DELAY_SHORT);

    // Precharge on P0
    sdram.dfii_pi0_address().write(|w| unsafe { w.bits(0) });
    sdram.dfii_pi0_baddress().write(|w| unsafe { w.bits(0) });
    sdram
        .dfii_pi0_command()
        .write(|w| w.ras().set_bit().we().set_bit().cs().set_bit());
    sdram
        .dfii_pi0_command_issue()
        .write(|w| unsafe { w.bits(1) });
    riscv::asm::delay(DELAY_SHORT);

    // Compare read data with expected pattern
    let mask = LANE_MASKS[lane];
    let mut errors = 0u32;
    for (i, &phase) in ALL_PHASES.iter().enumerate() {
        let rddata = read_phase_rddata(sdram, phase);
        let diff = (rddata ^ pattern[i]) & mask;
        errors += diff.count_ones();
    }

    errors
}

/// Run the write/read test with multiple LFSR seeds for robustness.
fn run_test_pattern(sdram: &Sdram, lane: usize) -> u32 {
    let mut total_errors = 0u32;
    for &seed in &LFSR_SEEDS {
        total_errors += dfi_write_read_check(sdram, lane, seed);
    }
    total_errors
}

/// Sweep all delay taps for the current bitslip and return a composite score.
///
/// Higher scores indicate more working taps and fewer errors.
#[cfg(not(feature = "sim"))]
fn scan_lane(phy: &Ddrphy, sdram: &Sdram, lane: usize) -> i32 {
    let mut score = 0i32;

    phy.rdly_dq_rst().write(|w| unsafe { w.bits(1) });

    for _tap in 0..NUM_DELAYS {
        let errors = run_test_pattern(sdram, lane) as i32;
        let working = if errors == 0 { 1i32 } else { 0i32 };
        score += working * MAX_ERRORS * (NUM_DELAYS as i32) + (MAX_ERRORS - errors);
        phy.rdly_dq_inc().write(|w| unsafe { w.bits(1) });
        riscv::asm::delay(DELAY_100US);
    }

    score
}

/// Find the working delay window for a lane and set the delay to its midpoint.
///
/// Requires 2 consecutive working taps to establish the window start, then
/// scans forward to find the end.
#[cfg(not(feature = "sim"))]
fn center_lane(phy: &Ddrphy, sdram: &Sdram, lane: usize) {
    phy.rdly_dq_rst().write(|w| unsafe { w.bits(1) });

    // Find first working delay (require 2 consecutive working taps)
    let mut delay_min: i32 = -1;
    let mut last_working = false;
    for tap in 0..NUM_DELAYS {
        let errors = run_test_pattern(sdram, lane);
        let working = errors == 0;
        if working && last_working && delay_min < 0 {
            delay_min = (tap as i32) - 1;
            break;
        }
        last_working = working;
        phy.rdly_dq_inc().write(|w| unsafe { w.bits(1) });
        riscv::asm::delay(DELAY_100US);
    }

    if delay_min < 0 {
        defmt::warn!("  lane {}: no working delay window found!", lane);
        return;
    }

    // Find last working delay
    let mut delay_max = delay_min;
    for tap in (delay_min + 2)..(NUM_DELAYS as i32) {
        let errors = run_test_pattern(sdram, lane);
        let working = errors == 0;
        if working {
            delay_max = tap;
        } else {
            break;
        }
        phy.rdly_dq_inc().write(|w| unsafe { w.bits(1) });
        riscv::asm::delay(DELAY_100US);
    }

    let mid = (delay_min + delay_max) / 2;
    defmt::debug!(
        "  lane {}: delay window: {}-{}, center: {}",
        lane,
        delay_min,
        delay_max,
        mid
    );

    // Set delay to midpoint
    phy.rdly_dq_rst().write(|w| unsafe { w.bits(1) });
    for _ in 0..mid {
        phy.rdly_dq_inc().write(|w| unsafe { w.bits(1) });
    }
}

/// Configure PHY read/write phases, reset per-lane delays/bitslips, and
/// toggle the PHY reset.
#[cfg(not(feature = "sim"))]
fn phy_init(phy: &Ddrphy) {
    defmt::info!("PHY INIT: Starting...");

    phy.rdphase().write(|w| unsafe { w.bits(RDPHASE as u32) });
    phy.wrphase().write(|w| unsafe { w.bits(WRPHASE as u32) });

    for lane in 0..NUM_LANES {
        phy.dly_sel().write(|w| unsafe { w.bits(1 << lane) });
        phy.rdly_dq_rst().write(|w| unsafe { w.bits(1) });
        phy.rdly_dq_bitslip_rst().write(|w| unsafe { w.bits(1) });
    }

    phy.rst().write(|w| unsafe { w.bits(1) });
    riscv::asm::delay(DELAY_1MS);
    phy.rst().write(|w| unsafe { w.bits(0) });
    riscv::asm::delay(DELAY_1MS);

    defmt::info!("PHY INIT: Done");
}

/// Run the DDR3 SDRAM initialization sequence.
///
/// Performs DDR3 power-up (CKE high), mode register loads, and ZQ
/// calibration. Caller must set DFII to software control before calling.
fn init_sequence(sdram: &Sdram) {
    defmt::info!("DRAM INIT: Starting...");

    defmt::debug!("Bring CKE high");
    sdram.dfii_pi0_address().write(|w| unsafe { w.bits(0) });
    sdram.dfii_pi0_baddress().write(|w| unsafe { w.bits(0) });
    dfii_sw_control(sdram);
    riscv::asm::delay(DELAY_20MS);

    // Load Mode Register 2, CWL=5
    defmt::debug!("Load MR2");
    sdram
        .dfii_pi0_address()
        .write(|w| unsafe { w.bits(MR2_ADDR) });
    sdram.dfii_pi0_baddress().write(|w| unsafe { w.bits(2) });
    sdram.dfii_pi0_command().write(|w| {
        w.ras()
            .set_bit()
            .cas()
            .set_bit()
            .we()
            .set_bit()
            .cs()
            .set_bit()
    });
    sdram
        .dfii_pi0_command_issue()
        .write(|w| unsafe { w.bits(1) });

    // Load Mode Register 3
    defmt::debug!("Load MR3");
    sdram
        .dfii_pi0_address()
        .write(|w| unsafe { w.bits(MR3_ADDR) });
    sdram.dfii_pi0_baddress().write(|w| unsafe { w.bits(3) });
    sdram.dfii_pi0_command().write(|w| {
        w.ras()
            .set_bit()
            .cas()
            .set_bit()
            .we()
            .set_bit()
            .cs()
            .set_bit()
    });
    sdram
        .dfii_pi0_command_issue()
        .write(|w| unsafe { w.bits(1) });

    // Load Mode Register 1
    defmt::debug!("Load MR1");
    sdram
        .dfii_pi0_address()
        .write(|w| unsafe { w.bits(MR1_ADDR) });
    sdram.dfii_pi0_baddress().write(|w| unsafe { w.bits(1) });
    sdram.dfii_pi0_command().write(|w| {
        w.ras()
            .set_bit()
            .cas()
            .set_bit()
            .we()
            .set_bit()
            .cs()
            .set_bit()
    });
    sdram
        .dfii_pi0_command_issue()
        .write(|w| unsafe { w.bits(1) });

    // Load Mode Register 0, CL=7, BL=8
    defmt::debug!("Load MR0");
    sdram
        .dfii_pi0_address()
        .write(|w| unsafe { w.bits(MR0_ADDR) });
    sdram.dfii_pi0_baddress().write(|w| unsafe { w.bits(0) });
    sdram.dfii_pi0_command().write(|w| {
        w.ras()
            .set_bit()
            .cas()
            .set_bit()
            .we()
            .set_bit()
            .cs()
            .set_bit()
    });
    sdram
        .dfii_pi0_command_issue()
        .write(|w| unsafe { w.bits(1) });
    riscv::asm::delay(DELAY_1MS);

    // ZQ Calibration
    defmt::debug!("ZQ Calibration");
    sdram
        .dfii_pi0_address()
        .write(|w| unsafe { w.bits(ZQCAL_ADDR) });
    sdram.dfii_pi0_baddress().write(|w| unsafe { w.bits(0) });
    sdram
        .dfii_pi0_command()
        .write(|w| w.we().set_bit().cs().set_bit());
    sdram
        .dfii_pi0_command_issue()
        .write(|w| unsafe { w.bits(1) });
    riscv::asm::delay(DELAY_1MS);

    defmt::info!("DRAM INIT: Done");
}

/// Perform read leveling across all byte lanes.
///
/// For each lane: sweeps all bitslip settings, scores each by scanning the
/// full delay range, selects the best bitslip, then centers the delay within
/// the working window.
#[cfg(not(feature = "sim"))]
fn read_leveling(phy: &Ddrphy, sdram: &Sdram) {
    defmt::info!("READ LEVELING: Starting...");

    for lane in 0..NUM_LANES {
        defmt::debug!("  Lane {}:", lane);
        phy.dly_sel().write(|w| unsafe { w.bits(1u32 << lane) });

        let mut best_score = i32::MIN;
        let mut best_bitslip = 0usize;

        phy.rdly_dq_bitslip_rst().write(|w| unsafe { w.bits(1) });

        for bitslip in 0..NUM_BITSLIPS {
            let score = scan_lane(phy, sdram, lane);
            defmt::debug!("    bitslip {}: score {}", bitslip, score);

            if score > best_score {
                best_score = score;
                best_bitslip = bitslip;
            }

            if bitslip < NUM_BITSLIPS - 1 {
                phy.rdly_dq_bitslip().write(|w| unsafe { w.bits(1) });
            }
        }

        defmt::debug!("    best bitslip: {} (score {})", best_bitslip, best_score);

        // Reset to best bitslip
        phy.rdly_dq_bitslip_rst().write(|w| unsafe { w.bits(1) });
        for _ in 0..best_bitslip {
            phy.rdly_dq_bitslip().write(|w| unsafe { w.bits(1) });
        }

        center_lane(phy, sdram, lane);
    }

    defmt::info!("READ LEVELING: Done");
}

/// Initialize DDR3 SDRAM and perform read leveling.
///
/// Runs the full DDR3 power-up and mode register programming sequence, then
/// performs per-lane read leveling (bitslip sweep + delay centering) before
/// handing control to the hardware memory controller.
pub fn init_dram(ddrctrl: &Ddrctrl, #[cfg(not(feature = "sim"))] phy: &Ddrphy, sdram: &Sdram) {
    ddrctrl.init_done().write(|w| unsafe { w.bits(0) });
    ddrctrl.init_error().write(|w| unsafe { w.bits(0) });

    dfii_sw_control(sdram);
    #[cfg(not(feature = "sim"))]
    phy_init(phy);
    init_sequence(sdram);
    #[cfg(not(feature = "sim"))]
    read_leveling(phy, sdram);
    dfii_hw_control(sdram);

    ddrctrl.init_done().write(|w| w.init_done().set_bit());
}
