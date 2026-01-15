# Blinky Example

Simple RISC-V SoC example demonstrating timer interrupts and GPIO control with Spiny.

## Overview

This example implements a simple system that uses a hardware timer to generate periodic interrupts, with firmware that sequences through LEDs based on switch inputs. It demonstrates:

### Behavior

The firmware configures the timer to fire an interrupt every 200ms. On each interrupt, it:
1. Reads the current switch states (16 switches)
2. Finds the next enabled LED in sequence (cycling from current-1 down to 0, then 15 down to current+1)
3. Lights up that LED
4. If no switches are enabled, turns off all LEDs

This creates a rotating LED pattern that only visits LEDs whose corresponding switches are enabled.

## Hardware Architecture

### SoC Configuration
- **CPU**: VexRiscv RV32I with Rust optimizations and Xilinx BSCANE2 debug support (uses the same FPGA configuration cable for RISC-V JTAG)
- **RAM**: 4 KB on-chip memory
- **Clock**: 100 MHz system clock (directly from input, no MMCM)
- **Reset**: Asynchronous assert, synchronous de-assert

### Peripherals

The timer and GPIO peripherals are automatically mapped to memory addresses and wired to the peripheral bus. Interrupts are automatically added to the VexRiscv CPU and wired up.

#### Timer
- 32-bit counter with 16-bit prescaler
- 1 compare channel (compare0)
- Configured as RISC-V machine timer (wired to `csrPlugin.timerInterrupt`)
- Settings:
  - Prescaler: 10,000 (divides 100 MHz to 10 kHz)
  - Compare: 2,000 (200ms at 10 kHz)
  - Simulation speedup: 10,000x faster for Verilator testing

#### GPIO
- Bank 0: 16 output bits (LEDs)
- Bank 1: 16 input bits (switches)

## Firmware Architecture

### Interrupt Handler

The firmware uses a machine timer interrupt handler with the attribute:
```rust
#[unsafe(export_name = "MachineTimer")]
fn machine_timer_handler() { ... }
```

This is compatible with the `riscv-rt` crate.

## Prerequisites

See the [main Spiny README](../../README.md#prerequisites) for installation of:
- Scala Build Tool (sbt)
- FuseSoC
- Rust & Cargo with `riscv32i-unknown-none-elf` target
- Verilator (for simulation)
- Vivado (for FPGA builds)

## Building

### Setup FuseSoC Library

Add Spiny to your FuseSoC libraries:
```bash
fusesoc library add spiny https://github.com/craigjb/spiny.git
```

Or manually add to your `fusesoc.conf`:
```ini
[library.spiny]
sync-uri = https://github.com/craigjb/spiny.git
sync-type = git
```

### Simulate with Verilator
This command will:
1. Generate Verilog from SpinalHDL
2. Generate SVD file for peripherals
3. Generate Rust PAC from SVD
4. Build Rust firmware in release mode
5. Convert firmware ELF to binary

```bash
fusesoc run --setup --target=nexys_a7_100t craigjb:spiny:blinky:0.1.0
```

This command will run the SpinalHDL simulation with Verilator:

```bash
# Run from spiny's root directory.
sbtn "runMain spiny.examples.blinky.TopLevelSim fw/target/release/blinky.bin"
```

The waveform file `.vcd` will be output in to `simWorkspace/Blinky/test/wave.vcd`. You can open this in a waveform viewer like [Surfer](https://surfer-project.org).

### Build for FPGA (Nexys A7-100T)

This command will:
1. Generate Verilog from SpinalHDL
2. Generate SVD file for peripherals
3. Generate Rust PAC from SVD
4. Build Rust firmware in release mode
5. Convert firmware ELF to binary
6. Generate Verilog with firmware included
7. Run Vivado to create FPGA bitstream

```bash
fusesoc run --build --target=nexys_a7_100t craigjb:spiny:blinky:0.1.0
```

Output bitstream: `build/craigjb_spiny_blinky_0.1.0/nexys_a7_100t/craigjb_spiny_blinky_0.1.0.bit`

### Programming the FPGA

Use [openFPGALoader](https://github.com/trabucayre/openFPGALoader) or Vivado Hardware Manager.

Example for openFPGALoader and a Digilent cable:
```bash
openFPGALoader -c digilent --freq 30000000 --bitstream build/craigjb_spiny_blinky_0.1.0/nexys_a7_100t/craigjb_spiny_blinky_0.1.0.bit
```
