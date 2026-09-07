#![no_main]
#![no_std]

mod aux;
mod edid;
mod train;

use defmt_rtt as _;
use embassy_executor::Spawner;
use embassy_time::Timer;
use panic_halt as _;

fn init() -> dptest_pac::Peripherals {
    let peripherals = critical_section::with(|cs| {
        let peripherals = dptest_pac::Peripherals::take().unwrap();
        dptest_hal::timer::init(cs);
        peripherals
    });

    unsafe {
        riscv::interrupt::enable();
    }

    peripherals
}

/// Reads the EDID over I2C over AUX and prints what the sink says it is
fn read_edid(dp: &dptest_pac::DisplayPort) {
    let mut raw = [0u8; aux::EDID_BLOCK_LEN];
    if let Err(error) = aux::edid_read(dp, &mut raw) {
        defmt::println!("EDID read failed: {}", error);
        return;
    }

    let info = match edid::parse(&raw) {
        Ok(info) => info,
        Err(error) => {
            defmt::println!("EDID did not parse: {}", error);
            defmt::println!("  first bytes: {=[u8]:#04x}", raw[..16]);
            return;
        }
    };

    defmt::println!("  manufacturer    {=str}", info.manufacturer_str());
    defmt::println!("  display name    {=str}", info.name_str());
    defmt::println!("  product code    {=u16:#06x}", info.product_code);
    defmt::println!("  serial          {=u32:#010x}", info.serial);
    defmt::println!(
        "  EDID version    {=u8}.{=u8}",
        info.version.0,
        info.version.1
    );
    defmt::println!("  extensions      {=u8}", info.extensions);
}

/// Waits for HPD, then reads the first 16 bytes of DPCD
async fn read_dpcd(dp: &dptest_pac::DisplayPort) {
    defmt::println!("waiting for a sink...");
    while !dp.hpd_status().read().connected().bit_is_set() {
        Timer::after_millis(10).await;
    }
    defmt::println!("HPD asserted, letting the sink settle");
    Timer::after_millis(200).await;

    let mut reply = [0u8; 17];
    match aux::dpcd_read(dp, 0x00000, 16, &mut reply) {
        Ok(dpcd) => {
            defmt::println!("DPCD 0x00000: {=[u8]:#04x}", dpcd);
            defmt::println!("  DPCD_REV        {=u8:#04x}", dpcd[0]);
            defmt::println!("  MAX_LINK_RATE   {=u8:#04x}", dpcd[1]);
            defmt::println!("  MAX_LANE_COUNT  {=u8:#04x}", dpcd[2] & 0x1f);
        }
        Err(error) => {
            defmt::println!("DPCD read failed: {}", error);
            // the receiver is armed the moment the transmitter lets go, so
            // junk on the line shows up here as unexpected or overrun
            let raw = dp.aux_int_raw().read();
            defmt::println!(
                "  AUX rxUnexpected {}, rxOverrun {}, requestDropped {}, result {=u8}",
                raw.rx_unexpected_raw().bit_is_set(),
                raw.rx_overrun_raw().bit_is_set(),
                raw.request_dropped_raw().bit_is_set(),
                dp.status().read().result().bits()
            );
        }
    }

    read_edid(dp);
}

#[embassy_executor::main]
async fn main(_spawner: Spawner) {
    let peripherals = init();
    let dp = &peripherals.display_port;
    let gpio = &peripherals.gpio;

    defmt::println!("dptest starting");

    // stop guessing what the AUX link is configured with and read it back
    let config = dp.config().read();
    defmt::println!(
        "AUX config: replyTimeout {=u32} clocks, maxRetries {=u8}",
        config.reply_timeout().bits(),
        config.max_retries().bits()
    );
    // The register keeps its value across a firmware reload, so a previous
    // build that wrote nonsense into it survives until the fabric is
    // reconfigured. Nothing works with a zero timeout, so say so.
    if config.reply_timeout().bits() == 0 {
        defmt::println!(
            "AUX reply timeout is zero, every transaction will time out. \
             Reload the bitstream or reset the board to restore the default."
        );
    }

    loop {
        read_dpcd(dp).await;

        match train::clock_recovery(dp).await {
            Ok(true) => defmt::println!("sink locked to training pattern 1"),
            Ok(false) => defmt::println!("sink did not lock"),
            Err(error) => defmt::println!("training pattern 1 failed: {}", error),
        }

        // heartbeat, and hold here until the sink goes away
        let mut leds: u8 = 1;
        while dp.hpd_status().read().connected().bit_is_set() {
            gpio.leds_write()
                .write(|w| unsafe { w.value().bits(leds as _) });
            leds = leds.rotate_left(1);
            Timer::after_millis(100).await;
        }
        defmt::println!("sink disconnected");
        gpio.leds_write().write(|w| unsafe { w.value().bits(0) });
    }
}
