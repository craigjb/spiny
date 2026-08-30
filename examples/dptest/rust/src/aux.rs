//! Blocking DisplayPort AUX transactions over the DisplayPort peripheral.

use dptest_pac::DisplayPort;

/// Outcome of a transaction, matching AuxLinkResult in the Scala source
#[derive(Debug, Clone, Copy, PartialEq, Eq, defmt::Format)]
pub enum AuxResult {
    Ack,
    Nack,
    Defer,
    Timeout,
    PhyError,
    Unknown(u8),
}

impl AuxResult {
    fn from_bits(bits: u8) -> Self {
        match bits {
            0 => Self::Ack,
            1 => Self::Nack,
            2 => Self::Defer,
            3 => Self::Timeout,
            4 => Self::PhyError,
            other => Self::Unknown(other),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, defmt::Format)]
pub enum AuxError {
    /// The link gave a definitive answer that was not an ACK
    Failed(AuxResult),
    /// A request byte did not reach the buffer, so the request was malformed
    RequestDropped,
    /// The reply was longer than the caller's buffer
    ReplyTooLong(usize),
}

/// Native AUX read command, in the top nibble of the first request byte
pub const NATIVE_READ: u8 = 0x9;

/// Clears the latched AUX events so the next transaction starts clean
fn clear_events(dp: &DisplayPort) {
    dp.aux_int_raw().write(|w| {
        w.done_raw()
            .clear_bit_by_one()
            .rx_overrun_raw()
            .clear_bit_by_one()
            .rx_unexpected_raw()
            .clear_bit_by_one()
            .request_dropped_raw()
            .clear_bit_by_one()
    });
}

/// Runs one transaction, returning the reply bytes written into `reply`
///
/// The link handles retries and the reply timeout, so this only spins until
/// it settles.
pub fn transact<'a>(
    dp: &DisplayPort,
    request: &[u8],
    reply: &'a mut [u8],
) -> Result<&'a [u8], AuxError> {
    clear_events(dp);

    for byte in request {
        dp.request().write(|w| unsafe { w.data().bits(*byte) });
    }
    if dp.aux_int_raw().read().request_dropped_raw().bit_is_set() {
        return Err(AuxError::RequestDropped);
    }

    dp.control().write(|w| w.start().set_bit());
    while dp.status().read().busy().bit_is_set() {}

    let status = dp.status().read();
    let result = AuxResult::from_bits(status.result().bits());
    let length = status.reply_length().bits() as usize;

    if result != AuxResult::Ack {
        return Err(AuxError::Failed(result));
    }
    if length > reply.len() {
        return Err(AuxError::ReplyTooLong(length));
    }

    for slot in reply[..length].iter_mut() {
        *slot = dp.reply().read().data().bits();
    }
    Ok(&reply[..length])
}

/// Reads up to 16 bytes from DPCD, returning the data after the reply header
pub fn dpcd_read<'a>(
    dp: &DisplayPort,
    address: u32,
    length: usize,
    reply: &'a mut [u8],
) -> Result<&'a [u8], AuxError> {
    let request = [
        (NATIVE_READ << 4) | ((address >> 16) & 0xf) as u8,
        (address >> 8) as u8,
        address as u8,
        (length - 1) as u8,
    ];
    let bytes = transact(dp, &request, reply)?;
    // the first reply byte is the AUX_ACK header, the rest is DPCD data
    Ok(&bytes[1..])
}

/// I2C over AUX command codes, with MOT set while the transaction stays open
const I2C_WRITE: u8 = 0x0;
const I2C_READ: u8 = 0x1;
const MOT: u8 = 0x4;

/// DDC address the EDID lives behind
pub const EDID_I2C_ADDR: u32 = 0x50;
/// One EDID block
pub const EDID_BLOCK_LEN: usize = 128;
/// The AUX length field is 4 bits, so one transaction moves at most 16 bytes
const AUX_MAX_DATA: usize = 16;
/// The DDC bus defers a lot, so give each chunk a few goes of its own
const I2C_ATTEMPTS: usize = 4;

fn i2c_header(command: u8, address: u32) -> [u8; 3] {
    [
        (command << 4) | (((address >> 16) & 0xf) as u8),
        (address >> 8) as u8,
        address as u8,
    ]
}

/// Writes bytes to an I2C device over AUX
pub fn i2c_write(
    dp: &DisplayPort,
    address: u32,
    data: &[u8],
    mot: bool,
) -> Result<(), AuxError> {
    let command = if mot { I2C_WRITE | MOT } else { I2C_WRITE };
    let header = i2c_header(command, address);
    let mut request = [0u8; 4 + AUX_MAX_DATA];
    request[..3].copy_from_slice(&header);
    request[3] = (data.len() - 1) as u8;
    request[4..4 + data.len()].copy_from_slice(data);

    let mut reply = [0u8; 17];
    transact(dp, &request[..4 + data.len()], &mut reply)?;
    Ok(())
}

/// Reads from an I2C device over AUX, returning how many bytes arrived
///
/// A sink may answer with fewer bytes than asked for, so the caller has to
/// look at the count rather than assume the buffer was filled.
pub fn i2c_read(
    dp: &DisplayPort,
    address: u32,
    out: &mut [u8],
    mot: bool,
) -> Result<usize, AuxError> {
    let command = if mot { I2C_READ | MOT } else { I2C_READ };
    let header = i2c_header(command, address);
    let request = [header[0], header[1], header[2], (out.len() - 1) as u8];

    let mut reply = [0u8; 17];
    let bytes = transact(dp, &request, &mut reply)?;
    let data = &bytes[1..];
    out[..data.len()].copy_from_slice(data);
    Ok(data.len())
}

/// Reads one EDID block over I2C over AUX
pub fn edid_read(dp: &DisplayPort, out: &mut [u8]) -> Result<(), AuxError> {
    // set the read offset, holding the transaction open with MOT
    i2c_write(dp, EDID_I2C_ADDR, &[0x00], true)?;

    let mut offset = 0;
    while offset < out.len() {
        let chunk = AUX_MAX_DATA.min(out.len() - offset);
        // the last read closes the transaction
        let mot = offset + chunk < out.len();

        let mut got = 0;
        let mut last = AuxError::Failed(AuxResult::Defer);
        for _ in 0..I2C_ATTEMPTS {
            match i2c_read(dp, EDID_I2C_ADDR, &mut out[offset..offset + chunk], mot) {
                Ok(0) => last = AuxError::Failed(AuxResult::Defer),
                Ok(n) => {
                    got = n;
                    break;
                }
                Err(error) => last = error,
            }
        }
        if got == 0 {
            return Err(last);
        }
        offset += got;
    }
    Ok(())
}
