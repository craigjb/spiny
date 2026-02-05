#![no_main]
#![no_std]

extern crate defmt_rtt;
extern crate panic_halt;

use ddrtest_pac::Peripherals;

#[riscv_rt::entry]
fn main() -> ! {
    let peripherals = Peripherals::take().unwrap();

    defmt::println!("Hello world!");

    // Turn on all LEDs
    peripherals
        .gpio
        .write()
        .write(|w| unsafe { w.value().bits(0xFF) });

    loop {
        riscv::asm::wfi();
    }
}
