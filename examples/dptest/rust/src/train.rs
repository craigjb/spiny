//! Enough of DisplayPort link training to ask the sink whether it locked

use crate::aux::{self, AuxError};
use dptest_pac::DisplayPort;
use embassy_time::Timer;

const LINK_BW_SET: u32 = 0x00100;
const LANE_COUNT_SET: u32 = 0x00101;
const TRAINING_PATTERN_SET: u32 = 0x00102;
/// What the source is actually transmitting, which the sink reads to decide
/// what to ask for next. Without it the adjust request never changes.
const TRAINING_LANE0_SET: u32 = 0x00103;
const DOWNSPREAD_CTRL: u32 = 0x00107;
const MAIN_LINK_CHANNEL_CODING_SET: u32 = 0x00108;
/// 0x202..0x207 read together: lane status, align, sink status, adjust requests
const STATUS_BLOCK: u32 = 0x00202;

const SET_POWER: u32 = 0x00600;

/// 2.7 Gbps a lane, the rate the PHY's PLL is built for
const LINK_BW_HBR: u8 = 0x0a;
/// Training pattern 1, with the scrambler off as the spec requires for it
const TPS1_SCRAMBLING_OFF: u8 = 0x21;
const TRAINING_OFF: u8 = 0x00;
/// Normal operation, as opposed to the D3 the sink may be sitting in
const POWER_D0: u8 = 0x01;
/// No spread spectrum, which this transmitter has no way to produce
const DOWNSPREAD_NONE: u8 = 0x00;
/// 8b/10b, the only coding this link does
const CODING_8B10B: u8 = 0x01;
/// LANE0_1_STATUS bit 0, the sink has recovered the clock on lane 0
const CR_DONE: u8 = 0x01;
/// LANE0_1_STATUS bit 1, lane 0 is equalized, which needs TPS2 or better
const EQ_DONE: u8 = 0x02;
/// LANE0_1_STATUS bit 2, the sink has found the 8b/10b symbol boundaries
const SYMBOL_LOCKED: u8 = 0x04;
/// SINK_STATUS bit 0, receive port 0 is in sync with the stream
const RX_IN_SYNC: u8 = 0x01;

/// The sink is given 100 us minimum to lock, so read back well after that
const LOCK_WAIT_MS: u64 = 10;
/// Each step of the sequence takes microseconds, so this only avoids hanging
const READY_POLLS: u32 = 20;
/// Restarts of the bring-up sequence before giving up on it
const READY_RESTARTS: u32 = 3;

/// The link retries on its own, so this is only for a sink that is slow to
/// accept the first write after the transmitter comes up
const WRITE_ATTEMPTS: u32 = 3;
/// Clock recovery attempts before giving up, the spec allows 10
const CR_ATTEMPTS: u32 = 10;
/// The spec stops after five attempts at one voltage swing
const MAX_TRIES_PER_SWING: u32 = 5;
/// DisplayPort has four swing levels, so this is the last one
const MAX_SWING: u8 = 3;
/// Long enough for a sink to finish waking or reconfiguring before we look
const READBACK_WAIT_MS: u64 = 20;

/// TRAINING_LANEx_SET: swing, pre-emphasis, and the two "no more left" bits
fn training_lane_set(swing: u8, emphasis: u8) -> u8 {
    let max_swing = if swing >= 3 { 0x04 } else { 0 };
    let max_emphasis = if emphasis >= 3 { 0x20 } else { 0 };
    (swing & 0x3) | max_swing | ((emphasis & 0x3) << 3) | max_emphasis
}

/// One DPCD byte, retried, and checked by reading it back
///
/// This sink answers writes that only store a value but goes quiet on the
/// ones that make it act, so a timeout is not proof the write was lost.
async fn write_dpcd(
    dp: &DisplayPort,
    name: &str,
    address: u32,
    value: u8,
) -> Result<(), AuxError> {
    let mut last = None;
    for _ in 0..WRITE_ATTEMPTS {
        match aux::dpcd_write(dp, address, &[value]) {
            Ok(()) => return Ok(()),
            Err(error) => last = Some(error),
        }
    }
    let error = last.unwrap();
    defmt::println!(
        "DPCD write to {} timed out, HPD {}, reading it back",
        name,
        dp.hpd_status().read().connected().bit_is_set()
    );

    Timer::after_millis(READBACK_WAIT_MS).await;
    let mut back = [0u8; 2];
    match aux::dpcd_read(dp, address, 1, &mut back) {
        Ok(seen) if seen[0] == value => {
            defmt::println!("  {} reads back {=u8:#04x}, taking it", name, seen[0]);
            Ok(())
        }
        Ok(seen) => {
            defmt::println!("  {} reads {=u8:#04x}, wanted {=u8:#04x}", name, seen[0], value);
            Err(error)
        }
        Err(read_error) => {
            defmt::println!("  {} read back failed too: {}", name, read_error);
            Err(read_error)
        }
    }
}

/// What the PHY reports about itself, see XilinxGtpPhyTxPorts
fn report_status(dp: &DisplayPort) {
    let s = dp.lane0gtp_status().read();
    defmt::println!(
        "  pll locked {}, refclk lost {}, fbclk lost {}, TXRESETDONE {}",
        s.pll_locked().bit_is_set(),
        s.ref_clk_lost().bit_is_set(),
        s.fb_clk_lost().bit_is_set(),
        s.reset_done().bit_is_set()
    );
    defmt::println!(
        "  TXOUTCLK detected {}, buffer half full {}, buffer error {}, transmitting {}",
        s.out_clk_detected().bit_is_set(),
        s.buffer_half_full().bit_is_set(),
        s.buffer_error().bit_is_set(),
        s.transmitting().bit_is_set()
    );
}

/// Waits for one of the PHY's status bits, returning false if it never sets
async fn wait_status(
    dp: &DisplayPort,
    read: fn(&DisplayPort) -> bool,
    what: &str,
) -> bool {
    for _ in 0..READY_POLLS {
        if read(dp) {
            return true;
        }
        Timer::after_millis(1).await;
    }
    defmt::println!("main link transmitter never reached {}", what);
    report_status(dp);
    false
}

/// The transmit reset sequence of UG482, one step per register write
///
/// The 500 ns hold of AR 43482 is measured from configuration, which is long
/// past by the time this runs.
async fn bring_up(dp: &DisplayPort) -> bool {
    for attempt in 0..READY_RESTARTS {
        if attempt > 0 {
            defmt::println!("bring-up stalled, starting over");
        }
        // everything held, which also clears the TXOUTCLK seen latch
        dp.lane0gtp_control().write(|w| {
            w.pll_reset()
                .set_bit()
                .gt_tx_reset()
                .set_bit()
                .usr_clk_ready()
                .clear_bit()
        });
        dp.main_link_control().write(|w| w.enable().set_bit());

        // let the PLL go and wait for it to lock
        dp.lane0gtp_control().modify(|_, w| w.pll_reset().clear_bit());
        if !wait_status(dp, |d| d.lane0gtp_status().read().pll_locked().bit_is_set(), "PLL lock")
            .await
        {
            continue;
        }

        // then the transmitter, whose PMA starts TXOUTCLK
        dp.lane0gtp_control().modify(|_, w| w.gt_tx_reset().clear_bit());
        if !wait_status(dp, |d| d.lane0gtp_status().read().out_clk_detected().bit_is_set(), "TXOUTCLK").await {
            continue;
        }

        // TXUSERRDY only once TXUSRCLK is running, then the reset completes
        dp.lane0gtp_control().modify(|_, w| w.usr_clk_ready().set_bit());
        if !wait_status(dp, |d| d.lane0gtp_status().read().reset_done().bit_is_set(), "TXRESETDONE").await {
            continue;
        }

        defmt::println!("main link transmitter ready");
        return true;
    }
    false
}

/// Every byte of the sink's status block, 0x202 through 0x207
fn dump_status(s: &[u8]) {
    defmt::println!(
        "  LANE0_1 {=u8:#04x} LANE2_3 {=u8:#04x} ALIGN {=u8:#04x} \
         SINK_STATUS {=u8:#04x} ADJUST {=u8:#04x} {=u8:#04x}",
        s[0], s[1], s[2], s[3], s[4], s[5]
    );
    defmt::println!(
        "  CR_DONE {}, EQ_DONE {}, SYMBOL_LOCKED {}, in sync {}, \
         wants swing {=u8} emphasis {=u8}",
        s[0] & CR_DONE != 0,
        s[0] & EQ_DONE != 0,
        s[0] & SYMBOL_LOCKED != 0,
        s[3] & RX_IN_SYNC != 0,
        s[4] & 0x3,
        (s[4] >> 2) & 0x3
    );
}

/// CR_DONE as the sink reports it right now
fn cr_done(dp: &DisplayPort) -> bool {
    let mut reply = [0u8; 8];
    match aux::dpcd_read(dp, STATUS_BLOCK, 6, &mut reply) {
        Ok(status) => status[0] & CR_DONE != 0,
        Err(_) => false,
    }
}

/// Drives training pattern 1 on lane 0 and reports whether the sink locked
pub async fn clock_recovery(dp: &DisplayPort) -> Result<bool, AuxError> {
    let result = attempt_clock_recovery(dp).await;

    // Whatever happened, do not walk away leaving the sink in training with
    // the scrambler off. An early return used to skip this, so a failed run
    // left the sink stuck until it was unplugged.
    let _ = aux::dpcd_write(dp, TRAINING_PATTERN_SET, &[TRAINING_OFF]);
    dp.main_link_control().write(|w| w.enable().clear_bit());

    result
}

async fn attempt_clock_recovery(dp: &DisplayPort) -> Result<bool, AuxError> {
    defmt::println!("enabling main link");
    // a sink parked in D3 will not train, and this has to happen while AUX
    // is known good, before anything else
    write_dpcd(dp, "SET_POWER", SET_POWER, POWER_D0).await?;

    dp.main_link_control().write(|w| w.enable().set_bit());
    if !bring_up(dp).await {
        return Ok(false);
    }

    write_dpcd(dp, "LINK_BW_SET", LINK_BW_SET, LINK_BW_HBR).await?;
    write_dpcd(dp, "LANE_COUNT_SET", LANE_COUNT_SET, 1).await?;
    write_dpcd(dp, "DOWNSPREAD_CTRL", DOWNSPREAD_CTRL, DOWNSPREAD_NONE).await?;
    write_dpcd(
        dp,
        "MAIN_LINK_CHANNEL_CODING_SET",
        MAIN_LINK_CHANNEL_CODING_SET,
        CODING_8B10B,
    )
    .await?;

    // This sink holds its last verdict until the cable is unplugged, so a
    // bit that is already set says nothing about the run about to start.
    if cr_done(dp) {
        defmt::println!(
            "CR_DONE is set before training starts, left over from an earlier \
             run. Unplug the sink to clear it, or treat a lock below as stale."
        );
    }

    // the spec has the pattern on the wire before the sink is told to look
    dp.main_link_control()
        .write(|w| w.enable().set_bit().pattern().set_bit());
    write_dpcd(dp, "TRAINING_PATTERN_SET", TRAINING_PATTERN_SET, TPS1_SCRAMBLING_OFF)
        .await?;

    // the flags lag the pattern write by a few transmit clocks, so let them
    // settle rather than reading a half updated state
    Timer::after_millis(1).await;
    report_status(dp);

    // The clock recovery loop of the spec: try, read what the sink wants,
    // apply it, try again. Level 0 is only ever a starting point.
    let mut locked = false;
    let mut swing = 0u8;
    let mut emphasis = 0u8;
    let mut tries_at_swing = 0u32;
    for attempt in 0..CR_ATTEMPTS {
        tries_at_swing += 1;
        dp.lane0drive().write(|w| unsafe {
            w.swing().bits(swing).pre_emphasis().bits(emphasis)
        });
        // tell the sink what is being transmitted, or it keeps asking for
        // the same adjustment forever
        write_dpcd(dp, "TRAINING_LANE0_SET", TRAINING_LANE0_SET,
            training_lane_set(swing, emphasis)).await?;
        Timer::after_millis(LOCK_WAIT_MS).await;

        let mut reply = [0u8; 8];
        let status = match aux::dpcd_read(dp, STATUS_BLOCK, 6, &mut reply) {
            Ok(status) => status,
            Err(error) => {
                defmt::println!("status read failed: {}", error);
                break;
            }
        };
        locked = status[0] & CR_DONE != 0;
        let wants_swing = status[4] & 0x3;
        let wants_emphasis = (status[4] >> 2) & 0x3;
        defmt::println!(
            "attempt {=u32}: sent swing {=u8} emphasis {=u8}",
            attempt,
            swing,
            emphasis
        );
        dump_status(status);
        if locked {
            // clock recovery is done, and equalization starts from whatever
            // the sink is asking for by then
            swing = wants_swing;
            emphasis = wants_emphasis;
            break;
        }

        // the spec gives up after five tries at one voltage swing
        if tries_at_swing >= MAX_TRIES_PER_SWING {
            defmt::println!(
                "  {=u32} attempts at swing {=u8} without lock, giving up",
                tries_at_swing,
                swing
            );
            break;
        }
        // and once the swing cannot go any higher
        if swing >= MAX_SWING {
            defmt::println!("  already at maximum swing without lock, giving up");
            break;
        }

        if wants_swing != swing {
            tries_at_swing = 0;
        }
        swing = wants_swing;
        emphasis = wants_emphasis;
    }

    if locked {
        // equalization picks up from here, once there is an equalization
        defmt::println!(
            "clock recovery done, sink wants swing {=u8} emphasis {=u8} for equalization",
            swing,
            emphasis
        );
    }

    Ok(locked)
}
