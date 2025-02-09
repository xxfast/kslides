package com.kslides

import com.github.pambrose.common.response.respondWith
import com.github.pambrose.common.util.ensureSuffix
import com.kslides.KSlides.Companion.logger
import com.kslides.KSlides.Companion.runHttpServer
import com.kslides.KSlides.Companion.writeSlidesToFileSystem
import com.kslides.Page.generatePage
import com.kslides.config.KSlidesConfig
import com.kslides.config.OutputConfig
import com.kslides.config.PresentationConfig
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.routing.*
import kotlinx.css.*
import mu.KLogging
import java.io.File

@DslMarker
annotation class KSlidesDslMarker

@KSlidesDslMarker
fun kslides(block: KSlides.() -> Unit) =
  KSlides()
    .apply {
      block()
      require(presentationBlocks.isNotEmpty()) { "At least one presentation must be defined" }

      kslidesConfigBlock(kslidesConfig)

      globalPresentationConfigBlock(globalPresentationConfig)

      presentationBlocks.forEach { presentationBlock ->
        Presentation(this)
          .apply {
            presentationBlock()
            require(slides.isNotEmpty()) { "At least one slide must be defined for a presentation" }
            validatePath()

            finalConfig =
              PresentationConfig()
                .also { config ->
                  config.mergeConfig(kslides.globalPresentationConfig)
                  config.mergeConfig(presentationConfig)
                }

            assignCssFiles()
            assignJsFiles()
            assignPlugins()
            assignDependencies()
          }
      }

      outputConfigBlock(outputConfig)

      if (!outputConfig.enableFileSystem && !outputConfig.enableHttp)
        logger.warn { "Set enableHttp or enableFileSystem to true in the kslides output{} block" }

      if (outputConfig.enableFileSystem)
        writeSlidesToFileSystem(outputConfig)

      if (outputConfig.enableHttp)
        runHttpServer(outputConfig, true)
    }

@KSlidesDslMarker
internal fun kslidesTest(block: KSlides.() -> Unit) =
  kslides {
    block()
    output {
      enableFileSystem = false
      enableHttp = false
    }
  }

class KSlides {
  internal val kslidesConfig = KSlidesConfig()
  internal val globalPresentationConfig = PresentationConfig().apply { assignDefaults() }
  internal val outputConfig = OutputConfig(this)

  internal var kslidesConfigBlock: KSlidesConfig.() -> Unit = {}
  internal var globalPresentationConfigBlock: PresentationConfig.() -> Unit = {}
  internal var outputConfigBlock: OutputConfig.() -> Unit = {}
  internal var presentationBlocks = mutableListOf<Presentation.() -> Unit>()
  internal val presentationMap = mutableMapOf<String, Presentation>()
  internal val presentations get() = presentationMap.values
  internal val staticIframeContent = mutableMapOf<String, String>()
  internal val dynamicIframeContent = mutableMapOf<String, () -> String>()
  internal var slideCount = 1

  internal fun presentation(name: String) =
    presentationMap[name] ?: throw IllegalArgumentException("Presentation $name not found")

  // User variables
  val css = CssValue()

  @KSlidesDslMarker
  fun kslidesConfig(block: KSlidesConfig.() -> Unit) {
    kslidesConfigBlock = block
  }

  @KSlidesDslMarker
  fun output(block: OutputConfig.() -> Unit) {
    outputConfigBlock = block
  }

  @KSlidesDslMarker
  fun presentationConfig(block: PresentationConfig.() -> Unit) {
    globalPresentationConfigBlock = block
  }

  @KSlidesDslMarker
  fun css(block: CssBuilder.() -> Unit) {
    css += block
  }

  @KSlidesDslMarker
  fun presentation(block: Presentation.() -> Unit) {
    presentationBlocks += block
  }

  companion object : KLogging() {
    internal fun writeSlidesToFileSystem(config: OutputConfig) {
      require(config.outputDir.isNotBlank()) { "outputDir value must not be empty" }

      val outputDir = config.outputDir
      val rootPrefix = config.staticRootDir.ensureSuffix("/")

      // Create directory if missing
      File(outputDir).mkdir()

      config.kslides.presentationMap
        .forEach { (key, p) ->
          val (file, srcPrefix) =
            when {
              key == "/" -> File("$outputDir/index.html") to rootPrefix
              key.endsWith(".html") -> File("$outputDir/$key") to rootPrefix
              else -> {
                val pathElems = "$outputDir/$key".split("/").filter { it.isNotBlank() }
                val path = pathElems.joinToString("/")
                val dotDot = List(pathElems.size - 1) { "../" }.joinToString("")
                // Create directory if missing
                File(path).mkdir()
                File("$path/index.html") to "$dotDot$rootPrefix"
              }
            }
          logger.info { "Writing presentation $key to $file" }
          file.writeText(generatePage(p, false, srcPrefix))
        }
    }

    internal fun runHttpServer(config: OutputConfig, wait: Boolean) {
      embeddedServer(CIO, port = config.port) {
        // By embedding this logic here, rather than in an Application.module() call, we are not able to use auto-reload
        install(CallLogging) { level = config.callLoggingLogLevel }
        install(DefaultHeaders) { header("X-Engine", "Ktor") }
        install(Compression) {
          gzip { priority = 1.0 }
          deflate { priority = 10.0; minimumSize(1024) /* condition*/ }
        }

        val kslides = config.kslides

        kslides.presentationMap
          .apply {
            if (!containsKey("/") && !containsKey("/index.html"))
              logger.warn { """Missing a presentation with: path = "/"""" }
          }

        routing {
          listOf(config.playgroundDir, config.plotlyDir)
            .forEach {
              get("$it/{fname}") {
                respondWith {
                  val path = call.parameters["fname"] ?: throw IllegalArgumentException("Missing slide arg $it")
                  kslides.dynamicIframeContent[path]?.invoke()
                    ?: kslides.staticIframeContent[path]
                    ?: throw IllegalArgumentException("Invalid slide path: $path")
                }
              }
            }

          if (config.defaultHttpRoot.isNotBlank())
            static("/") {
              staticBasePackage = config.defaultHttpRoot
              resources(".")
            }

          // This is hardcoded for http since it is shipped with the jar
          val rootDir = "revealjs"
          val baseDirs =
            kslides.kslidesConfig.httpStaticRoots
              .filter { it.dirname.isNotBlank() }
              .map { it.dirname }

          if (baseDirs.isNotEmpty())
            static("/") {
              staticBasePackage = rootDir
              static(rootDir) {
                baseDirs.forEach {
                  static(it) {
                    logger.debug { "Registering http dir $it" }
                    resources(it)
                  }
                }
              }
            }

          kslides.presentationMap
            .forEach { (key, p) ->
              get(key) {
                respondWith {
                  generatePage(p, true, "/$rootDir")
                }
              }
            }
        }
      }.start(wait = wait)
    }
  }
}
