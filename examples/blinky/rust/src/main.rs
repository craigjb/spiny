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

    let mut led_mask: u8 = 0x80;
    loop {
        Timer::after_millis(200).await;

        let switches = gpio.read().read().value().bits();

        // Rotate mask downward, wrapping from bit 0 back to bit 7
        led_mask = led_mask.rotate_right(1);

        // Find next enabled switch from current position
        let mut i = 0u8;
        while i < 8 {
            if switches & led_mask != 0 {
                break;
            }
            led_mask = led_mask.rotate_right(1);
            i += 1;
        }

        // If no switches on, display nothing; otherwise light the LED
        let output = if i >= 8 { 0 } else { led_mask };
        gpio.write().write(|w| unsafe { w.value().bits(output) });
    }
}
