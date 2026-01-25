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
import yaml
import subprocess
from pathlib import Path
from collections.abc import Iterable

from fusesoc.capi2.generator import Generator

from litedram import modules as litedram_modules
from litedram.modules import (
    SDRModule, DDR2Module, DDR3Module, DDR4Module,
    _TechnologyTimings, _SpeedgradeTimings
)


SUPPORTED_MEM_TYPES = [
    "DDR2"
]

MODULE_BASE_CLASS_MAP = {
    "SDR": SDRModule,
    "DDR2": DDR2Module,
    "DDR3": DDR3Module,
    "DDR4": DDR4Module,
}

SUPPORTED_USER_PORT_TYPES = [
    "native"
]

def err_req_param(param_name, container_name):
    print(f"ERROR: `{param_name}` is a required parameter "
          f"in {container_name}")


def validate_exists(input, param_name, container_name):
    value = input.get(param_name, None)
    if value is None:
        err_req_param(param_name, container_name)
        return None
    else:
        return value


def validate_int(input, param_name, container_name):
    value = input.get(param_name, None)
    if value is None:
        err_req_param(param_name, container_name)
        return None

    try:
        return int(value)
    except (ValueError, TypeError) as e:
        print(f"ERROR: `{param_name}` must be an integer")
        return None


def validate_float(input, param_name, container_name):
    value = input.get(param_name, None)
    if value is None:
        err_req_param(param_name, container_name)
        return None

    try:
        return float(value)
    except (ValueError, TypeError) as e:
        print(f"ERROR: `{param_name}` must be a number")
        return None


def validate_tuple_cycles_time(input, param_name, container_name):
    raw_value = input.get(param_name, None)
    if value is None:
        err_req_param(param_name, container_name)
        return None

    if not isinstance(raw_value, Iterable) or len(raw_value) != 2:
        print(f"ERROR: `{param_name}` must be a list of [int, number]")
        return None

    try:
        if raw_value[0] is None:
            cycles = None
        else:
            cycles = int(raw_value[0])

        if raw_value[1] is None:
            time = None
        else:
            time = float(raw_value[1])

        return (cycles, time)
    except (ValueError, TypeError) as e:
        print(f"ERROR: `{param_name}` must be a list of [int, number]")
        return None


def validate_geometry(geom_def):
    nbanks = validate_int(geom_def, "num_banks", "geometry")
    nrows = validate_int(geom_def, "num_rows", "geometry")
    ncols = validate_int(geom_def, "num_cols", "geometry")

    if None in [nbanks, nrows, ncols]:
        sys.exit(1)

    return {
        "nbanks": nbanks,
        "nrows": nrows,
        "ncols": ncols
    }


def validate_tech_timings(timings_def):
    tREFI = validate_float(timings_def, "tREFI", "timings")
    tWTR = validate_tuple_cycles_time("tWTR", "timings")
    tCCD = validate_tuple_cycles_time("tCCD", "timings")
    tRRD = validate_tuple_cycles_time("tRRD", "timings")
    tZQCS = validate_tuple_cycles_time("tZQCS", "timings")

    if None in [tREFI, tWTR, tCCD, tRRD, tZQCS]:
        sys.exit(1)

    return _TechnologyTimings(
        tREFI = tREFI,
        tWTR = tWTR,
        tCCD = tCCD,
        tRRD = tRRD,
        tZQCS = tZQCS
    )

def validate_speedgrade_timings(timings_def):
    tRP = validate_float(timings_def, "tRP", "timings")
    tRCD = validate_float(timings_def, "tRCD", "timings")
    tWR = validate_float(timings_def, "tWR", "timings")
    tRFC = validate_tuple_cycles_time("tRFC", "timings")
    tFAW = validate_tuple_cycles_time("tFAW", "timings")
    tRAS = validate_float(timings_def, "tRAS", "timings")

    if None in [tRP, tRCD, tWR, tRFC, tFAW, tRAS]:
        sys.exit(1)

    return _SpeedgradeTimings(
        tRP = tRP,
        tRCD = tRCD,
        tWR = tWR,
        tRFC = tRFC,
        tFAW = tFAW,
        tRAS = tRAS
    )


def create_custom_module(module_def, mem_type, geom):
    """
    LiteDRAM only has some DRAM modules built-in, so this creates a new
    sub-class based on a YAML definition
    """
    container_name = "dram_module"
    module_name = validate_exists(module_def, "name", container_name)
    timings_def = validate_exists(module_def, "timings", container_name)

    if None in [module_name, timings_def]:
        sys.exit(1)

    tech_timings = validate_tech_timings(timings_def)
    speedgrade_timings = validate_speedgrade_timings(timings_def)

    if mem_type not in MODULE_BASE_CLASS_MAP:
        print(f"ERROR: Unknown module type: {mem_type}. ")
        print(f"       Must be one of {list(MODULE_BASE_CLASS_MAP.keys())}")
        sys.exit(1)
    base_class = MODULE_BASE_CLASS_MAP[mem_type]

    return type(module_name, (base_class,), {
        "nbanks": geom["nbanks"],
        "nrows": geom["nrows"],
        "ncols": geom["ncols"],
        "technology_timings": tech_timings,
        "speedgrade_timings": {
            "default": speedgrade_timings
        }
    })


def translate_config(config):
    """
    Translate config to LiteDRAM config and create custom module if needed
    """
    ctn_name = "config file"

    # general
    fpga_speedgrade = validate_int(config, "fpga_speedgrade", ctn_name)
    mem_type = validate_exists(config, "type", ctn_name)

    # PHY
    extra_cmd_latency = validate_int(config, "extra_cmd_latency", ctn_name)
    num_byte_groups = validate_int(config, "num_byte_groups", ctn_name)
    num_ranks = validate_int(config, "num_ranks", ctn_name)
    phy = validate_exists(config, "phy", ctn_name)

    # electrical
    rtt_nom = config.get("rtt_nom", None)
    rtt_wr = config.get("rtt_wr", None)
    ron = config.get("ron", None)
    
    # frequency
    input_clk_freq = validate_float(config, "input_clk_freq", ctn_name)
    user_clk_freq = validate_float(config, "user_clk_freq", ctn_name)
    iodelay_clk_freq = validate_float(config, "iodelay_clk_freq", ctn_name)

    # core
    cmd_buffer_depth = validate_int(config, "cmd_buffer_depth", ctn_name)

    # user ports
    user_ports = validate_exists(config, "user_ports", ctn_name)

    # geometry (always required)
    geom_def = validate_exists(config, "dram_geometry", ctn_name)

    required_fields = [
        fpga_speedgrade, mem_type, extra_cmd_latency, num_byte_groups,
        num_ranks, phy, input_clk_freq, user_clk_freq, iodelay_clk_freq,
        cmd_buffer_depth, user_ports, geom_def
    ]
    if None in required_fields:
        sys.exit(1)

    geom = validate_geometry(geom_def)

    # check only supported memory type is used
    if mem_type not in SUPPORTED_MEM_TYPES:
        print("ERROR: Only these types of memory are currently supported: "
              f"{SUPPORTED_MEM_TYPES}")
        sys.exit(1)

    # check only supported port types are used
    invalid_port = False
    for port in user_ports.values():
        if port.get("type", None) not in SUPPORTED_USER_PORT_TYPES:
            invalid_port = True
            print("ERROR: Only these types of user ports are currently "
                 f"supported: {SUPPORTED_USER_PORT_TYPES}")
    if invalid_port:
        sys.exit(1)

    litedram_config = {
        # general
        "speedgrade": fpga_speedgrade,
        "cpu": "None",
        "memtype": mem_type,
        "uart": "None",

        # PHY
        "cmd_latency": extra_cmd_latency,
        "sdram_module_nb": num_byte_groups,
        "sdram_rank_nb": num_ranks,
        "sdram_phy": phy,

        # electrical
        "rtt_nom": rtt_nom,
        "rtt_wr": rtt_wr,
        "ron": ron,

        # frequency
        "input_clk_freq": input_clk_freq,
        "sys_clk_freq": user_clk_freq,
        "iodelay_clk_freq": iodelay_clk_freq,

        # core
        "cmd_buffer_depth": cmd_buffer_depth,

        # user ports
        "user_ports": user_ports,
    }

    module_def = validate_exists(config, "dram_module", ctn_name)
    if isinstance(module_def, dict):
        # custom
        custom_class = create_custom_module(module_def, mem_type, geom)
        module_name = custom_class.__name__
        setattr(litedram_modules, module_name, custom_class)
        litedram_config["sdram_module"] = module_name
    else:
        # built-in
        litedram_config["sdram_module"] = module_def

    return litedram_config


class LiteDramGen(Generator):
    def run(self):
        config_file = self.config.get("config_file", None)
        if not config_file:
            print(f"ERROR: `config_file` is a required parameter")
            sys.exit(1)

        in_config_path = Path(self.files_root) / config_file
        in_config = yaml.safe_load(in_config_path.read_text())

        litex_name = in_config.get("name", "litedram_core")
        litedram_config = translate_config(in_config)

        output_dir = Path("litex_build")
        verilog_path = output_dir / "gateware" / f"{litex_name}.v"
        xdc_path = output_dir / "gateware" / f"{litex_name}.xdc"

        out_config_path = Path("litedram_config.yml")
        out_config_path.write_text(yaml.dump(litedram_config))

        command = [
            "litedram_gen",
            "--no-compile",
            "--name", litex_name,
            "--output-dir", output_dir.resolve().as_posix(),
            out_config_path.resolve().as_posix(),
        ]

        log_file = output_dir / "litedram_gen.log"
        output_dir.mkdir(parents=True, exist_ok=True)
        with open(log_file, "w") as f:
            try:
                subprocess.check_call(command, stdout=f, stderr=subprocess.STDOUT)
            except subprocess.CalledProcessError:
                print("ERROR: litedram_gen failed")
                print(f"See log: {log_file.resolve().as_posix()}")
                sys.exit(1)
            except FileNotFoundError:
                print("ERROR: litedram_gen command not found. "
                        "Is litex installed and on PATH?")
                sys.exit(1)

        if not verilog_path.is_file():
            print("ERROR: litedram_gen failed, output verilog not found:")
            print(f"       {verilog_path.resolve().as_posix()}")
            print(f"See log: {log_file.resolve().as_posix()}")
            sys.exit(1)

        if not xdc_path.is_file():
            print("ERROR: litedram_gen failed, output constraints not found:")
            print(f"       {xdc_path.resolve().as_posix()}")
            print(f"See log: {log_file.resolve().as_posix()}")
            sys.exit(1)

        self.add_files(
            [verilog_path.resolve().as_posix()],
            fileset="rtl",
            file_type="verilogSource"
        )
        self.add_files(
            [xdc_path.resolve().as_posix()],
            fileset="xdc",
            file_type="xdc"
        )

        print(f"[{litex_name}] LiteDRAM generation completed")


if __name__ == "__main__":
    generator = LiteDramGen()
    generator.run()
    generator.write()
