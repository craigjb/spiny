//! Minimal EDID base block decoding, enough to identify a sink.

#[derive(Debug, Clone, Copy, PartialEq, Eq, defmt::Format)]
pub enum EdidError {
    TooShort,
    BadHeader,
    BadChecksum,
}

pub struct EdidInfo {
    /// Three letter PNP manufacturer ID
    pub manufacturer: [u8; 3],
    pub product_code: u16,
    pub serial: u32,
    pub version: (u8, u8),
    /// Monitor name from the 0xfc descriptor, space padded
    pub name: [u8; 13],
    pub extensions: u8,
}

impl EdidInfo {
    /// The name with its terminator and padding removed
    pub fn name_str(&self) -> &str {
        let end = self.name.iter().position(|&b| b == 0x0a).unwrap_or(13);
        let trimmed = match self.name[..end].iter().rposition(|&b| b != b' ') {
            Some(last) => &self.name[..=last],
            None => &self.name[..0],
        };
        core::str::from_utf8(trimmed).unwrap_or("")
    }

    pub fn manufacturer_str(&self) -> &str {
        core::str::from_utf8(&self.manufacturer).unwrap_or("")
    }
}

const HEADER: [u8; 8] = [0x00, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x00];
const DESCRIPTOR_BASE: usize = 54;
const DESCRIPTOR_LEN: usize = 18;
const TAG_MONITOR_NAME: u8 = 0xfc;

pub fn parse(block: &[u8]) -> Result<EdidInfo, EdidError> {
    if block.len() < 128 {
        return Err(EdidError::TooShort);
    }
    if block[..8] != HEADER {
        return Err(EdidError::BadHeader);
    }
    if block[..128].iter().fold(0u8, |acc, &b| acc.wrapping_add(b)) != 0 {
        return Err(EdidError::BadChecksum);
    }

    // three five bit letters packed big endian, where 1 is 'A'
    let packed = u16::from_be_bytes([block[8], block[9]]);
    let manufacturer = [
        b'@' + ((packed >> 10) & 0x1f) as u8,
        b'@' + ((packed >> 5) & 0x1f) as u8,
        b'@' + (packed & 0x1f) as u8,
    ];

    let mut name = [b' '; 13];
    for index in 0..4 {
        let start = DESCRIPTOR_BASE + index * DESCRIPTOR_LEN;
        let descriptor = &block[start..start + DESCRIPTOR_LEN];
        // a display descriptor starts with three zero bytes, then its tag
        let is_display = descriptor[0] == 0 && descriptor[1] == 0 && descriptor[2] == 0;
        if is_display && descriptor[3] == TAG_MONITOR_NAME {
            name.copy_from_slice(&descriptor[5..18]);
        }
    }

    Ok(EdidInfo {
        manufacturer,
        product_code: u16::from_le_bytes([block[10], block[11]]),
        serial: u32::from_le_bytes([block[12], block[13], block[14], block[15]]),
        version: (block[18], block[19]),
        name,
        extensions: block[126],
    })
}
