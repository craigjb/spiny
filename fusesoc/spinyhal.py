#                           /$$
#                          |__/
#        /$$$$$$$  /$$$$$$  /$$ /$$$$$$$  /$$   /$$
#       /$$_____/ /$$__  $$| $$| $$__  $$| $$  | $$
#      |  $$$$$$ | $$  \ $$| $$| $$  \ $$| $$  | $$   (c) Craig J Bishop
#       \____  $$| $$  | $$| $$| $$  | $$| $$  | $$   All rights reserved
#       /$$$$$$$/| $$$$$$$/| $$| $$  | $$|  $$$$$$$
#      |_______/ | $$____/ |__/|__/  |__/ \____  $$   MIT License
#                | $$                     /$$  | $$
#                | $$                    |  $$$$$$/
#                |__/                     \______/
#
# Permission is hereby granted, free of charge, to any person obtaining a
# copy of this software and associated documentation files (the
# "Software"), to deal in the Software without restriction, including
# without limitation the rights to use, copy, modify, merge, publish,
# distribute, sublicense, and/or sell copies of the Software, and to permit
# persons to whom the Software is furnished to do so, subject to the
# following conditions:
#
# The above copyright notice and this permission notice shall be included
# in all copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
# OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
# MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
# NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
# DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
# OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE
# USE OR OTHER DEALINGS IN THE SOFTWARE.

import sys
import json
import textwrap
import hashlib
from pathlib import Path

from fusesoc.capi2.generator import Generator


PERIPHERAL_GENERATORS = {}


def peripheral_generator(ptype):
    """Decorator to register a peripheral code generator."""
    def decorator(func):
        PERIPHERAL_GENERATORS[ptype] = func
        return func
    return decorator


@peripheral_generator("SpinyTimer")
def gen_timer(peripheral, soc_info, pac_crate_name):
    """Generate timer_hal! macro invocation for a SpinyTimer peripheral.

    Only generates the Embassy time driver for the machine timer.
    Non-machine timers are skipped (no HAL driver yet).
    """
    if not peripheral.get("is_machine_timer", False):
        return None

    name = peripheral["name"]
    mod_name = name.lower()
    timer_width = peripheral["timer_width"]
    num_compares = peripheral["num_compares"]
    cpu_freq_hz = soc_info["sys_clk_freq_hz"]

    # PAC peripheral type name matches the component name
    pac_type = name

    code = textwrap.dedent(f"""\
        pub mod {mod_name} {{
            spiny_hal::timer_hal! {{
                pac: {pac_crate_name},
                peripheral: {pac_type},
                timer_width: {timer_width},
                num_compares: {num_compares},
                cpu_freq_hz: {cpu_freq_hz},
            }}
        }}
    """)
    return code


class SpinyHalGen(Generator):
    def get_file_hash(self, path):
        if not path or not path.is_file():
            return None
        h = hashlib.sha256()
        with open(path, "rb") as f:
            for chunk in iter(lambda: f.read(65536), b""):
                h.update(chunk)
        return h.hexdigest()

    def generate_cargo_toml(self, crate_name, pac_crate_name, pac_crate_path,
                            spiny_hal_path):
        # Convert pac crate name to the Cargo dependency key (with hyphens)
        pac_dep_key = pac_crate_name.replace("_", "-")

        content = textwrap.dedent(f"""\
            [package]
            name = "{crate_name}"
            version = "0.1.0"
            edition = "2021"

            [dependencies]
            spiny-hal = {{ path = "{spiny_hal_path}" }}
            {pac_dep_key} = {{ path = "{pac_crate_path}" }}
            embassy-time-driver = {{ version = "0.2", features = ["tick-hz-32_768"] }}
            embassy-time-queue-utils = "0.3"
            critical-section = "1.2"
            riscv = "0.16"
        """)
        return content

    def run(self):
        crate_name = self.config.get("crate_name")
        output_path = self.config.get("output_path")
        json_path = self.config.get("json_path")
        pac_crate_name = self.config.get("pac_crate_name")
        pac_crate_path = self.config.get("pac_crate_path")
        spiny_hal_path = self.config.get("spiny_hal_path")

        missing_parameter = False
        for param in ["crate_name", "output_path", "json_path",
                      "pac_crate_name", "pac_crate_path", "spiny_hal_path"]:
            if not self.config.get(param):
                print(f"ERROR: '{param}' is a required parameter")
                missing_parameter = True
        if missing_parameter:
            sys.exit(1)

        files_root = Path(self.files_root)
        output_path = files_root / output_path
        json_src_path = files_root / json_path

        # Check cache
        current_hashes = {
            "json": self.get_file_hash(json_src_path),
            "crate_name": crate_name,
            "pac_crate_name": pac_crate_name,
        }

        state_file = output_path / ".generator_state.json"
        if output_path.exists() and not state_file.exists():
            print("ERROR: The output path exists, but has no generator state")
            print(f"Refusing to overwrite: {output_path}")
            print("Check if output_dir path is correct or delete manually")
            sys.exit(1)

        if (state_file.exists() and
                (output_path / "src").exists() and
                (output_path / "Cargo.toml").exists()):
            try:
                saved_state = json.loads(state_file.read_text())
                if saved_state == current_hashes:
                    print(f"[{crate_name}] Inputs unchanged. Skipping generation.")
                    return
            except (json.JSONDecodeError, KeyError):
                pass

        # Read JSON
        if not json_src_path.is_file():
            print("ERROR: HAL JSON input does not exist or is not a file")
            print(f"(expected here: {json_src_path.resolve().as_posix()})")
            sys.exit(1)

        with open(json_src_path) as f:
            hal_json = json.load(f)

        soc_info = hal_json["soc"]
        peripherals = hal_json["peripherals"]

        # Convert pac crate name to Rust identifier (hyphens to underscores)
        pac_rust_name = pac_crate_name.replace("-", "_")

        # Generate lib.rs
        lib_rs_parts = ["#![no_std]\n"]

        for peripheral in peripherals:
            ptype = peripheral.get("type")
            if ptype not in PERIPHERAL_GENERATORS:
                print(f"[{crate_name}] Skipping peripheral "
                      f"'{peripheral.get('name', '?')}' "
                      f"(no HAL generator for type '{ptype}')")
                continue

            gen_func = PERIPHERAL_GENERATORS[ptype]
            code = gen_func(peripheral, soc_info, pac_rust_name)
            if code is not None:
                lib_rs_parts.append(code)

        lib_rs_content = "\n".join(lib_rs_parts)

        # Write output crate
        src_path = output_path / "src"
        src_path.mkdir(parents=True, exist_ok=True)

        (src_path / "lib.rs").write_text(lib_rs_content)
        (output_path / "Cargo.toml").write_text(
            self.generate_cargo_toml(
                crate_name, pac_crate_name, pac_crate_path,
                spiny_hal_path))
        state_file.write_text(json.dumps(current_hashes))

        print(f"[{crate_name}] Generated HAL crate at {output_path}")


if __name__ == "__main__":
    generator = SpinyHalGen()
    generator.run()
