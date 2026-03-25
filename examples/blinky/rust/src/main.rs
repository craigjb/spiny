#![no_main]
#![no_std]

use embassy_executor::Spawner;
use embassy_time::Timer;
use panic_halt as _;

fn init() -> blinky_pac::Peripherals {
    let peripherals = critical_section::with(|cs| {
        let peripherals = blinky_pac::Peripherals::take().unwrap();
        blinky_hal::timer::init(cs);
        peripherals
    });

    unsafe {
        riscv::interrupt::enable();
    }

    peripherals
}

#[embassy_executor::main]
async fn main(_spawner: Spawner) {
    let peripherals = init();
    let gpio = &peripherals.gpio;

    let mut led: u8 = 0x80;
    loop {
        gpio.write().write(|w| unsafe { w.value().bits(led) });
        Timer::after_millis(200).await;
        led = if led == 1 { 0x80 } else { led >> 1 };
    }
}
