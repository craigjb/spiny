#![no_main]
#![no_std]

extern crate panic_halt;

use riscv::asm::delay;
use blinky_pac::Peripherals;

#[riscv_rt::entry]
fn main() -> ! {
    let peripherals = Peripherals::take().unwrap();

    loop {
        peripherals
            .gpio0
            .write()
            .write(|w| unsafe { w.value().bits(0xff) });
        delay(50_000_000);
        peripherals
            .gpio0
            .write()
            .write(|w| unsafe { w.value().bits(0xff00) });
        delay(50_000_000);
    }
}
