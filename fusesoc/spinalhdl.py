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

import subprocess
import shutil
import sys
from pathlib import Path

from fusesoc.capi2.generator import Generator


class SpinalHdlGen(Generator):
    def run(self):
        sbt_dir = self.config.get("sbt_dir")
        project = self.config.get("project", None)
        main = self.config.get("main")
        output_path = self.config.get("output_path")
        file_type = self.config.get("file_type")
        args = self.config.get("args")

        if not sbt_dir:
            sbt_dir = self.files_root
        missing_parameter = False
        if not main:
            print("ERROR: 'main' is a required parameter")
            missing_parameter = True
        if output_path and not file_type:
            print("ERROR: 'file_type' is a required parameter " + 
                "if 'output_path' is set")
            missing_parameter = True
        if missing_parameter:
            sys.exit(1)

        if project is not None:
            project_prefix = f"{project}/"
        else:
            project_prefix = ""

        working_dir = Path(self.files_root) / Path(sbt_dir)
        command = ["sbtn", f"{project_prefix}runMain", main]
        if args:
            command += args

        try:
            subprocess.check_call(command, cwd=working_dir)
        except subprocess.CalledProcessError:
            print("ERROR: SpinalHDL generation failed")
            sys.exit(1)
        except FileNotFoundError:
            print("ERROR: 'sbtn' command not found. Is sbt installed?")
            sys.exit(1)

        if output_path:
            src_rtl_path = Path(self.files_root) / output_path
            dest_rtl_file = Path(output_path).name

            if not src_rtl_path.exists():
                print(f"ERROR: Generated file not found at {output_path}")
                sys.exit(1)
            shutil.copy(src_rtl_path, dest_rtl_file)

            self.add_files(
                [dest_rtl_file],
                fileset="rtl",
                file_type=file_type
            )

if __name__ == "__main__":
    generator = SpinalHdlGen()
    generator.run()
    generator.write()
