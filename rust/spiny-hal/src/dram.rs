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

// ---- DDR3 Mode Register Calculation ----

/// Format DDR3 MR0: BL=8 (fixed), CL and WR from JEDEC encoding, DLL reset bit.
pub const fn ddr3_format_mr0(cl: u32, wr: u32, dll_reset: bool) -> u32 {
    // BL=8 → bits[1:0] = 0b00
    let bl = 0u32;

    // CL encoding: CL 5..14 → A[6:4],A[2]
    // CL:      5    6    7    8    9   10   11   12   13   14
    // code:  0b001 010  011  100  101  110  111  000  001  010
    let cl_code = match cl {
        5 => 0b0010,
        6 => 0b0100,
        7 => 0b0110,
        8 => 0b1000,
        9 => 0b1010,
        10 => 0b1100,
        11 => 0b1110,
        12 => 0b0001,
        13 => 0b0011,
        14 => 0b0101,
        _ => 0,
    };
    // cl_code[0] → A2, cl_code[3:1] → A[6:4]
    let cl_bits = ((cl_code & 1) << 2) | (((cl_code >> 1) & 0x7) << 4);

    // WR encoding: WR 5..16 → A[11:9]
    let wr_code = match wr {
        5 => 1,
        6 => 2,
        7 => 3,
        8 => 4,
        10 => 5,
        12 => 6,
        14 => 7,
        16 => 0,
        _ => 0,
    };
    let wr_bits = (wr_code & 0x7) << 9;

    let dll_reset_bit = if dll_reset { 1 << 8 } else { 0 };

    bl | cl_bits | dll_reset_bit | wr_bits
}

/// Format DDR3 MR1: RON + RTT_NOM encoding, DLL enable, TDQS=0.
pub const fn ddr3_format_mr1(ron_ohms: u32, rtt_nom_ohms: u32) -> u32 {
    // RON (output driver impedance): A[5],A[1]
    let ron_bits = match ron_ohms {
        40 => 0b00, // RZQ/6
        34 => 0b01, // RZQ/7
        _ => 0b01,  // default 34ohm
    };
    let ron = ((ron_bits & 1) << 1) | (((ron_bits >> 1) & 1) << 5);

    // RTT_NOM: A[9],A[6],A[2]
    let rtt_code = match rtt_nom_ohms {
        0 => 0b000,   // disabled
        60 => 0b001,  // RZQ/4
        120 => 0b010, // RZQ/2
        40 => 0b011,  // RZQ/6
        20 => 0b100,  // RZQ/12
        30 => 0b101,  // RZQ/8
        _ => 0b001,   // default 60ohm
    };
    let rtt = ((rtt_code & 1) << 2) | (((rtt_code >> 1) & 1) << 6) | (((rtt_code >> 2) & 1) << 9);

    // DLL enable (bit 0) = 0 means enabled
    ron | rtt
}

/// Format DDR3 MR2: CWL encoding + RTT_WR.
pub const fn ddr3_format_mr2(cwl: u32, rtt_wr_ohms: u32) -> u32 {
    // CWL encoding: (CWL - 5) << 3
    let cwl_bits = if cwl >= 5 { (cwl - 5) << 3 } else { 0 };

    // RTT_WR: A[10:9]
    let rtt_wr = match rtt_wr_ohms {
        0 => 0b00,   // disabled
        60 => 0b01,  // RZQ/4
        120 => 0b10, // RZQ/2
        _ => 0b01,   // default 60ohm
    };
    let rtt_wr_bits = (rtt_wr & 0x3) << 9;

    cwl_bits | rtt_wr_bits
}

// ---- DDR2 Mode Register Calculation ----

/// Format DDR2 MR: BL=4 (fixed), CL, WR=2.
pub const fn ddr2_format_mr(cl: u32) -> u32 {
    // BL=4 → log2(4)=2 → bits[2:0]
    let bl = 2u32;
    // CL → A[6:4]
    let cl_bits = (cl & 0x7) << 4;
    // WR=2 → A[11:9] (WR encoding = WR - 1 = 1)
    let wr_bits = 1u32 << 9;
    bl | cl_bits | wr_bits
}

/// Format DDR2 EMR (Extended Mode Register 1): DLL enable, defaults.
pub const fn ddr2_format_emr() -> u32 {
    // DLL enable (bit 0 = 0), all other bits 0 for basic config
    0
}

// ---- Lane Mask Helper ----

/// Compute a 32-bit lane mask for a given byte group.
///
/// For `num_byte_groups=2`: lane 0 → `0x00FF00FF`, lane 1 → `0xFF00FF00`.
pub const fn lane_mask(byte_group: u32, num_byte_groups: u32) -> u32 {
    let mut mask = 0u32;
    let mut i = byte_group;
    while i < 4 {
        mask |= 0xFF << (i * 8);
        i += num_byte_groups;
    }
    mask
}

// ---- Common DFII Helpers ----

/// Internal macro for code shared between DDR2 and DDR3 HAL macros.
///
/// Generates delay constants, DFII software/hardware control, command_p0,
/// and load_mr helpers. All items are non-pub.
#[macro_export]
#[doc(hidden)]
macro_rules! dram_hal_common {
    (
        pac: $pac:ident,
        user_clk_freq_hz: $freq:expr,
        sim: $sim:expr $(,)?
    ) => {
        #[allow(dead_code)]
        const DELAY_SHORT: u32 = if $sim { 10 } else { $freq / 2_000_000 };
        #[allow(dead_code)]
        const DELAY_100US: u32 = if $sim { 10 } else { $freq / 20_000 };
        #[allow(dead_code)]
        const DELAY_1MS: u32 = if $sim { 10 } else { $freq / 2_000 };
        #[allow(dead_code)]
        const DELAY_20MS: u32 = if $sim { 10 } else { $freq / 100 };

        /// Switch DFII to software control mode (CKE | ODT | RESET_N).
        fn dfii_sw_control(sdram: &$pac::Sdram) {
            sdram.dfii_control().write(|w| {
                w.sel().clear_bit()
                    .cke().set_bit()
                    .odt().set_bit()
                    .reset_n().set_bit()
            });
        }

        /// Switch DFII to hardware control mode (SEL | CKE | ODT | RESET_N).
        fn dfii_hw_control(sdram: &$pac::Sdram) {
            sdram.dfii_control().write(|w| {
                w.sel().set_bit()
                    .cke().set_bit()
                    .odt().set_bit()
                    .reset_n().set_bit()
            });
        }

        /// Issue a command on phase 0.
        fn command_p0(sdram: &$pac::Sdram, cs: bool, we: bool, cas: bool, ras: bool) {
            sdram.dfii_pi0_command().write(|w| {
                w.cs().bit(cs)
                    .we().bit(we)
                    .cas().bit(cas)
                    .ras().bit(ras)
            });
            sdram.dfii_pi0_command_issue().write(|w| unsafe { w.bits(1) });
        }

        /// Load a mode register via phase 0.
        fn load_mr(sdram: &$pac::Sdram, bank: u32, value: u32) {
            sdram.dfii_pi0_address().write(|w| unsafe { w.bits(value) });
            sdram.dfii_pi0_baddress().write(|w| unsafe { w.bits(bank) });
            command_p0(sdram, true, true, true, true); // CS + WE + CAS + RAS = MRS
            riscv::asm::delay(DELAY_SHORT);
        }
    };
}

// ---- DDR3 HAL Macro ----

/// Generates a DDR3 SDRAM init module with computed mode register values,
/// PHY initialization, and read leveling.
///
/// # Parameters
///
/// | Parameter | Description |
/// |-----------|-------------|
/// | `pac` | PAC crate name (e.g., `ddrtest_pac`) |
/// | `cl` | CAS latency (computed by Scala) |
/// | `cwl` | CAS write latency (computed by Scala) |
/// | `wr` | Write recovery (computed by Scala) |
/// | `nphases` | Number of PHY phases |
/// | `num_byte_groups` | Number of DQ byte groups (lanes) |
/// | `user_clk_freq_hz` | User clock frequency in Hz |
/// | `sim` | `true` for simulation (short delays, no PHY init) |
/// | `rtt_nom_ohms` | RTT_NOM impedance in ohms |
/// | `rtt_wr_ohms` | RTT_WR impedance in ohms |
/// | `ron_ohms` | Output driver impedance in ohms |
#[macro_export]
macro_rules! ddr3_hal {
    // ---- Non-sim arm: full PHY init + read leveling ----
    (
        pac: $pac:ident,
        cl: $cl:expr,
        cwl: $cwl:expr,
        wr: $wr:expr,
        nphases: $nphases:expr,
        num_byte_groups: $nbg:expr,
        user_clk_freq_hz: $freq:expr,
        sim: false,
        rtt_nom_ohms: $rtt_nom:expr,
        rtt_wr_ohms: $rtt_wr:expr,
        ron_ohms: $ron:expr $(,)?
    ) => {
        spiny_hal::dram_hal_common! {
            pac: $pac,
            user_clk_freq_hz: $freq,
            sim: false,
        }

        const MR0: u32 = spiny_hal::dram::ddr3_format_mr0($cl, $wr, true);
        const MR1: u32 = spiny_hal::dram::ddr3_format_mr1($ron, $rtt_nom);
        const MR2: u32 = spiny_hal::dram::ddr3_format_mr2($cwl, $rtt_wr);
        const MR3: u32 = 0;

        #[derive(Clone, Copy)]
        #[repr(u8)]
        enum Phase { P0 = 0, P1 = 1, P2 = 2, P3 = 3 }
        const ALL_PHASES: [Phase; 4] = [Phase::P0, Phase::P1, Phase::P2, Phase::P3];
        const RDPHASE: u32 = 2;
        const WRPHASE: u32 = 3;

        const NUM_LANES: usize = $nbg as usize;
        const NUM_BITSLIPS: usize = 8;
        const NUM_DELAYS: usize = 32;
        const LANE_MASKS: [u32; NUM_LANES] = {
            let mut masks = [0u32; NUM_LANES];
            let mut i = 0;
            while i < NUM_LANES {
                masks[i] = spiny_hal::dram::lane_mask(i as u32, $nbg as u32);
                i += 1;
            }
            masks
        };

        const LFSR_TAPS: u32 = 0x80200003;
        const LFSR_SEEDS: [u32; 3] = [42, 84, 36];
        const MAX_ERRORS: i32 = 96;

        fn write_phase_wrdata(sdram: &$pac::Sdram, phase: Phase, data: u32) {
            match phase {
                Phase::P0 => sdram.dfii_pi0_wrdata().write(|w| unsafe { w.bits(data) }),
                Phase::P1 => sdram.dfii_pi1_wrdata().write(|w| unsafe { w.bits(data) }),
                Phase::P2 => sdram.dfii_pi2_wrdata().write(|w| unsafe { w.bits(data) }),
                Phase::P3 => sdram.dfii_pi3_wrdata().write(|w| unsafe { w.bits(data) }),
            };
        }

        fn read_phase_rddata(sdram: &$pac::Sdram, phase: Phase) -> u32 {
            match phase {
                Phase::P0 => sdram.dfii_pi0_rddata().read().bits(),
                Phase::P1 => sdram.dfii_pi1_rddata().read().bits(),
                Phase::P2 => sdram.dfii_pi2_rddata().read().bits(),
                Phase::P3 => sdram.dfii_pi3_rddata().read().bits(),
            }
        }

        fn lfsr32(prev: u32) -> u32 {
            let lsb = prev & 1;
            let mut next = prev >> 1;
            if lsb != 0 { next ^= LFSR_TAPS; }
            next
        }

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

        fn dfi_write_read_check(sdram: &$pac::Sdram, lane: usize, seed: u32) -> u32 {
            let pattern = generate_pattern(seed);

            // Activate row 0, bank 0 on P0
            sdram.dfii_pi0_address().write(|w| unsafe { w.bits(0) });
            sdram.dfii_pi0_baddress().write(|w| unsafe { w.bits(0) });
            sdram.dfii_pi0_command().write(|w| w.ras().set_bit().cs().set_bit());
            sdram.dfii_pi0_command_issue().write(|w| unsafe { w.bits(1) });
            riscv::asm::delay(DELAY_SHORT);

            // Write pattern to all phases
            for (i, &phase) in ALL_PHASES.iter().enumerate() {
                write_phase_wrdata(sdram, phase, pattern[i]);
            }

            // Issue write on WRPHASE (P3)
            sdram.dfii_pi3_address().write(|w| unsafe { w.bits(0) });
            sdram.dfii_pi3_baddress().write(|w| unsafe { w.bits(0) });
            sdram.dfii_pi3_command().write(|w| {
                w.cas().set_bit().we().set_bit().cs().set_bit().wren().set_bit()
            });
            sdram.dfii_pi3_command_issue().write(|w| unsafe { w.bits(1) });
            riscv::asm::delay(DELAY_SHORT);

            // Issue read on RDPHASE (P2)
            sdram.dfii_pi2_address().write(|w| unsafe { w.bits(0) });
            sdram.dfii_pi2_baddress().write(|w| unsafe { w.bits(0) });
            sdram.dfii_pi2_command().write(|w| {
                w.cas().set_bit().cs().set_bit().rden().set_bit()
            });
            sdram.dfii_pi2_command_issue().write(|w| unsafe { w.bits(1) });
            riscv::asm::delay(DELAY_SHORT);

            // Precharge on P0
            sdram.dfii_pi0_address().write(|w| unsafe { w.bits(0) });
            sdram.dfii_pi0_baddress().write(|w| unsafe { w.bits(0) });
            sdram.dfii_pi0_command().write(|w| {
                w.ras().set_bit().we().set_bit().cs().set_bit()
            });
            sdram.dfii_pi0_command_issue().write(|w| unsafe { w.bits(1) });
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

        fn run_test_pattern(sdram: &$pac::Sdram, lane: usize) -> u32 {
            let mut total = 0u32;
            for &seed in &LFSR_SEEDS {
                total += dfi_write_read_check(sdram, lane, seed);
            }
            total
        }

        fn scan_lane(phy: &$pac::Ddrphy, sdram: &$pac::Sdram, lane: usize) -> i32 {
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

        fn center_lane(phy: &$pac::Ddrphy, sdram: &$pac::Sdram, lane: usize) {
            phy.rdly_dq_rst().write(|w| unsafe { w.bits(1) });

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

            if delay_min < 0 { return; }

            let mut delay_max = delay_min;
            for tap in (delay_min + 2)..(NUM_DELAYS as i32) {
                let errors = run_test_pattern(sdram, lane);
                let working = errors == 0;
                if working { delay_max = tap; } else { break; }
                phy.rdly_dq_inc().write(|w| unsafe { w.bits(1) });
                riscv::asm::delay(DELAY_100US);
            }

            let mid = (delay_min + delay_max) / 2;
            phy.rdly_dq_rst().write(|w| unsafe { w.bits(1) });
            for _ in 0..mid {
                phy.rdly_dq_inc().write(|w| unsafe { w.bits(1) });
            }
        }

        fn phy_init(phy: &$pac::Ddrphy) {
            phy.rdphase().write(|w| unsafe { w.bits(RDPHASE) });
            phy.wrphase().write(|w| unsafe { w.bits(WRPHASE) });
            for lane in 0..NUM_LANES {
                phy.dly_sel().write(|w| unsafe { w.bits(1 << lane) });
                phy.rdly_dq_rst().write(|w| unsafe { w.bits(1) });
                phy.rdly_dq_bitslip_rst().write(|w| unsafe { w.bits(1) });
            }
            phy.rst().write(|w| unsafe { w.bits(1) });
            riscv::asm::delay(DELAY_1MS);
            phy.rst().write(|w| unsafe { w.bits(0) });
            riscv::asm::delay(DELAY_1MS);
        }

        fn init_sequence(sdram: &$pac::Sdram) {
            // Bring CKE high
            sdram.dfii_pi0_address().write(|w| unsafe { w.bits(0) });
            sdram.dfii_pi0_baddress().write(|w| unsafe { w.bits(0) });
            dfii_sw_control(sdram);
            riscv::asm::delay(DELAY_20MS);

            load_mr(sdram, 2, MR2);
            load_mr(sdram, 3, MR3);
            load_mr(sdram, 1, MR1);
            load_mr(sdram, 0, MR0);
            riscv::asm::delay(DELAY_1MS);

            // ZQ Calibration (long)
            sdram.dfii_pi0_address().write(|w| unsafe { w.bits(0x400) });
            sdram.dfii_pi0_baddress().write(|w| unsafe { w.bits(0) });
            command_p0(sdram, true, true, false, false); // CS + WE
            riscv::asm::delay(DELAY_1MS);
        }

        fn read_leveling(phy: &$pac::Ddrphy, sdram: &$pac::Sdram) {
            for lane in 0..NUM_LANES {
                phy.dly_sel().write(|w| unsafe { w.bits(1u32 << lane) });

                let mut best_score = i32::MIN;
                let mut best_bitslip = 0usize;

                phy.rdly_dq_bitslip_rst().write(|w| unsafe { w.bits(1) });
                for bitslip in 0..NUM_BITSLIPS {
                    let score = scan_lane(phy, sdram, lane);
                    if score > best_score {
                        best_score = score;
                        best_bitslip = bitslip;
                    }
                    if bitslip < NUM_BITSLIPS - 1 {
                        phy.rdly_dq_bitslip().write(|w| unsafe { w.bits(1) });
                    }
                }

                phy.rdly_dq_bitslip_rst().write(|w| unsafe { w.bits(1) });
                for _ in 0..best_bitslip {
                    phy.rdly_dq_bitslip().write(|w| unsafe { w.bits(1) });
                }

                center_lane(phy, sdram, lane);
            }
        }

        /// Initialize DDR3 SDRAM: MR loads, PHY init, and read leveling.
        pub fn init(
            ddrctrl: &$pac::Ddrctrl,
            ddrphy: &$pac::Ddrphy,
            sdram: &$pac::Sdram,
        ) {
            ddrctrl.init_done().write(|w| unsafe { w.bits(0) });
            ddrctrl.init_error().write(|w| unsafe { w.bits(0) });
            dfii_sw_control(sdram);
            phy_init(ddrphy);
            init_sequence(sdram);
            read_leveling(ddrphy, sdram);
            dfii_hw_control(sdram);
            ddrctrl.init_done().write(|w| w.init_done().set_bit());
        }
    };

    // ---- Sim arm: MR loads only, no PHY ----
    (
        pac: $pac:ident,
        cl: $cl:expr,
        cwl: $cwl:expr,
        wr: $wr:expr,
        nphases: $nphases:expr,
        num_byte_groups: $nbg:expr,
        user_clk_freq_hz: $freq:expr,
        sim: true,
        rtt_nom_ohms: $rtt_nom:expr,
        rtt_wr_ohms: $rtt_wr:expr,
        ron_ohms: $ron:expr $(,)?
    ) => {
        spiny_hal::dram_hal_common! {
            pac: $pac,
            user_clk_freq_hz: $freq,
            sim: true,
        }

        const MR0: u32 = spiny_hal::dram::ddr3_format_mr0($cl, $wr, true);
        const MR1: u32 = spiny_hal::dram::ddr3_format_mr1($ron, $rtt_nom);
        const MR2: u32 = spiny_hal::dram::ddr3_format_mr2($cwl, $rtt_wr);
        const MR3: u32 = 0;

        fn init_sequence(sdram: &$pac::Sdram) {
            // Bring CKE high
            sdram.dfii_pi0_address().write(|w| unsafe { w.bits(0) });
            sdram.dfii_pi0_baddress().write(|w| unsafe { w.bits(0) });
            dfii_sw_control(sdram);
            riscv::asm::delay(DELAY_20MS);

            load_mr(sdram, 2, MR2);
            load_mr(sdram, 3, MR3);
            load_mr(sdram, 1, MR1);
            load_mr(sdram, 0, MR0);
            riscv::asm::delay(DELAY_1MS);

            // ZQ Calibration (long)
            sdram.dfii_pi0_address().write(|w| unsafe { w.bits(0x400) });
            sdram.dfii_pi0_baddress().write(|w| unsafe { w.bits(0) });
            command_p0(sdram, true, true, false, false); // CS + WE
            riscv::asm::delay(DELAY_1MS);
        }

        /// Initialize DDR3 SDRAM in simulation mode: MR loads only.
        pub fn init(ddrctrl: &$pac::Ddrctrl, sdram: &$pac::Sdram) {
            ddrctrl.init_done().write(|w| unsafe { w.bits(0) });
            ddrctrl.init_error().write(|w| unsafe { w.bits(0) });
            dfii_sw_control(sdram);
            init_sequence(sdram);
            dfii_hw_control(sdram);
            ddrctrl.init_done().write(|w| w.init_done().set_bit());
        }
    };
}

// ---- DDR2 HAL Macro ----

/// Generates a DDR2 SDRAM init module with computed mode register values,
/// PHY initialization, and read leveling.
///
/// # Parameters
///
/// | Parameter | Description |
/// |-----------|-------------|
/// | `pac` | PAC crate name (e.g., `ddrtest_pac`) |
/// | `cl` | CAS latency (computed by Scala) |
/// | `nphases` | Number of PHY phases |
/// | `num_byte_groups` | Number of DQ byte groups (lanes) |
/// | `user_clk_freq_hz` | User clock frequency in Hz |
/// | `sim` | `true` for simulation (short delays, no PHY init) |
#[macro_export]
macro_rules! ddr2_hal {
    // ---- Non-sim arm: full PHY init + read leveling ----
    (
        pac: $pac:ident,
        cl: $cl:expr,
        nphases: $nphases:expr,
        num_byte_groups: $nbg:expr,
        user_clk_freq_hz: $freq:expr,
        sim: false $(,)?
    ) => {
        spiny_hal::dram_hal_common! {
            pac: $pac,
            user_clk_freq_hz: $freq,
            sim: false,
        }

        const MR: u32 = spiny_hal::dram::ddr2_format_mr($cl);
        const EMR: u32 = spiny_hal::dram::ddr2_format_emr();
        const EMR2: u32 = 0;
        const EMR3: u32 = 0;

        #[derive(Clone, Copy)]
        #[repr(u8)]
        enum Phase { P0 = 0, P1 = 1 }
        const ALL_PHASES: [Phase; 2] = [Phase::P0, Phase::P1];
        const RDPHASE: u32 = 0;
        const WRPHASE: u32 = 1;

        const NUM_LANES: usize = $nbg as usize;
        const NUM_BITSLIPS: usize = 8;
        const NUM_DELAYS: usize = 32;
        const LANE_MASKS: [u32; NUM_LANES] = {
            let mut masks = [0u32; NUM_LANES];
            let mut i = 0;
            while i < NUM_LANES {
                masks[i] = spiny_hal::dram::lane_mask(i as u32, $nbg as u32);
                i += 1;
            }
            masks
        };

        const LFSR_TAPS: u32 = 0x80200003;
        const LFSR_SEEDS: [u32; 3] = [42, 84, 36];
        const MAX_ERRORS: i32 = 96;

        fn write_phase_wrdata(sdram: &$pac::Sdram, phase: Phase, data: u32) {
            match phase {
                Phase::P0 => sdram.dfii_pi0_wrdata().write(|w| unsafe { w.bits(data) }),
                Phase::P1 => sdram.dfii_pi1_wrdata().write(|w| unsafe { w.bits(data) }),
            };
        }

        fn read_phase_rddata(sdram: &$pac::Sdram, phase: Phase) -> u32 {
            match phase {
                Phase::P0 => sdram.dfii_pi0_rddata().read().bits(),
                Phase::P1 => sdram.dfii_pi1_rddata().read().bits(),
            }
        }

        fn lfsr32(prev: u32) -> u32 {
            let lsb = prev & 1;
            let mut next = prev >> 1;
            if lsb != 0 { next ^= LFSR_TAPS; }
            next
        }

        fn generate_pattern(seed: u32) -> [u32; 2] {
            let mut pattern = [0u32; 2];
            let mut prv = seed;
            for p in 0..2 {
                let mut word = 0u32;
                for bit in 0..32 {
                    prv = lfsr32(prv);
                    word |= (prv & 1) << bit;
                }
                pattern[p] = word;
            }
            pattern
        }

        fn dfi_write_read_check(sdram: &$pac::Sdram, lane: usize, seed: u32) -> u32 {
            let pattern = generate_pattern(seed);

            // Activate row 0, bank 0 on P0
            sdram.dfii_pi0_address().write(|w| unsafe { w.bits(0) });
            sdram.dfii_pi0_baddress().write(|w| unsafe { w.bits(0) });
            sdram.dfii_pi0_command().write(|w| w.ras().set_bit().cs().set_bit());
            sdram.dfii_pi0_command_issue().write(|w| unsafe { w.bits(1) });
            riscv::asm::delay(DELAY_SHORT);

            // Write pattern to all phases
            for (i, &phase) in ALL_PHASES.iter().enumerate() {
                write_phase_wrdata(sdram, phase, pattern[i]);
            }

            // Issue write on WRPHASE (P1)
            sdram.dfii_pi1_address().write(|w| unsafe { w.bits(0) });
            sdram.dfii_pi1_baddress().write(|w| unsafe { w.bits(0) });
            sdram.dfii_pi1_command().write(|w| {
                w.cas().set_bit().we().set_bit().cs().set_bit().wren().set_bit()
            });
            sdram.dfii_pi1_command_issue().write(|w| unsafe { w.bits(1) });
            riscv::asm::delay(DELAY_SHORT);

            // Issue read on RDPHASE (P0)
            sdram.dfii_pi0_address().write(|w| unsafe { w.bits(0) });
            sdram.dfii_pi0_baddress().write(|w| unsafe { w.bits(0) });
            sdram.dfii_pi0_command().write(|w| {
                w.cas().set_bit().cs().set_bit().rden().set_bit()
            });
            sdram.dfii_pi0_command_issue().write(|w| unsafe { w.bits(1) });
            riscv::asm::delay(DELAY_SHORT);

            // Precharge on P0
            sdram.dfii_pi0_address().write(|w| unsafe { w.bits(0) });
            sdram.dfii_pi0_baddress().write(|w| unsafe { w.bits(0) });
            sdram.dfii_pi0_command().write(|w| {
                w.ras().set_bit().we().set_bit().cs().set_bit()
            });
            sdram.dfii_pi0_command_issue().write(|w| unsafe { w.bits(1) });
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

        fn run_test_pattern(sdram: &$pac::Sdram, lane: usize) -> u32 {
            let mut total = 0u32;
            for &seed in &LFSR_SEEDS {
                total += dfi_write_read_check(sdram, lane, seed);
            }
            total
        }

        fn scan_lane(phy: &$pac::Ddrphy, sdram: &$pac::Sdram, lane: usize) -> i32 {
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

        fn center_lane(phy: &$pac::Ddrphy, sdram: &$pac::Sdram, lane: usize) {
            phy.rdly_dq_rst().write(|w| unsafe { w.bits(1) });

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

            if delay_min < 0 { return; }

            let mut delay_max = delay_min;
            for tap in (delay_min + 2)..(NUM_DELAYS as i32) {
                let errors = run_test_pattern(sdram, lane);
                let working = errors == 0;
                if working { delay_max = tap; } else { break; }
                phy.rdly_dq_inc().write(|w| unsafe { w.bits(1) });
                riscv::asm::delay(DELAY_100US);
            }

            let mid = (delay_min + delay_max) / 2;
            phy.rdly_dq_rst().write(|w| unsafe { w.bits(1) });
            for _ in 0..mid {
                phy.rdly_dq_inc().write(|w| unsafe { w.bits(1) });
            }
        }

        fn phy_init(phy: &$pac::Ddrphy) {
            phy.rdphase().write(|w| unsafe { w.bits(RDPHASE) });
            phy.wrphase().write(|w| unsafe { w.bits(WRPHASE) });
            for lane in 0..NUM_LANES {
                phy.dly_sel().write(|w| unsafe { w.bits(1 << lane) });
                phy.rdly_dq_rst().write(|w| unsafe { w.bits(1) });
                phy.rdly_dq_bitslip_rst().write(|w| unsafe { w.bits(1) });
            }
            phy.rst().write(|w| unsafe { w.bits(1) });
            riscv::asm::delay(DELAY_1MS);
            phy.rst().write(|w| unsafe { w.bits(0) });
            riscv::asm::delay(DELAY_1MS);
        }

        fn init_sequence(sdram: &$pac::Sdram) {
            // Bring CKE high
            sdram.dfii_pi0_address().write(|w| unsafe { w.bits(0) });
            sdram.dfii_pi0_baddress().write(|w| unsafe { w.bits(0) });
            dfii_sw_control(sdram);
            riscv::asm::delay(DELAY_20MS);

            // Precharge All
            sdram.dfii_pi0_address().write(|w| unsafe { w.bits(0x400) });
            sdram.dfii_pi0_baddress().write(|w| unsafe { w.bits(0) });
            command_p0(sdram, true, true, false, true); // CS + WE + RAS
            riscv::asm::delay(DELAY_SHORT);

            load_mr(sdram, 3, EMR3);
            load_mr(sdram, 2, EMR2);
            load_mr(sdram, 1, EMR);

            // MR with DLL reset (bit 8)
            let mr_dll_reset = spiny_hal::dram::ddr2_format_mr($cl) | (1 << 8);
            load_mr(sdram, 0, mr_dll_reset);
            riscv::asm::delay(DELAY_1MS);

            // Precharge All
            sdram.dfii_pi0_address().write(|w| unsafe { w.bits(0x400) });
            sdram.dfii_pi0_baddress().write(|w| unsafe { w.bits(0) });
            command_p0(sdram, true, true, false, true); // CS + WE + RAS
            riscv::asm::delay(DELAY_SHORT);

            // 2x Auto Refresh
            command_p0(sdram, true, false, true, true); // CS + CAS + RAS
            riscv::asm::delay(DELAY_SHORT);
            command_p0(sdram, true, false, true, true); // CS + CAS + RAS
            riscv::asm::delay(DELAY_SHORT);

            // MR without DLL reset
            load_mr(sdram, 0, MR);
            riscv::asm::delay(DELAY_1MS);

            // EMR OCD default calibration
            let emr_ocd = EMR | (0x7 << 7); // OCD = default (111)
            load_mr(sdram, 1, emr_ocd);
            riscv::asm::delay(DELAY_SHORT);

            // EMR OCD exit
            load_mr(sdram, 1, EMR);
        }

        fn read_leveling(phy: &$pac::Ddrphy, sdram: &$pac::Sdram) {
            for lane in 0..NUM_LANES {
                phy.dly_sel().write(|w| unsafe { w.bits(1u32 << lane) });

                let mut best_score = i32::MIN;
                let mut best_bitslip = 0usize;

                phy.rdly_dq_bitslip_rst().write(|w| unsafe { w.bits(1) });
                for bitslip in 0..NUM_BITSLIPS {
                    let score = scan_lane(phy, sdram, lane);
                    if score > best_score {
                        best_score = score;
                        best_bitslip = bitslip;
                    }
                    if bitslip < NUM_BITSLIPS - 1 {
                        phy.rdly_dq_bitslip().write(|w| unsafe { w.bits(1) });
                    }
                }

                phy.rdly_dq_bitslip_rst().write(|w| unsafe { w.bits(1) });
                for _ in 0..best_bitslip {
                    phy.rdly_dq_bitslip().write(|w| unsafe { w.bits(1) });
                }

                center_lane(phy, sdram, lane);
            }
        }

        /// Initialize DDR2 SDRAM: MR loads, PHY init, and read leveling.
        pub fn init(
            ddrctrl: &$pac::Ddrctrl,
            ddrphy: &$pac::Ddrphy,
            sdram: &$pac::Sdram,
        ) {
            ddrctrl.init_done().write(|w| unsafe { w.bits(0) });
            ddrctrl.init_error().write(|w| unsafe { w.bits(0) });
            dfii_sw_control(sdram);
            phy_init(ddrphy);
            init_sequence(sdram);
            read_leveling(ddrphy, sdram);
            dfii_hw_control(sdram);
            ddrctrl.init_done().write(|w| w.init_done().set_bit());
        }
    };

    // ---- Sim arm: MR loads only, no PHY ----
    (
        pac: $pac:ident,
        cl: $cl:expr,
        nphases: $nphases:expr,
        num_byte_groups: $nbg:expr,
        user_clk_freq_hz: $freq:expr,
        sim: true $(,)?
    ) => {
        spiny_hal::dram_hal_common! {
            pac: $pac,
            user_clk_freq_hz: $freq,
            sim: true,
        }

        const MR: u32 = spiny_hal::dram::ddr2_format_mr($cl);
        const EMR: u32 = spiny_hal::dram::ddr2_format_emr();
        const EMR2: u32 = 0;
        const EMR3: u32 = 0;

        fn init_sequence(sdram: &$pac::Sdram) {
            // Bring CKE high
            sdram.dfii_pi0_address().write(|w| unsafe { w.bits(0) });
            sdram.dfii_pi0_baddress().write(|w| unsafe { w.bits(0) });
            dfii_sw_control(sdram);
            riscv::asm::delay(DELAY_20MS);

            // Precharge All
            sdram.dfii_pi0_address().write(|w| unsafe { w.bits(0x400) });
            sdram.dfii_pi0_baddress().write(|w| unsafe { w.bits(0) });
            command_p0(sdram, true, true, false, true); // CS + WE + RAS
            riscv::asm::delay(DELAY_SHORT);

            load_mr(sdram, 3, EMR3);
            load_mr(sdram, 2, EMR2);
            load_mr(sdram, 1, EMR);

            // MR with DLL reset (bit 8)
            let mr_dll_reset = spiny_hal::dram::ddr2_format_mr($cl) | (1 << 8);
            load_mr(sdram, 0, mr_dll_reset);
            riscv::asm::delay(DELAY_1MS);

            // Precharge All
            sdram.dfii_pi0_address().write(|w| unsafe { w.bits(0x400) });
            sdram.dfii_pi0_baddress().write(|w| unsafe { w.bits(0) });
            command_p0(sdram, true, true, false, true); // CS + WE + RAS
            riscv::asm::delay(DELAY_SHORT);

            // 2x Auto Refresh
            command_p0(sdram, true, false, true, true); // CS + CAS + RAS
            riscv::asm::delay(DELAY_SHORT);
            command_p0(sdram, true, false, true, true); // CS + CAS + RAS
            riscv::asm::delay(DELAY_SHORT);

            // MR without DLL reset
            load_mr(sdram, 0, MR);
            riscv::asm::delay(DELAY_1MS);

            // EMR OCD default calibration
            let emr_ocd = EMR | (0x7 << 7); // OCD = default (111)
            load_mr(sdram, 1, emr_ocd);
            riscv::asm::delay(DELAY_SHORT);

            // EMR OCD exit
            load_mr(sdram, 1, EMR);
        }

        /// Initialize DDR2 SDRAM in simulation mode: MR loads only.
        pub fn init(ddrctrl: &$pac::Ddrctrl, sdram: &$pac::Sdram) {
            ddrctrl.init_done().write(|w| unsafe { w.bits(0) });
            ddrctrl.init_error().write(|w| unsafe { w.bits(0) });
            dfii_sw_control(sdram);
            init_sequence(sdram);
            dfii_hw_control(sdram);
            ddrctrl.init_done().write(|w| w.init_done().set_bit());
        }
    };
}
