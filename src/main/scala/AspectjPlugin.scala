package com.typesafe.sbtaspectj

import sbt._
import Configurations.Compile
import Keys._

import java.io.File

object AspectjPlugin {
  case class Mapping(in: File, aspects: Seq[File], out: File)

  val Aspectj = config("aspectj") hide

  val aspectjVersion = SettingKey[String]("aspectj-version")
  val aspectjDirectory = SettingKey[File]("aspectj-directory")
  val outputDirectory = SettingKey[File]("output-directory")

  val showWeaveInfo = SettingKey[Boolean]("show-weave-info")
  val verbose = SettingKey[Boolean]("verbose")
  val sourceLevel = SettingKey[String]("source-level")

  val aspectFilter = SettingKey[(File, Seq[File]) => Seq[File]]("aspect-filter")

  val aspectjClasspath = TaskKey[Classpath]("aspectj-classpath")
  val baseOptions = TaskKey[Seq[String]]("base-options")

  val inputs = TaskKey[Seq[File]]("inputs")
  val sources = TaskKey[Seq[File]]("sources")
  val aspectMappings = TaskKey[Seq[Mapping]]("aspect-mappings")

  val weave = TaskKey[Seq[File]]("weave", "Run the AspectJ compiler.")

  lazy val settings: Seq[Setting[_]] = inConfig(Aspectj)(aspectjSettings) ++ dependencySettings

  def aspectjSettings = Seq(
    aspectjVersion := "1.6.11",
    aspectjDirectory <<= sourceDirectory(_ / "main" / "aspectj"),
    outputDirectory <<= crossTarget / "aspectj",
    showWeaveInfo := false,
    verbose := false,
    sourceLevel := "-1.5",
    managedClasspath <<= (configuration, classpathTypes, update) map Classpaths.managedJars,
    dependencyClasspath <<= fullClasspath in Compile,
    aspectjClasspath <<= (managedClasspath, dependencyClasspath) map { _ ++ _ },
    baseOptions <<= ajcBaseOptions,
    inputs := Seq.empty,
    sources <<= aspectjDirectory map { dir => (dir ** "*.aj").get },
    aspectFilter := { (f, a) => a },
    aspectMappings <<= mapAspects,
    weave <<= weaveTask)

  def dependencySettings = Seq(
    ivyConfigurations += Aspectj,
    resolvers += "Typesafe Repo" at "http://repo.typesafe.com/typesafe/releases/",
    libraryDependencies <+= (aspectjVersion in Aspectj)("org.aspectj" % "aspectjtools" % _ % Aspectj.name),
    libraryDependencies <+= (aspectjVersion in Aspectj)("org.aspectj" % "aspectjrt" % _))

  def ajcBaseOptions = (showWeaveInfo, verbose, sourceLevel) map {
    (info, verbose, level) => {
      (if (info) Seq("-showWeaveInfo") else Seq.empty[String]) ++
      (if (verbose) Seq("-verbose") else Seq.empty[String]) ++
      Seq(level)
    }
  }

  def mapAspects = (inputs, sources, aspectFilter, outputDirectory) map {
    (jars, aspects, filter, dir) => {
      jars map { jar => Mapping(jar, filter(jar, aspects), instrumented(jar, dir)) }
    }
  }

  def instrumented(jar: File, outputDir: File): File = {
    val (base, ext) = jar.baseAndExt
    val outputName = base + "-instrumented" + "." + ext
    new File(outputDir, outputName)
  }

  def weaveTask = (cacheDirectory, aspectMappings, baseOptions, aspectjClasspath, streams) map {
    (cache, mappings, options, cp, s) => {
      val cached = FileFunction.cached(cache / "aspectj", FilesInfo.hash) { _ =>
        val withPrevious = mappings.zipWithIndex map { case (m, i) => (mappings.take(i), m) }
        (withPrevious map { case (previousMappings, mapping) =>
          val classpath = insertInstrumentedJars(cp, previousMappings)
          val classpathOption = Seq("-classpath", classpath.files.absString)
          runAjc(mapping.in, mapping.aspects, mapping.out, options ++ classpathOption, s.log)
          mapping.out
        }).toSet
      }
      val cacheInputs = mappings.flatMap( mapping => {
        mapping.in +: mapping.aspects
       }).toSet
      cached(cacheInputs).toSeq
    }
  }

  def runAjc(jar: File, aspects: Seq[File], outputJar: File, baseOptions: Seq[String], log: Logger) = {
    IO.createDirectory(outputJar.getParentFile)
    if (aspects.isEmpty) {
      log.info("No aspects for %s" format jar)
      log.info("Copying jar to %s" format outputJar)
      IO.copyFile(jar, outputJar, false)
    } else {
      log.info("Weaving %s with aspects:" format jar)
      aspects foreach { f => log.info("  " + f.absolutePath) }
      log.info("to %s" format outputJar)
      val ajc = new org.aspectj.tools.ajc.Main
      val options = ajcOptions(jar, aspects, outputJar, baseOptions).toArray
      ajc.runMain(options, false)
    }
  }

  def ajcOptions(in: File, aspects: Seq[File], out: File, baseOptions: Seq[String]): Seq[String] = {
    baseOptions ++
    Seq("-inpath", in.absolutePath, "-outjar", out.absolutePath) ++
    aspects.map(_.absolutePath)
  }

  def useInstrumentedJars(config: Configuration) = {
    (fullClasspath in config, aspectMappings in Aspectj, weave in Aspectj) map {
      (cp, mappings, woven) => insertInstrumentedJars(cp, mappings)
    }
  }

  def insertInstrumentedJars(classpath: Classpath, mappings: Seq[Mapping]) = {
    classpath map { a => mappings.find(_.in == a.data).map(_.out).map(Attributed.blank).getOrElse(a) }
  }
}