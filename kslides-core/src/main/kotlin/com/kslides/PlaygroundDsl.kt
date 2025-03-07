package com.kslides

import com.kslides.Playground.playgroundContent
import com.kslides.config.PlaygroundConfig
import com.kslides.slide.DslSlide
import kotlinx.html.*

//context(Presentation, DslSlide, SECTION)
@KSlidesDslMarker
fun DslSlide.playground(
  srcName: String,
  vararg otherSrcs: String = emptyArray(),
  configBlock: PlaygroundConfig.() -> Unit = {},
) {
  val iframeId = _iframeCount++
  val kslides = presentation.kslides
  val localConfig = PlaygroundConfig().apply { configBlock() }
  val mergedConfig =
    PlaygroundConfig()
      .also { config ->
        config.merge(globalPlaygroundConfig)
        config.merge(presentationPlaygroundConfig)
        config.merge(localConfig)
      }

  // CSS values are additive
  val combinedCss =
    CssValue(
      globalPlaygroundConfig.css,
      presentationPlaygroundConfig.css,
      localConfig.css
    )

  recordContent(kslides, mergedConfig.staticContent, filename(iframeId), playgroundPath) {
    playgroundContent(kslides, mergedConfig, combinedCss, srcName, otherSrcs.toList())
  }

  _section?.iframe {
    src = playgroundFilename(iframeId)
    mergedConfig.width.also { if (it.isNotBlank()) width = it }
    mergedConfig.height.also { if (it.isNotBlank()) height = it }
    mergedConfig.style.also { if (it.isNotBlank()) style = it }
    mergedConfig.title.also { if (it.isNotBlank()) title = it }
  } ?: error("playground{} must be called from within a content{} block")
}