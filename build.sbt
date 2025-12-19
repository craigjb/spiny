ThisBuild / version := "0.1"
ThisBuild / scalaVersion := "2.13.14"
ThisBuild / organization := "com.craigjb"

val spinalVersion = "1.13.0"
val spinalCore = "com.github.spinalhdl" %% "spinalhdl-core" % spinalVersion
val spinalLib = "com.github.spinalhdl" %% "spinalhdl-lib" % spinalVersion
val spinalIdslPlugin = compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % spinalVersion)

lazy val vexRiscv = RootProject(uri("https://github.com/SpinalHDL/VexRiscv.git#master"))

lazy val spiny = (project in file("."))
  .settings(
    name := "spiny", 
    scalaVersion := "2.13.12",
    Compile / scalaSource := baseDirectory.value / "spinal",
    libraryDependencies ++= Seq(spinalCore, spinalLib, spinalIdslPlugin),
    excludeDependencies += "com.github.spinalhdl" % "vexriscv_2.13"
  )
  .dependsOn(vexRiscv)

fork := true
