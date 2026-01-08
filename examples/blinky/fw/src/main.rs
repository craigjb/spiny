#![no_main]
#![no_std]

extern crate panic_halt;

use blinky_pac::Peripherals;
use riscv::asm::delay;

const MAC_ADDR_W0: u32 = 0x00020000;
const MAC_ADDR_W1: u32 = 0x01000000;

#[riscv_rt::entry]
fn main() -> ! {
    let peripherals = Peripherals::take().unwrap();
    let leds = &peripherals.gpio0;
    let eth = &peripherals.eth;

    eth.ctrl().write(|w| {
        w.phy_reset()
            .clear_bit()
            .rx_reset()
            .clear_bit()
            .tx_reset()
            .clear_bit()
            .rx_align()
            .set_bit()
    });

    delay(10_000_000);

    loop {
        let status = eth.status().read();
        if status.rx_valid().bit_is_clear() {
            leds.write().write(|w| unsafe { w.value().bits(0x8001) });
            continue;
        }

        let bit_len = eth.rx_fifo().read().bits();
        let words_to_read = (bit_len as u32 + 31) >> 2;

        let w0: u32 = eth.rx_fifo().read().bits();
        let w1: u32 = eth.rx_fifo().read().bits();

        for _ in 0..(words_to_read - 2) {
            let _ = eth.rx_fifo().read().bits();
        }

        if w0 == MAC_ADDR_W0 && w1 == MAC_ADDR_W1 {
            leds.write().write(|w| unsafe { w.value().bits(0xffff) });
            delay(100_000_000);
            leds.write().write(|w| unsafe { w.value().bits(0x0) });
        }
    }
}
