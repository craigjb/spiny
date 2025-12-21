ThisBuild / version := "0.1"
ThisBuild / scalaVersion := "2.12.18"
ThisBuild / organization := "com.craigjb"
ThisBuild / excludeDependencies += "com.github.spinalhdl" % "vexriscv_2.13"
ThisBuild / fork := true

val spinalVersion = "1.13.0"
val spinalCore = "com.github.spinalhdl" %% "spinalhdl-core" % spinalVersion
val spinalLib = "com.github.spinalhdl" %% "spinalhdl-lib" % spinalVersion
val spinalIdslPlugin = compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % spinalVersion)

lazy val vexRiscv = RootProject(uri("https://github.com/SpinalHDL/VexRiscv.git#master"))

lazy val spiny = (project in file("."))
  .dependsOn(vexRiscv)
  .settings(
    name := "spiny", 
    Compile / scalaSource := baseDirectory.value / "spinal",
    libraryDependencies ++= Seq(spinalCore, spinalLib, spinalIdslPlugin),
  )

// Example projects
lazy val blinky = (project in file("examples/blinky"))
  .dependsOn(spiny) // Link to your main library
  .dependsOn(vexRiscv)
  .settings(
    name := "blinky",
    Compile / scalaSource := baseDirectory.value / "spinal",
    publish / skip := true,
    libraryDependencies ++= Seq(spinalIdslPlugin),
  )
