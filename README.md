# Spiny

A library for building FPGA projects using SpinalHDL, Rust (with Embassy), and FuseSoC.

## Overview

Spiny has three main components:

**1. [SpinalHDL](https://spinalhdl.github.io/RTD/master/index.html) library**
- VexRiscv CPU profiles for running embedded Rust
- Customizable peripherals with auto-generated Rust boilerplate (and documentation)
- Automated mapping and wiring for peripheral bus and interrupts
- Output [system view description (SVD)](https://open-cmsis-pack.github.io/svd-spec/latest/index.html) file
- Generate linker script for SoC memory layout

**2. [FuseSoC](https://fusesoc.readthedocs.io) generators**
- Automatically generate peripheral access crate (PAC) for Rust from SVD file
- Include Rust firmware build in FuseSoC dependency tree

**3. Hardware abstraction layer for [Embassy](https://embassy.dev) (coming soon)**

## Quick Start

```bash
# Clone the repository
git clone https://github.com/craigjb/spiny.git
cd spiny

# Build firmware and simulate example. Run from spiny's root directory.
fusesoc run --setup --target=nexys_a7_100t craigjb:spiny:blinky:0.1.0
sbtn "runMain spiny.examples.blinky.TopLevelSim fw/target/release/blinky.bin"

# Build example for FPGA (Digilent Nexys A7-100T board)
fusesoc run --build --target=nexys_a7_100t craigjb:spiny:blinky:0.1.0
```

See [examples/blinky](examples/blinky) for detailed build instructions and expected behavior.

## Prerequisites

### Required Tools

- **Scala Build Tool (sbt)** - for SpinalHDL compilation
  ```bash
  # Install via SDKMAN (recommended)
  curl -s "https://get.sdkman.io" | bash
  sdk install sbt
  ```
  Or see: https://www.scala-sbt.org/download.html

- **FuseSoC** - build system and package management for digital hardware
  ```bash
  pip3 install fusesoc
  ```
  See: https://fusesoc.readthedocs.io

- **Rust & Cargo** - for firmware development
  ```bash
  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh

  # For example SoC
  rustup target add riscv32i-unknown-none-elf
  ```
  See: https://rustup.rs
  For supported RISC-V extensions and targets, [see these Rust docs](https://doc.rust-lang.org/nightly/rustc/platform-support.html#tier-2-without-host-tools)

### Optional Tools

- **Verilator** - for RTL simulation
  ```bash
  # Ubuntu/Debian
  sudo apt-get install verilator

  # macOS
  brew install verilator
  ```
  See: https://verilator.org

- **Xilinx Vivado** - for FPGA synthesis, place, route, and bitstream generation
  - Download from: https://www.xilinx.com/support/download.html

## Installation

### Using the SpinalHDL Library

To use Spiny's SpinalHDL library in your own project, add it as a dependency in your `build.sbt`:

```scala
lazy val spiny = RootProject(uri("https://github.com/craigjb/spiny.git"))

lazy val myProject = (project in file("."))
  .dependsOn(spiny)
  .settings(
    name := "my-project",
    scalaVersion := "2.12.18",
    // ... other settings
  )
```

Then import Spiny components in your Scala code:
```scala
import spiny.soc._
import spiny.peripheral._
```

### Using the FuseSoC Generators

To use the FuseSoC generators, add Spiny to your FuseSoC library:

```bash
fusesoc library add spiny https://github.com/craigjb/spiny.git
```

Or manually add to your `fusesoc.conf`:
```ini
[library.spiny]
sync-uri = https://github.com/craigjb/spiny.git
sync-type = git
```

## Creating a SoC

Here's a minimal example showing how to create a SoC with a timer and a GPIO peripheral:

```scala
import spinal.core._
import spiny.soc._
import spiny.peripheral._

class MyDesign extends Component {
  val io = new Bundle {
    val SYS_CLK = in(Bool())
    val CPU_RESET_N = in(Bool())
  }

  val sysClkDomain = ClockDomain(
    clock = io.SYS_CLK,
    reset = ResetCtrl.asyncAssertSyncDeassert(
      input = !io.CPU_RESET_N,
      clockDomain = ClockDomain(io.SYS_CLK)
    ),
    frequency = FixedFrequency(100 MHz)
  )

  val soc = sysClkDomain on new SpinySoC(
    cpuProfile = SpinyRv32iRustCpuProfile(),
    ramSize = 4 kB,
    firmwarePath = "firmware.bin"
  ) {
    // Timer with RISC-V machine timer interrupt
    val timer = new SpinyTimer(
      timerWidth = 32,
      prescaleWidth = 16,
      numCompares = 1,
      isMachineTimer = true
    ).setName("Timer")

    // GPIO with output and input banks
    val gpio = new SpinyGpio(
      Seq(
        SpinyGpioBankConfig(
          width = 16,
          direction = SpinyGpioDirection.Output,
          name = "leds"
        ),
        SpinyGpioBankConfig(
          width = 16,
          direction = SpinyGpioDirection.Input,
          name = "switches"
        )
      )
    ).setName("Gpio")

    // Export GPIO pins to top-level IO
    gpio.getBankBits("leds").toIo().setName("LEDS")
    gpio.getBankBits("switches").toIo().setName("SWITCHES")

    // Build the SoC with peripherals
    build(peripherals = Seq(timer, gpio))
  }
}
```

See the complete [Blinky example](examples/blinky) for a full working SoC with firmware.

## Using FuseSoC Generators (Standalone)

Spiny provides FuseSoC generators that can be used independently in any FuseSoC project, even without using this SpinalHDL library.

### Adding Spiny as a Generator Library

First, add Spiny to your FuseSoC libraries:
```bash
fusesoc library add spiny https://github.com/craigjb/spiny.git
```

Or manually add to your `fusesoc.conf`:
```ini
[library.spiny]
sync-uri = https://github.com/craigjb/spiny.git
sync-type = git
```

See the [FuseSoC package manager documentation](https://fusesoc.readthedocs.io/en/stable/user/package_manager/index.html) for more details.

### SpinalHDL Generator

Generates Verilog from SpinalHDL by running sbt. Useful for any SpinalHDL-based project.

**In your FuseSoC `.core` file:**
```yaml
generate:
  my_rtl:
    generator: spinalhdl
    parameters:
      sbt_dir: "./" # Path to directory containing build.sbt
      output_path: "rtl/MyTop.v" # Where to write generated Verilog
      main: my.package.TopLevelVerilog # Scala main object to run
      file_type: verilogSource # FuseSoC file type for output
      args: # Optional arguments to main
        - "arg1"
        - "arg2"

filesets:
  rtl:
    depend:
      - craigjb:spiny:generators
```

The generator will:
1. Run `sbt "runMain <main> <args...>"`
2. Collect generated Verilog
3. Make output available to FuseSoC build as the specified file type

### Cargo Generator

Builds Rust firmware projects, and with [cargo-binutils](https://github.com/rust-embedded/cargo-binutils) installed, handles binary conversion.

**In your FuseSoC `.core` file:**
```yaml
generate:
  firmware:
    generator: cargo
    parameters:
      project_dir: "fw" # Directory containing Cargo.toml
      args:
        - "objcopy"
        - "--release"
        - "--"
        - "-O"
        - "binary"
        - "target/release/firmware.bin"

filesets:
  fw:
    depend:
      - craigjb:spiny:generators
```

The generator will run `cargo <args...>` with the arguments specified.

### Rust PAC Generator

Generates a Rust peripheral access crate (PAC) from an SVD file using `svd2rust`.

**In your FuseSoC `.core` file:**
```yaml
generate:
  pac:
    generator: rustpac
    parameters:
      crate_name: my-pac # Output PAC name
      crate_version: 0.1.0 # Output PAC version
      output_path: "target/rust/my-pac" # Where to create PAC
      svd_path: "target/peripheral.svd" # Path to SVD input
      linker_script_path: "target/memory.x"  # Optional linker script output

filesets:
  pac:
    depend:
      - craigjb:spiny:generators
```

The generator will:
1. Run `svd2rust`, `form`, and `rustfmt` to create PAC source files
2. Generate `Cargo.toml`, `build.rs`, and optional linker script

## Peripherals

| Peripheral | Description |
|------------|-------------|
| **SpinyTimer** | Configurable timer with prescaler, multiple compare channels, overflow & compare interrupts, optional machine timer interrupt |
| **SpinyGpio** | Multi-bank GPIO with configurable direction (input, output, inout) per bank |
| **SpinyEthernet** | 100BASE-X Ethernet MAC with RMII interface and TX/RX FIFOs |

More coming! All peripherals implement the `SpinyPeripheral` trait for easy integration into `SpinySoC`.

See source code in `spinal/spiny/peripheral/` for implementation details.

## Examples

### Blinky
Simple example demonstrating timer interrupts and GPIO control. Features:
- Timer configured as RISC-V machine timer for interrupt-driven operation
- LED sequencing based on switch inputs
- Rust firmware using generated peripheral access crate (PAC)
- Simulation with Verilator and build for FPGA (Digilent Nexys A7 board)

See [examples/blinky](examples/blinky) for complete documentation and build instructions.

## Project Structure

```
spiny/
├── spinal/
│   └── spiny/
│       ├── soc/          # SoC infrastructure (CPU, memory, interconnect)
│       └── peripheral/   # Reusable peripherals (Timer, GPIO, etc.)
├── examples/
│   └── blinky/          # Example SoC with firmware
│       ├── spinal/      # Hardware description
│       ├── fw/          # Rust firmware
│       └── data/        # Constraints and settings
├── generators/          # FuseSoC generator scripts
└── build.sbt           # Scala build configuration
```
