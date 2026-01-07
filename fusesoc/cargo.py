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
from pathlib import Path

from fusesoc.capi2.generator import Generator


class CargoGen(Generator):
    def run(self):
        project_dir = self.config.get("project_dir", ".")
        args = self.config.get("args")

        if not args:
            print("ERROR: 'args' is a required parameter")
            sys.exit(1)

        files_root = Path(self.files_root)
        cargo_cwd = files_root / project_dir

        if not (cargo_cwd / "Cargo.toml").exists():
            print(f"ERROR: Cargo.toml not found in {cargo_cwd}")
            sys.exit(1)

        command = ["cargo"] + args

        print(f"Running cargo in: {cargo_cwd}")
        print(f"Command: {' '.join(command)}")

        try:
            subprocess.check_call(command, cwd=cargo_cwd)
        except subprocess.CalledProcessError as e:
            print(f"ERROR: Cargo failed with return code {e.returncode}")
            sys.exit(1)
        except FileNotFoundError:
            print("ERROR: 'cargo' command not found. Is Rust installed?")
            sys.exit(1)


if __name__ == "__main__":
    generator = CargoGen()
    generator.run()
