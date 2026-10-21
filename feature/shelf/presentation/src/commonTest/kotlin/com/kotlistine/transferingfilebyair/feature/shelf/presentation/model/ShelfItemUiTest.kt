package com.kotlistine.transferingfilebyair.feature.shelf.presentation.model

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import com.kotlistine.transferingfilebyair.core.domain.shelf.ShelfItem
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test

class ShelfItemUiTest {

    @Test
    fun `sizes use decimal units like the reference design`() {
        assertThat(formatFileSize(1)).isEqualTo("1 byte")
        assertThat(formatFileSize(208)).isEqualTo("208 bytes")
        assertThat(formatFileSize(16_000)).isEqualTo("16 KB")
        assertThat(formatFileSize(367_400)).isEqualTo("367 KB")
        assertThat(formatFileSize(1_600_000)).isEqualTo("1.6 MB")
        assertThat(formatFileSize(1_000_000)).isEqualTo("1 MB")
        assertThat(formatFileSize(2_340_000_000)).isEqualTo("2.3 GB")
    }

    @Test
    fun `sizes on a rounding edge move up to the next unit`() {
        assertThat(formatFileSize(999_499)).isEqualTo("999 KB")
        assertThat(formatFileSize(999_500)).isEqualTo("1 MB")
        assertThat(formatFileSize(999_949_999)).isEqualTo("999.9 MB")
        assertThat(formatFileSize(999_950_000)).isEqualTo("1 GB")
    }

    @Test
    fun `items added today show the time, older ones the date`() {
        val now = at(2026, 10, 21, 18, 0)

        assertThat(formatAddedTime(millis(2026, 10, 21, 9, 5), now, TimeZone.UTC)).isEqualTo("09:05")
        assertThat(formatAddedTime(millis(2026, 3, 4, 13, 25), now, TimeZone.UTC)).isEqualTo("4 Mar")
        assertThat(formatAddedTime(millis(2025, 12, 31, 23, 59), now, TimeZone.UTC)).isEqualTo("31 Dec 2025")
    }

    @Test
    fun `image cards follow the photo's shape within limits`() {
        assertThat(image(4000, 3000).cardAspectRatio()).isCloseTo(4f / 3f, 0.001f)
        assertThat(image(8000, 1000).cardAspectRatio()).isEqualTo(1.6f)
        assertThat(image(1000, 4000).cardAspectRatio()).isEqualTo(0.75f)
        assertThat(image(null, null).cardAspectRatio()).isCloseTo(4f / 3f, 0.001f)
    }

    @Test
    fun `document cards are tall pages`() {
        val pdf = image(null, null).copy(name = "Q4 Report.pdf", mimeType = "application/pdf")

        assertThat(pdf.cardAspectRatio()).isEqualTo(0.78f)
    }

    @Test
    fun `ui model combines size and time into one details line`() {
        val item = image(4000, 3000).copy(sizeBytes = 367_000, addedAtEpochMillis = millis(2026, 10, 21, 13, 25))

        val ui = item.toUi(now = at(2026, 10, 21, 18, 0), timeZone = TimeZone.UTC)

        assertThat(ui.details).isEqualTo("367 KB · 13:25")
        assertThat(ui.badge).isEqualTo("JPG")
    }

    private fun image(width: Int?, height: Int?) = ShelfItem(
        id = "1",
        name = "Golden Hour.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 0,
        addedAtEpochMillis = 0,
        uri = "content://x",
        widthPx = width,
        heightPx = height,
    )

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        LocalDateTime(year, month, day, hour, minute).toInstant(TimeZone.UTC)

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        at(year, month, day, hour, minute).toEpochMilliseconds()
}
