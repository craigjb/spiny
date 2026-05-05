ThisBuild / version := "0.1"
ThisBuild / scalaVersion := "2.12.18"
ThisBuild / organization := "com.craigjb"
ThisBuild / excludeDependencies += "com.github.spinalhdl" % "vexriscv_2.13"
ThisBuild / fork := true

val spinalVersion = "1.13.0"
val spinalCore = "com.github.spinalhdl" %% "spinalhdl-core" % spinalVersion
val spinalLib = "com.github.spinalhdl" %% "spinalhdl-lib" % spinalVersion
val spinalIdslPlugin = compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % spinalVersion)
val scallop = "org.rogach" %% "scallop" % "6.0.0"

lazy val vexRiscv = RootProject(uri("https://github.com/SpinalHDL/VexRiscv.git#master"))

lazy val spiny = (project in file("."))
  .dependsOn(vexRiscv)
  .settings(
    name := "spiny",
    Compile / scalaSource := baseDirectory.value / "spinal",
    libraryDependencies ++= Seq(
      spinalCore,
      spinalLib,
      spinalIdslPlugin,
      "org.scala-lang.modules" %% "scala-xml" % "2.1.0",
      "org.yaml" % "snakeyaml" % "2.0",
      "com.lihaoyi" %% "ujson" % "3.3.1"
    )
  )

// Example projects
lazy val blinky = (project in file("examples/blinky"))
  .dependsOn(spiny)
  .dependsOn(vexRiscv)
  .settings(
    name := "blinky",
    Compile / scalaSource := baseDirectory.value / "spinal",
    publish / skip := true,
    libraryDependencies ++= Seq(spinalIdslPlugin, scallop),
  )

lazy val ddrtest = (project in file("examples/ddrtest"))
  .dependsOn(spiny)
  .dependsOn(vexRiscv)
  .settings(
    name := "ddrtest",
    Compile / scalaSource := baseDirectory.value / "spinal",
    publish / skip := true,
    libraryDependencies ++= Seq(spinalIdslPlugin, scallop),
  )

lazy val hdmitest = (project in file("examples/hdmitest"))
  .dependsOn(spiny)
  .settings(
    name := "hdmitest",
    Compile / scalaSource := baseDirectory.value / "spinal",
    publish / skip := true,
    libraryDependencies ++= Seq(spinalIdslPlugin, scallop),
  )

lazy val graphics2d = (project in file("examples/graphics2d"))
  .dependsOn(spiny)
  .settings(
    name := "graphics2d",
    Compile / scalaSource := baseDirectory.value / "spinal",
    publish / skip := true,
    libraryDependencies ++= Seq(spinalIdslPlugin, scallop),
  )
