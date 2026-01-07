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


class Makefile(Generator):
    def run(self):
        make_dir = self.config.get("make_dir", ".")
        target = self.config.get("target", "")

        working_dir = Path(self.files_root) / Path(make_dir)
        command = ["make"]
        if target:
            command.append(target)

        try:
            subprocess.check_call(command, cwd=working_dir)
        except subprocess.CalledProcessError:
            print("ERROR: Makefile failed")
            sys.exit(1)


if __name__ == "__main__":
    generator = Makefile()
    generator.run()
