#![no_main]
#![no_std]

extern crate panic_halt;

use blinky_pac::Peripherals;
use riscv::asm::wfi;
use riscv::register::{mie, mstatus};

static mut CURRENT_LED: usize = 15;

// 100 MHz clock, 200 ms delay
// prescaler = 10,000, compare at 2,000
// 10,000 * 2,000 = 20,000,000 cycles = 200 ms
const TIMER_PRESCALE: u32 = 10_000;
const TIMER_COMPARE: u32 = 2_000;

#[riscv_rt::entry]
fn main() -> ! {
    let peripherals = Peripherals::take().unwrap();

    // Configure timer to fire compare interrupt every 200 ms
    peripherals
        .timer
        .prescale()
        .write(|w| unsafe { w.bits(TIMER_PRESCALE) });

    peripherals
        .timer
        .compare0()
        .write(|w| unsafe { w.bits(TIMER_COMPARE) });

    peripherals.timer.counter().write(|w| unsafe { w.bits(0) });

    // Enable timer and interrupts, unmask compare0 interrupt
    peripherals
        .timer
        .control()
        .write(|w| w.enable().set_bit().interrupt_enable().set_bit());

    peripherals
        .timer
        .interrupt_mask()
        .write(|w| w.compare0mask().clear_bit());

    // Enable machine timer interrupt
    unsafe {
        mie::set_mtimer();
        mstatus::set_mie();
    }

    // Main loop just waits for interrupts
    loop {
        wfi();
    }
}

#[unsafe(export_name = "MachineTimer")]
fn machine_timer_handler() {
    let peripherals = unsafe { Peripherals::steal() };

    // Clear compare0 interrupt status and reset counter using clear bit
    peripherals
        .timer
        .interrupt_status()
        .write(|w| w.compare0status().clear_bit_by_one());
    peripherals
        .timer
        .control()
        .modify(|_, w| w.clear().set_bit());

    let gpio = &peripherals.gpio;
    let switches = gpio.read().read().value().bits() as u16;
    let current = unsafe { CURRENT_LED };

    // Find next LED with switch enabled, wrapping from current-1 down to 0, then 15 down to current+1
    let next_led = (0..current)
        .rev()
        .chain(((current + 1)..=15).rev())
        .find(|&led| (switches & (1 << led)) != 0);

    match next_led {
        Some(led) => {
            unsafe { CURRENT_LED = led };
            gpio.write().write(|w| unsafe { w.value().bits(1 << led) });
        }
        None => {
            // No switches enabled, turn off all LEDs
            gpio.write().write(|w| unsafe { w.value().bits(0) });
        }
    }
}
