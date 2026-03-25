#![no_main]
#![no_std]

extern crate defmt_rtt;
extern crate panic_halt;

mod draminit;

use core::ptr;
use ddrtest_pac::Peripherals;

const DRAM_BASE: usize = 0x2000_0000;
const DRAM_SIZE: usize = 512 * 1024 * 1024;
const NUM_CHUNKS: usize = 8;
const CHUNK_SIZE: usize = DRAM_SIZE / NUM_CHUNKS;

#[riscv_rt::entry]
fn main() -> ! {
    let p = Peripherals::take().unwrap();

    defmt::println!("Hello world!");

    draminit::init_dram(&p.ddrctrl, &p.ddrphy, &p.sdram);

    defmt::info!("MEMTEST: Starting...");
    let mut leds = 0u8;

    for chunk in 0..NUM_CHUNKS {
        let base = DRAM_BASE + chunk * CHUNK_SIZE;
        let addrs = || (base..base + CHUNK_SIZE).step_by(16);

        addrs().for_each(|addr| unsafe {
            ptr::write_volatile(addr as *mut u32, addr as u32);
            ptr::write_volatile((addr + 4) as *mut u32, (addr + 4) as u32);
            ptr::write_volatile((addr + 8) as *mut u32, (addr + 8) as u32);
            ptr::write_volatile((addr + 12) as *mut u32, (addr + 12) as u32);
        });

        let fail = addrs().find_map(|addr| {
            let val0 = unsafe { ptr::read_volatile(addr as *const u32) };
            let val1 = unsafe { ptr::read_volatile((addr + 4) as *const u32) };
            let val2 = unsafe { ptr::read_volatile((addr + 8) as *const u32) };
            let val3 = unsafe { ptr::read_volatile((addr + 12) as *const u32) };
            if val0 != addr as u32 {
                Some((addr, val0))
            } else if val1 != (addr + 4) as u32 {
                Some((addr + 4, val1))
            } else if val2 != (addr + 8) as u32 {
                Some((addr + 8, val2))
            } else if val3 != (addr + 12) as u32 {
                Some((addr + 12, val3))
            } else {
                None
            }
        });

        match fail {
            Some((addr, val)) => defmt::warn!(
                "MEMTEST: chunk {} fail at 0x{:08x}: got 0x{:08x}",
                chunk,
                addr,
                val
            ),
            None => {
                leds |= 1 << chunk;
                defmt::info!("MEMTEST: chunk {} OK", chunk);
            }
        }
        p.gpio.write().write(|w| unsafe { w.value().bits(leds) });
    }

    defmt::info!("MEMTEST: Done, LEDs = 0b{:08b}", leds);

    loop {
        riscv::asm::wfi();
    }
}
