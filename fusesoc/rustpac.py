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
import subprocess
import shutil
import textwrap
import hashlib
import json
from pathlib import Path

from fusesoc.capi2.generator import Generator


BUILD_RS_CONTENT = textwrap.dedent("""\
    use std::env;
    use std::fs::File;
    use std::io::Write;
    use std::path::PathBuf;

    fn main() {
        let out = &PathBuf::from(env::var_os("OUT_DIR").unwrap());

        File::create(out.join("pac.x"))
            .unwrap()
            .write_all(include_bytes!("pac.x"))
            .unwrap();

        File::create(out.join("device.x"))
            .unwrap()
            .write_all(include_bytes!("device.x"))
            .unwrap();

        println!("cargo:rustc-link-search={}", out.display());

        println!("cargo:rustc-link-arg=-Tpac.x");
        println!("cargo:rustc-link-arg=-Tdevice.x");
        println!("cargo:rustc-link-arg=-Tlink.x");

        println!("cargo:rerun-if-changed=pac.x");
        println!("cargo:rerun-if-changed=device.x");
        println!("cargo:rerun-if-changed=build.rs");
    }
""")


class RustPacGen(Generator):
    def get_file_hash(self, path):
        if not path or not path.is_file():
            return None
        h = hashlib.sha256()
        with open(path, "rb") as f:
            for chunk in iter(lambda: f.read(65536), b""):
                h.update(chunk)
        return h.hexdigest()

    def run_svd2rust(self, files_root, svd_src_path):
        if not svd_src_path.is_file():
            print("ERROR: SVD input does not exist or is not a file")
            print(f"(expected here: {svd_src_path.resolve().as_posix()}")
            sys.exit(1)

        try:
            subprocess.check_call([
                "svd2rust",
                "-i", svd_src_path.resolve().as_posix(),
                "--target", "riscv"
            ])
        except subprocess.CalledProcessError:
            print("ERROR: svd2rust failed")
            print("(make sure it's installed and on PATH)")
            sys.exit(1)

        lib_rs_path = Path("lib.rs")
        if not lib_rs_path.is_file():
            print("ERROR: svd2rust failed to generate lib.rs")
            print(f"(expected here: {lib_rs_path.resolve().as_posix()})")
            sys.exit(1)

        device_x_path = Path("device.x")
        if not device_x_path.is_file():
            print("ERROR: svd2rust failed to generate device.x")
            print(f"(expected here: {device_x_path.resolve().as_posix()})")
            sys.exit(1)

        return lib_rs_path, device_x_path

    def run_form(self, lib_rs_path):
        src_path = Path("src")
        if src_path.exists():
            shutil.rmtree(src_path)
        src_path.mkdir(parents=True)

        try:
            subprocess.check_call([
                "form",
                "-i", lib_rs_path.resolve().as_posix(),
                "-o", src_path.resolve().as_posix()
            ])
        except subprocess.CalledProcessError:
            print("ERROR: form failed")
            print("(make sure it's installed and on PATH)")
            sys.exit(1)

        if not any(src_path.iterdir()):
            print("ERROR: src directory output from form is empty")
            sys.exit(1)

        return src_path

    def run_rustfmt(self, src_path):
        src_lib_rs_path = src_path / "lib.rs"
        if not src_lib_rs_path.is_file():
            print("ERROR: src/lib.rs is missing for rustfmt")
            sys.exit(1)

        try:
            subprocess.check_call(
                ["rustfmt", src_lib_rs_path.resolve().as_posix()]
            )
        except subprocess.CalledProcessError:
            print("ERROR: rustfmt failed")
            print("(make sure it's installed and on PATH)")
            sys.exit(1)

    def generate_cargo_toml(self, crate_name, crate_version):
        content = textwrap.dedent(f"""\
            [package]
            name = "{crate_name}"
            version = "{crate_version}"
            edition = "2021"

            [dependencies]
            critical-section = {{ version = "1.2.0", optional = true }}
            vcell = "0.1.3"

            [features]
            rt = []
        """)
        return content

    def run(self):
        crate_name = self.config.get("crate_name")
        crate_version = self.config.get("crate_version")
        output_path = self.config.get("output_path")
        svd_path = self.config.get("svd_path")
        linker_script_path = self.config.get("linker_script_path")

        missing_parameter = False
        if not crate_name:
            print("ERROR: 'crate_name' is a required parameter")
            missing_parameter = True
        if not crate_version:
            print("ERROR: 'crate_version' is a required parameter")
            missing_parameter = True
        if not output_path:
            print("ERROR: 'output_path' is a required parameter")
            missing_parameter = True
        if not svd_path:
            print("ERROR: 'svd_path' is a required parameter")
            missing_parameter = True
        if missing_parameter:
            sys.exit(1)

        files_root = Path(self.files_root)
        output_path = files_root / output_path
        svd_src_path = files_root / svd_path
        if linker_script_path:
            linker_script_src = files_root / linker_script_path
        else:
            linker_script_src = None

        current_hashes = {
            "svd": self.get_file_hash(svd_src_path),
            "linker_script": self.get_file_hash(linker_script_src),
            "crate_name": crate_name,
            "crate_version": crate_version
        }

        should_run = True
        state_file = output_path / ".generator_state.json"
        if (state_file.exists() and 
                (output_path / "src").exists() and 
                (output_path / "Cargo.toml").exists() and 
                (output_path / "build.rs").exists()):
            try:
                saved_state = json.loads(state_file.read_text())
                if saved_state == current_hashes:
                    print(f"[{crate_name}] Inputs unchanged. Skipping generation.")
                    should_run = False
            except (json.JSONDecodeError, KeyError):
                pass

        if not should_run:
            return

        # generate PAC src files and format
        lib_rs_path, device_x_path = self.run_svd2rust(
            files_root, svd_src_path)
        src_path = self.run_form(lib_rs_path)
        self.run_rustfmt(src_path)

        # copy src and device.x to output crate
        dest_src_path = output_path / "src"
        if dest_src_path.exists():
            shutil.rmtree(dest_src_path)
        shutil.copytree(src_path, dest_src_path)
        shutil.copy2(device_x_path, output_path / "device.x")

        # optional linker script
        if linker_script_src:
            shutil.copy2(linker_script_src, output_path / "pac.x")

        (output_path / "Cargo.toml").write_text(
            self.generate_cargo_toml(crate_name, crate_version))
        (output_path / "build.rs").write_text(BUILD_RS_CONTENT)
        state_file.write_text(json.dumps(current_hashes))


if __name__ == "__main__":
    generator = RustPacGen()
    generator.run()
