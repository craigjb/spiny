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

    let mut led_mask: usize = 0x80;
    loop {
        Timer::after_millis(100).await;

        let switches = gpio.switches_read().read().value().bits() as usize;

        // Find next enabled switch from current position
        // If no switches on, display nothing; otherwise light the LED
        let mut output = 0;
        for _ in 0..8 {
            if switches & led_mask != 0 {
                output = led_mask;
                break;
            } else {
                led_mask = if led_mask == 1 {
                    0x80
                } else {
                    led_mask.rotate_right(1)
                };
            }
        }

        led_mask = if led_mask == 1 {
            0x80
        } else {
            led_mask.rotate_right(1)
        };

        gpio.leds_write()
            .write(|w| unsafe { w.value().bits(output as _) });
    }
}
