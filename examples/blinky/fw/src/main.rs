#![no_main]
#![no_std]

extern crate panic_halt;

use blinky_pac::Peripherals;
use riscv::asm::delay;

const MAC_ADDR: [u8; 6] = [0x02, 0x00, 0x00, 0x00, 0x00, 0x01];

// accounts for 2-byte padding (alignment) at the beginning
const OFF_ETH_DST: usize = 2;
const OFF_ETH_SRC: usize = 8;
const OFF_ETHERTYPE: usize = 14;
const OFF_IP_HDR: usize = 16;
const OFF_IP_SRC: usize = 28;
const OFF_IP_DST: usize = 32;
const OFF_ICMP_HDR: usize = 36;

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
            .tx_align()
            .set_bit()
    });

    delay(10_000_000);

    let mut buffer = [0u8; 128];

    loop {
        if eth.status().read().rx_valid().bit_is_clear() {
            leds.write().write(|w| unsafe { w.value().bits(0x8001) });
            continue;
        }

        let bit_len = eth.rx_fifo().read().bits();
        let total_bytes = ((bit_len + 7) / 8) as usize;
        let words_to_read = (total_bytes + 3) / 4;
        let mut bytes_read = 0;

        for _ in 0..words_to_read {
            let word = eth.rx_fifo().read().bits();
            if bytes_read < buffer.len() {
                let bytes = word.to_le_bytes();
                let copy_len = 4.min(buffer.len() - bytes_read);
                buffer[bytes_read..bytes_read + copy_len].copy_from_slice(&bytes[0..copy_len]);
                bytes_read += 4;
            }
        }

        // drop packets that are too big
        if bytes_read > buffer.len() + 3 {
            continue;
        }

        // drop packets not to us
        if &buffer[OFF_ETH_DST..OFF_ETH_DST + 6] != MAC_ADDR {
            continue;
        }

        // packet is to us, turn on LEDs
        leds.write().write(|w| unsafe { w.value().bits(0xffff) });

        // check for IPv4
        if buffer[OFF_ETHERTYPE] != 0x08 || buffer[OFF_ETHERTYPE + 1] != 0x00 {
            turn_off_leds(leds);
            continue;
        }

        // is it ICMP?
        if buffer[OFF_IP_HDR + 9] != 1 {
            turn_off_leds(leds);
            continue;
        }

        // is it an echo request?
        if buffer[OFF_ICMP_HDR] != 8 {
            turn_off_leds(leds);
            continue;
        }

        // construct reply
        // flip MAC addresses
        for i in 0..6 {
            buffer.swap(OFF_ETH_DST + i, OFF_ETH_SRC + i);
        }

        // flip IP addresses
        for i in 0..4 {
            buffer.swap(OFF_IP_DST + i, OFF_IP_SRC + i);
        }

        // change type to reply
        buffer[OFF_ICMP_HDR] = 0;

        // IP checksum
        buffer[OFF_IP_HDR + 10] = 0;
        buffer[OFF_IP_HDR + 11] = 0;
        let ip_checksum = checksum(&buffer[OFF_IP_HDR..OFF_IP_HDR + 20]);
        buffer[OFF_IP_HDR + 10] = (ip_checksum >> 8) as u8;
        buffer[OFF_IP_HDR + 11] = (ip_checksum & 0xFF) as u8;

        // ICMP checksum
        buffer[OFF_ICMP_HDR + 2] = 0;
        buffer[OFF_ICMP_HDR + 3] = 0;
        let icmp_len = total_bytes - OFF_ICMP_HDR;
        let icmp_csum = checksum(&buffer[OFF_ICMP_HDR..OFF_ICMP_HDR + icmp_len]);
        buffer[OFF_ICMP_HDR + 2] = (icmp_csum >> 8) as u8;
        buffer[OFF_ICMP_HDR + 3] = (icmp_csum & 0xFF) as u8;

        // transmit!
        eth.tx_fifo().write(|w| unsafe { w.bits(bit_len) });
        let mut i = 0;
        while i < total_bytes {
            let mut word_bytes = [0u8; 4];

            // Copy up to 4 bytes into temporary buffer
            for j in 0..4 {
                if i + j < total_bytes {
                    word_bytes[j] = buffer[i + j];
                }
            }

            // Convert [u8; 4] -> u32 (Little Endian)
            let word = u32::from_le_bytes(word_bytes);

            eth.tx_fifo().write(|w| unsafe { w.bits(word) });
            i += 4;
        }
        turn_off_leds(leds);
    }
}

fn checksum(data: &[u8]) -> u16 {
    let mut sum: u32 = 0;
    let mut i = 0;
    while i < data.len() - 1 {
        // Build u16 from two bytes
        let word = ((data[i] as u32) << 8) | (data[i + 1] as u32);
        sum += word;
        i += 2;
    }
    if i < data.len() {
        sum += (data[i] as u32) << 8;
    }
    while (sum >> 16) != 0 {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }
    !sum as u16
}

fn turn_off_leds(leds: &blinky_pac::Gpio0) {
    delay(100_000_000);
    leds.write().write(|w| unsafe { w.value().bits(0x0) });
}
