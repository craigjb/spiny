ThisBuild / version := "0.1"
ThisBuild / scalaVersion := "2.13.14"
ThisBuild / organization := "com.craigjb"

val spinalVersion = "1.13.0"
val spinalCore = "com.github.spinalhdl" %% "spinalhdl-core" % spinalVersion
val spinalLib = "com.github.spinalhdl" %% "spinalhdl-lib" % spinalVersion
val spinalIdslPlugin = compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % spinalVersion)

lazy val spinaltools = (project in file("."))
  .settings(
    name := "spinaltools", 
    Compile / scalaSource := baseDirectory.value / "src" / "spinal",
    libraryDependencies ++= Seq(spinalCore, spinalLib, spinalIdslPlugin)
  )

fork := true
