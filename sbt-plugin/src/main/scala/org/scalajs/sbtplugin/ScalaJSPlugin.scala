/*                     __                                               *\
**     ________ ___   / /  ___      __ ____  Scala.js sbt plugin        **
**    / __/ __// _ | / /  / _ | __ / // __/  (c) 2013, LAMP/EPFL        **
**  __\ \/ /__/ __ |/ /__/ __ |/_// /_\ \    http://scala-js.org/       **
** /____/\___/_/ |_/____/_/ | |__/ /____/                               **
**                          |/____/                                     **
\*                                                                      */


package org.scalajs.sbtplugin

import scala.language.implicitConversions

import sbt._
import sbt.Keys._

import org.scalajs.core.tools.sem.Semantics
import org.scalajs.core.tools.io._
import org.scalajs.core.tools.linker._
import org.scalajs.core.tools.linker.standard._
import org.scalajs.core.tools.jsdep.{JSDependencyManifest, ResolvedJSDependency}
import org.scalajs.core.tools.jsdep.ManifestFilters.ManifestFilter
import org.scalajs.core.tools.jsdep.DependencyResolver.DependencyFilter

import org.scalajs.core.ir.ScalaJSVersions

import org.scalajs.jsenv.{JSEnv, JSConsole}
import org.scalajs.jsenv.rhino.RhinoJSEnv
import org.scalajs.jsenv.nodejs.NodeJSEnv
import org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv
import org.scalajs.jsenv.phantomjs.PhantomJSEnv

object ScalaJSPlugin extends AutoPlugin {
  override def requires: Plugins = plugins.JvmPlugin

  /* The following module-case double definition is a workaround for a bug
   * somewhere in the sbt dependency macro - scala macro pipeline that affects
   * the %%% operator on dependencies (see #1331).
   *
   * If the object AutoImport is written lower-case, it is wrongly identified as
   * dynamic dependency (only if the usage code is generated by a macro). On the
   * other hand, only lower-case autoImport is automatically imported by sbt (in
   * an AutoPlugin, therefore the alias.
   *
   * We do not know *why* this fixes the issue, but it does.
   */
  val autoImport = AutoImport

  object AutoImport extends impl.DependencyBuilders
                       with cross.CrossProjectExtra {
    import KeyRanks._

    // Some constants
    val scalaJSVersion = ScalaJSVersions.current
    val scalaJSIsSnapshotVersion = ScalaJSVersions.currentIsSnapshot
    val scalaJSBinaryVersion = ScalaJSCrossVersion.currentBinaryVersion

    // Stage values
    @deprecated("Use FastOptStage instead", "0.6.6")
    val PreLinkStage = Stage.FastOpt
    val FastOptStage = Stage.FastOpt
    val FullOptStage = Stage.FullOpt

    // CrossType
    @deprecated(
        "The built-in cross-project feature of sbt-scalajs is deprecated. " +
        "Use the separate sbt plugin sbt-crossproject instead: " +
        "https://github.com/portable-scala/sbt-crossproject",
        "0.6.23")
    lazy val CrossType = cross.CrossType

    // Factory methods for JSEnvs

    /** A non-deprecated version of `RhinoJSEnv` for internal use. */
    private[sbtplugin]
    def RhinoJSEnvInternal(): Def.Initialize[Task[RhinoJSEnv]] = Def.task {
      /* We take the semantics from the linker, since they depend on the stage.
       * This way we are sure we agree on the semantics with the linker.
       */
      import ScalaJSPluginInternal.scalaJSRequestsDOMInternal
      val semantics = scalaJSLinker.value.semantics
      val withDOM = scalaJSRequestsDOMInternal.value
      new RhinoJSEnv(semantics, withDOM, internal = ())
    }

    /** Creates a [[sbt.Def.Initialize Def.Initialize]] for a [[RhinoJSEnv]].
     *
     *  Use this to explicitly specify in your build that you would like to run
     *  with Rhino:
     *
     *  {{{
     *  Seq(Compile, Test).flatMap(c => inConfig(c)(jsEnv := RhinoJSEnv().value))
     *  }}}
     *
     *  The Rhino JS environment will support DOM through `env.js` if and only
     *  if `scalaJSRequestsDOM.value` evaluates to `true`.
     *
     *  Note that the resulting [[sbt.Def.Setting Setting]] must be scoped in a
     *  project that has the `ScalaJSPlugin` enabled to work properly.
     *  Therefore, either put the upper line in your project settings (common
     *  case) or scope it manually, using
     *  [[sbt.ProjectExtra.inScope[* Project.inScope]].
     */
    @deprecated(
        "The Rhino JS environment is being phased out. " +
        "It will be removed in Scala.js 1.0.0. ",
        "0.6.13")
    def RhinoJSEnv(): Def.Initialize[Task[RhinoJSEnv]] = RhinoJSEnvInternal()

    /**
     *  Creates a [[sbt.Def.Initialize Def.Initialize]] for a NodeJSEnv. Use
     *  this to explicitly specify in your build that you would like to run with Node.js:
     *
     *  {{{
     *  jsEnv := NodeJSEnv().value
     *  }}}
     *
     *  Note that the resulting [[sbt.Def.Setting Setting]] is not scoped at
     *  all, but must be scoped in a project that has the ScalaJSPlugin enabled
     *  to work properly.
     *  Therefore, either put the upper line in your project settings (common
     *  case) or scope it manually, using
     *  [[sbt.ProjectExtra.inScope[* Project.inScope]].
     */
    @deprecated(
        "Use `jsEnv := new org.scalajs.jsenv.nodejs.NodeJSEnv(...)` instead.",
        "0.6.16")
    def NodeJSEnv(
        executable: String = "node",
        args: Seq[String] = Seq.empty,
        env: Map[String, String] = Map.empty
    ): Def.Initialize[Task[NodeJSEnv]] = Def.task {
      new NodeJSEnv(
          org.scalajs.jsenv.nodejs.NodeJSEnv.Config()
            .withExecutable(executable)
            .withArgs(args.toList)
            .withEnv(env))
    }

    /**
     *  Creates a [[sbt.Def.Initialize Def.Initialize]] for a JSDOMNodeJSEnv. Use
     *  this to explicitly specify in your build that you would like to run with
     *  Node.js on a JSDOM window:
     *
     *  {{{
     *  jsEnv := JSDOMNodeJSEnv().value
     *  }}}
     *
     *  Note that the resulting [[sbt.Def.Setting Setting]] is not scoped at
     *  all, but must be scoped in a project that has the ScalaJSPlugin enabled
     *  to work properly.
     *  Therefore, either put the upper line in your project settings (common
     *  case) or scope it manually, using
     *  [[sbt.ProjectExtra.inScope[* Project.inScope]].
     */
    @deprecated(
        "Use `jsEnv := new org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv(...)` " +
        "instead.",
        "0.6.16")
    def JSDOMNodeJSEnv(
        executable: String = "node",
        args: Seq[String] = Seq.empty,
        env: Map[String, String] = Map.empty
    ): Def.Initialize[Task[JSDOMNodeJSEnv]] = Def.task {
      new JSDOMNodeJSEnv(
          org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv.Config()
            .withExecutable(executable)
            .withArgs(args.toList)
            .withEnv(env))
    }

    /** Creates a [[sbt.Def.Initialize Def.Initialize]] for a PhantomJSEnv.
     *
     *  Use this to explicitly specify in your build that you would like to run
     *  with PhantomJS:
     *
     *  {{{
     *  jsEnv := PhantomJSEnv(...).value
     *  }}}
     *
     *  The specified `Config` is augmented with an appropriate Jetty class
     *  loader (through `withJettyClassLoader`).
     *
     *  Note that the resulting [[sbt.Def.Setting Setting]] is not scoped at
     *  all, but must be scoped in a project that has the ScalaJSPlugin enabled
     *  to work properly.
     *  Therefore, either put the upper line in your project settings (common
     *  case) or scope it manually, using
     *  [[sbt.ProjectExtra.inScope[* Project.inScope]].
     */
    def PhantomJSEnv(
        config: org.scalajs.jsenv.phantomjs.PhantomJSEnv.Config
    ): Def.Initialize[Task[PhantomJSEnv]] = Def.task {
      val loader = scalaJSPhantomJSClassLoader.value
      new PhantomJSEnv(config.withJettyClassLoader(loader))
    }

    /** Creates a [[sbt.Def.Initialize Def.Initialize]] for a PhantomJSEnv
     *  with the default configuration.
     *
     *  This is equivalent to
     *  {{{
     *  PhantomJSEnv(org.scalajs.jsenv.phantomjs.PhantomJSEnv.Config())
     *  }}}
     */
    def PhantomJSEnv(): Def.Initialize[Task[PhantomJSEnv]] =
      PhantomJSEnv(org.scalajs.jsenv.phantomjs.PhantomJSEnv.Config())

    /** Creates a [[sbt.Def.Initialize Def.Initialize]] for a PhantomJSEnv. */
    @deprecated("Use the overload with a PhantomJSEnv.Config.", "0.6.20")
    def PhantomJSEnv(
        executable: String = "phantomjs",
        args: Seq[String] = Seq.empty,
        env: Map[String, String] = Map.empty,
        autoExit: Boolean = true
    ): Def.Initialize[Task[PhantomJSEnv]] = {
      PhantomJSEnv(
          org.scalajs.jsenv.phantomjs.PhantomJSEnv.Config()
            .withExecutable(executable)
            .withArgs(args.toList)
            .withEnv(env)
            .withAutoExit(autoExit))
    }

    // ModuleKind
    val ModuleKind = org.scalajs.core.tools.linker.backend.ModuleKind

    // All our public-facing keys

    val isScalaJSProject = SettingKey[Boolean]("isScalaJSProject",
        "Tests whether the current project is a Scala.js project. " +
        "Do not set the value of this setting (only use it as read-only).",
        BSetting)

    // This is lazy to avoid initialization order issues
    lazy val scalaJSIRCache = TaskKey[ScalaJSPluginInternal.globalIRCache.Cache](
        "scalaJSIRCache",
        "Scala.js internal: Task to access a cache.", KeyRanks.Invisible)

    /** Persisted instance of the Scala.js linker.
     *
     *  This setting must be scoped per project, configuration, and stage task
     *  (`fastOptJS` or `fullOptJS`).
     *
     *  If a task uses the `link` method of the `ClearableLinker`, it must be
     *  protected from running in parallel with any other task doing the same
     *  thing, by tagging the task with the value of [[usesScalaJSLinkerTag]]
     *  in the same scope. The typical shape of such a task will be:
     *  {{{
     *  myTask in (Compile, fastOptJS) := Def.taskDyn {
     *    val linker = (scalaJSLinker in (Compile, fastOptJS)).value
     *    val usesLinkerTag = (usesScalaJSLinkerTag in (Compile, fastOptJS)).value
     *    // Read the `.value` of other settings and tasks here
     *
     *    Def.task {
     *      // Do the actual work of the task here, in particular calling
     *      linker.link(...)
     *    }.tag(usesLinkerTag)
     *  }.value,
     *  }}}
     */
    val scalaJSLinker = SettingKey[ClearableLinker]("scalaJSLinker",
        "Persisted instance of the Scala.js linker", KeyRanks.Invisible)

    /** A tag to indicate that a task is using the value of [[scalaJSLinker]]
     *  and its `link` method.
     *
     *  This setting's value should always be retrieved from the same scope
     *  than [[scalaJSLinker]] was retrieved from.
     *
     *  @see [[scalaJSLinker]]
     */
    val usesScalaJSLinkerTag = SettingKey[Tags.Tag]("usesScalaJSLinkerTag",
        "Tag to indicate that a task uses the link method of the value of " +
        "scalaJSLinker",
        KeyRanks.Invisible)

    val fastOptJS = TaskKey[Attributed[File]]("fastOptJS",
        "Quickly link all compiled JavaScript into a single file", APlusTask)

    val fullOptJS = TaskKey[Attributed[File]]("fullOptJS",
        "Link all compiled JavaScript into a single file and fully optimize", APlusTask)

    val testHtmlFastOpt = TaskKey[Attributed[File]]("testHtmlFastOpt",
        "Create an HTML test runner for fastOptJS", AMinusTask)

    val testHtmlFullOpt = TaskKey[Attributed[File]]("testHtmlFullOpt",
        "Create an HTML test runner for fullOptJS", AMinusTask)

    val scalaJSIR = TaskKey[Attributed[Seq[VirtualScalaJSIRFile with RelativeVirtualFile]]](
        "scalaJSIR", "All the *.sjsir files on the classpath", CTask)

    val scalaJSModuleInitializers = TaskKey[Seq[ModuleInitializer]]("scalaJSModuleInitializers",
        "Module initializers of the Scala.js application, to be called when it starts.",
        AMinusTask)

    val scalaJSUseMainModuleInitializer = SettingKey[Boolean]("scalaJSUseMainModuleInitializer",
        "If true, adds the `mainClass` as a module initializer of the Scala.js module",
        APlusSetting)

    val scalaJSMainModuleInitializer = TaskKey[Option[ModuleInitializer]](
        "scalaJSMainModuleInitializer",
        "The main module initializer, used if " +
        "`scalaJSUseMainModuleInitializer` is true",
        CTask)

    val scalaJSLinkerConfig = SettingKey[StandardLinker.Config](
        "scalaJSLinkerConfig",
        "Configuration of the Scala.js linker",
        BPlusSetting)

    val scalaJSNativeLibraries = TaskKey[Attributed[Seq[VirtualJSFile with RelativeVirtualFile]]](
        "scalaJSNativeLibraries", "All the *.js files on the classpath", CTask)

    val scalaJSStage = SettingKey[Stage]("scalaJSStage",
        "The optimization stage at which run and test are executed", APlusSetting)

    /** Non-deprecated alias of `packageScalaJSLauncher` for internal use. */
    private[sbtplugin] val packageScalaJSLauncherInternal = TaskKey[Attributed[File]](
        "packageScalaJSLauncher",
        "Writes the persistent launcher file. Fails if the mainClass is ambigous",
        CTask)

    @deprecated(
        "The functionality of `packageScalaJSLauncher` has been superseded " +
        "by `scalaJSUseMainModuleInitializer`. Set the latter to `true` to " +
        "include what was previously the launcher directly inside the main " +
        ".js file generated by fastOptJS/fullOptJS.",
        "0.6.15")
    val packageScalaJSLauncher = packageScalaJSLauncherInternal

    val packageJSDependencies = TaskKey[File]("packageJSDependencies",
        "Packages all dependencies of the preLink classpath in a single file.", AMinusTask)

    val packageMinifiedJSDependencies = TaskKey[File]("packageMinifiedJSDependencies",
        "Packages minified version (if available) of dependencies of the preLink " +
        "classpath in a single file.", AMinusTask)

    val jsDependencyManifest = TaskKey[File]("jsDependencyManifest",
        "Writes the JS_DEPENDENCIES file.", DTask)

    val jsDependencyManifests = TaskKey[Attributed[Traversable[JSDependencyManifest]]](
        "jsDependencyManifests", "All the JS_DEPENDENCIES on the classpath", DTask)

    val scalaJSLinkedFile = TaskKey[VirtualJSFile]("scalaJSLinkedFile",
        "Linked Scala.js file. This is the result of fastOptJS or fullOptJS, " +
        "depending on the stage.", DTask)

    /** Non-deprecated alias of `scalaJSUseRhino` for internal use. */
    private[sbtplugin] val scalaJSLauncherInternal = TaskKey[Attributed[VirtualJSFile]](
        "scalaJSLauncher",
        "Code used to run. (Attributed with used class name)", DTask)

    @deprecated(
        "The functionality of `scalaJSLauncher` has been superseded by " +
        "`scalaJSUseMainModuleInitializer`. Set the latter to `true` to " +
        "include what was previously the launcher directly inside the main " +
        ".js file generated by fastOptJS/fullOptJS.",
        "0.6.15")
    val scalaJSLauncher = scalaJSLauncherInternal

    val scalaJSConsole = TaskKey[JSConsole]("scalaJSConsole",
        "The JS console used by the Scala.js runner/tester", DTask)

    /** Non-deprecated alias of `scalaJSUseRhino` for internal use. */
    private[sbtplugin] val scalaJSUseRhinoInternal = SettingKey[Boolean](
        "scalaJSUseRhino", "Whether Rhino should be used", KeyRanks.Invisible)

    @deprecated(
        "Will be removed in 1.0.0. " +
        "Note that Rhino is not used by default anymore, " +
        "so setting `scalaJSUseRhino` to `false` is redundant. " +
        "To enable Rhino anew, use " +
        "`Seq(Compile, Test).flatMap(c => inConfig(c)(jsEnv := RhinoJSEnv().value))`.",
        "0.6.13")
    val scalaJSUseRhino = scalaJSUseRhinoInternal

    val jsEnv = TaskKey[JSEnv]("jsEnv",
        "A JVM-like environment where Scala.js files can be run and tested.", AMinusTask)

    val resolvedJSEnv = TaskKey[JSEnv]("resolvedJSEnv",
        "The JSEnv used for execution. This equals the setting of jsEnv or a " +
        "reasonable default value if jsEnv is not set.", DTask)

    @deprecated("Use jsEnv instead.", "0.6.6")
    val preLinkJSEnv = jsEnv

    @deprecated("Use jsEnv instead.", "0.6.6")
    val postLinkJSEnv = jsEnv

    /** Non-deprecated alias of `requiresDOM` for internal use. */
    private[sbtplugin] val requiresDOMInternal = SettingKey[Boolean]("requiresDOM",
        "Whether this projects needs the DOM. Overrides anything inherited through dependencies.", AMinusSetting)

    @deprecated(
        "Requesting a DOM-enabled JS env with `jsDependencies += RuntimeDOM` " +
        "or `requiresDOM := true` will not be supported in Scala.js 1.x. " +
        "Instead, explicitly select a suitable JS with `jsEnv`, e.g., " +
        "`jsEnv := new org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv`.",
        "0.6.20")
    val requiresDOM = requiresDOMInternal

    val relativeSourceMaps = SettingKey[Boolean]("relativeSourceMaps",
        "Make the referenced paths on source maps relative to target path", BPlusSetting)

    val emitSourceMaps = SettingKey[Boolean]("emitSourceMaps",
        "Whether package and optimize stages should emit source maps at all", BPlusSetting)

    /** Non-deprecated alias of `scalaJSOutputWrapper` for internal use. */
    private[sbtplugin] val scalaJSOutputWrapperInternal = SettingKey[(String, String)](
        "scalaJSOutputWrapper",
        "Custom wrapper for the generated .js files. Formatted as tuple (header, footer).",
        BPlusSetting)

    @deprecated(
        "The functionality of `scalaJSOutputWrapper` has been superseded by " +
        "a combination of more direct and more reliable features. Depending " +
        "on your use case, use `scalaJSUseMainModuleInitializer`, " +
        "`scalaJSModuleKind` and/or `@JSExportTopLevel` instead.",
        "0.6.15")
    val scalaJSOutputWrapper = scalaJSOutputWrapperInternal

    val jsDependencies = SettingKey[Seq[AbstractJSDep]]("jsDependencies",
        "JavaScript libraries this project depends upon. Also used to depend on the DOM.", APlusSetting)

    val scalaJSSemantics = SettingKey[Semantics]("scalaJSSemantics",
        "Configurable semantics of Scala.js.", BPlusSetting)

    val scalaJSOutputMode = SettingKey[OutputMode]("scalaJSOutputMode",
        "Output mode of Scala.js.", BPlusSetting)

    val scalaJSModuleKind = SettingKey[ModuleKind]("scalaJSModuleKind",
        "Kind of JavaScript modules emitted by Scala.js.", BPlusSetting)

    val jsDependencyFilter = SettingKey[DependencyFilter]("jsDependencyFilter",
        "The filter applied to the raw JavaScript dependencies before execution", CSetting)

    val jsManifestFilter = SettingKey[ManifestFilter]("jsManifestFilter",
        "The filter applied to JS dependency manifests before resolution", CSetting)

    val resolvedJSDependencies = TaskKey[Attributed[Seq[ResolvedJSDependency]]]("resolvedJSDependencies",
        "JS dependencies after resolution.", DTask)

    val checkScalaJSSemantics = SettingKey[Boolean]("checkScalaJSSemantics",
        "Whether to check that the current semantics meet compliance " +
        "requirements of dependencies.", CSetting)

    /** Non-deprecated alias of `persistLauncher` for internal use. */
    private[sbtplugin] val persistLauncherInternal = SettingKey[Boolean]("persistLauncher",
        "Tell optimize/package tasks to write the laucher file to disk. " +
        "If this is set, your project may only have a single mainClass or you must explicitly set it", AMinusSetting)

    @deprecated(
        "The functionality of `persistLauncher` has been superseded by " +
        "`scalaJSUseMainModuleInitializer`. Set the latter to `true` to " +
        "include what was previously the launcher directly inside the main " +
        ".js file generated by fastOptJS/fullOptJS.",
        "0.6.15")
    val persistLauncher = persistLauncherInternal

    val scalaJSOptimizerOptions = SettingKey[OptimizerOptions]("scalaJSOptimizerOptions",
        "All kinds of options for the Scala.js optimizer stages", DSetting)

    val loadedJSEnv = TaskKey[JSEnv]("loadedJSEnv",
        "A JSEnv already loaded up with library and Scala.js code. Ready to run.", DTask)

    /** Class loader for PhantomJSEnv. Used to load jetty8. */
    val scalaJSPhantomJSClassLoader = TaskKey[ClassLoader]("scalaJSPhantomJSClassLoader",
        "Private class loader to load jetty8 without polluting classpath. Only use this " +
        "as the `jettyClassLoader` argument of the PhantomJSEnv",
        KeyRanks.Invisible)

    /** All .sjsir files on the fullClasspath, used by scalajsp. */
    val sjsirFilesOnClasspath = TaskKey[Seq[String]]("sjsirFilesOnClasspath",
        "All .sjsir files on the fullClasspath, used by scalajsp",
        KeyRanks.Invisible)

    /** Prints the content of a .sjsir file in human readable form. */
    val scalajsp = InputKey[Unit]("scalajsp",
        "Prints the content of a .sjsir file in human readable form.",
        CTask)

    val scalaJSConfigurationLibs = TaskKey[Seq[ResolvedJSDependency]](
        "scalaJSConfigurationLibs",
        "List of JS libraries used as project configuration.", CTask)

    val scalaJSJavaSystemProperties = TaskKey[Map[String, String]](
        "scalaJSJavaSystemProperties",
        "List of arguments to pass to the Scala.js Java System.properties.",
        CTask)

    val scalaJSSourceFiles = AttributeKey[Seq[File]]("scalaJSSourceFiles",
        "Files used to compute this value (can be used in FileFunctions later).",
        KeyRanks.Invisible)

    val scalaJSSourceMap = AttributeKey[File]("scalaJSSourceMap",
        "Source map file attached to an Attributed .js file.",
        BSetting)

    /* This is here instead of in impl.DependencyBuilders for binary
     * compatibility reasons (impl.DependencyBuilders is a non-sealed trait).
     */
    @deprecated(
        """Use %%% if possible, or '"com.example" % "foo" % "1.0.0" cross """ +
        """ScalaJSCrossVersion.binary"'""",
        "0.6.23")
    final implicit def toScalaJSGroupeIDForce(
        groupID: String): impl.ScalaJSGroupIDForce = {
      require(groupID.trim.nonEmpty, "Group ID cannot be empty.")
      new impl.ScalaJSGroupIDForce(groupID)
    }
  }

  import autoImport._

  /** Maps a [[Stage]] to the corresponding [[sbt.Def.TaskKey TaskKey]].
   *
   *  For example, [[Stage.FastOpt]] (aka `FastOptStage`) is mapped to
   *  [[fastOptJS]].
   */
  val stageKeys: Map[Stage, TaskKey[Attributed[File]]] = Map(
      Stage.FastOpt -> fastOptJS,
      Stage.FullOpt -> fullOptJS
  )

  /** Logs the current statistics about the global IR cache. */
  def logIRCacheStats(logger: Logger): Unit = {
    import ScalaJSPluginInternal.globalIRCache
    logger.debug("Global IR cache stats: " + globalIRCache.stats.logLine)
  }

  override def globalSettings: Seq[Setting[_]] = {
    Seq(
        scalaJSStage := Stage.FastOpt,
        scalaJSUseRhinoInternal := false,

        ScalaJSPluginInternal.scalaJSClearCacheStatsInternal := {},

        // Clear the IR cache stats every time a sequence of tasks ends
        onComplete := {
          val prev = onComplete.value

          { () =>
            prev()
            ScalaJSPluginInternal.closeAllTestAdapters()
            ScalaJSPluginInternal.globalIRCache.clearStats()
          }
        },

        /* When unloading the build, free all the IR caches.
         * Note that this runs on `reload`s, for example, but not when we
         * *exit* sbt. That is fine, though, since in that case the process
         * is killed altogether.
         */
        onUnload := {
          onUnload.value.andThen { state =>
            ScalaJSPluginInternal.freeAllIRCaches()
            state
          }
        }
    )
  }

  override def projectSettings: Seq[Setting[_]] = (
      ScalaJSPluginInternal.scalaJSAbstractSettings ++
      ScalaJSPluginInternal.scalaJSEcosystemSettings
  )

  /** Basic set of settings enabling Scala.js for a configuration.
   *
   *  The `Compile` and `Test` configurations of sbt are already equipped with
   *  these settings. Directly using this method is only necessary if you want
   *  to configure a custom configuration.
   *
   *  Moreover, if your custom configuration is similar in spirit to `Compile`
   *  (resp. `Test`), you should use [[compileConfigSettings]] (resp.
   *  [[testConfigSettings]]) instead.
   */
  def baseConfigSettings: Seq[Setting[_]] =
    ScalaJSPluginInternal.scalaJSConfigSettings

  /** Complete set of settings enabling Scala.js for a `Compile`-like
   *  configuration.
   *
   *  The `Compile` configuration of sbt is already equipped with these
   *  settings. Directly using this method is only necessary if you want to
   *  configure a custom `Compile`-like configuration.
   */
  def compileConfigSettings: Seq[Setting[_]] =
    ScalaJSPluginInternal.scalaJSCompileSettings

  /** Complete set of settings enabling Scala.js for a `Test`-like
   *  configuration.
   *
   *  The `Test` configuration of sbt is already equipped with these settings.
   *  Directly using this method is only necessary if you want to configure a
   *  custom `Test`-like configuration, e.g., `IntegrationTest`.
   */
  def testConfigSettings: Seq[Setting[_]] =
    ScalaJSPluginInternal.scalaJSTestSettings
}
