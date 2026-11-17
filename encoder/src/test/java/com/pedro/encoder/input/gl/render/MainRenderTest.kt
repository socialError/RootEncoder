package com.pedro.encoder.input.gl.render

import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.input.gl.render.filters.NoFilterRender
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainRenderTest {

  private fun filter(renderMode: RenderMode): BaseFilterRender =
    NoFilterRender().apply { this.renderMode = renderMode }

  @Test
  fun noFiltersMatch() {
    assertTrue(MainRender.previewFiltersMatchOutput(emptyList()))
  }

  @Test
  fun allFiltersInBothMatch() {
    assertTrue(MainRender.previewFiltersMatchOutput(listOf(filter(RenderMode.ALL), filter(RenderMode.ALL))))
  }

  @Test
  fun previewOnlyFilterDoesNotMatch() {
    assertFalse(MainRender.previewFiltersMatchOutput(listOf(filter(RenderMode.ALL), filter(RenderMode.PREVIEW))))
  }

  @Test
  fun outputOnlyFilterDoesNotMatch() {
    assertFalse(MainRender.previewFiltersMatchOutput(listOf(filter(RenderMode.OUTPUT), filter(RenderMode.ALL))))
  }
}
