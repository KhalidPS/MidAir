package com.kotlistine.midair.core.domain.shelf

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test

class ShelfItemTest {

    @Test
    fun `mime type decides the kind`() {
        assertThat(FileKind.of("image/jpeg", "Golden Hour.jpg")).isEqualTo(FileKind.IMAGE)
        assertThat(FileKind.of("video/mp4", "clip.mp4")).isEqualTo(FileKind.VIDEO)
        assertThat(FileKind.of("application/pdf", "Q4 Report.pdf")).isEqualTo(FileKind.PDF)
        assertThat(FileKind.of("text/plain", "notes")).isEqualTo(FileKind.TEXT)
    }

    @Test
    fun `extension is the fallback for generic mime types`() {
        assertThat(FileKind.of("application/octet-stream", "Q4 Report.PDF")).isEqualTo(FileKind.PDF)
        assertThat(FileKind.of("application/octet-stream", "Meeting notes.md")).isEqualTo(FileKind.TEXT)
        assertThat(FileKind.of("application/octet-stream", "archive.zip")).isEqualTo(FileKind.OTHER)
    }

    @Test
    fun `mime type wins over a misleading extension`() {
        assertThat(FileKind.of("image/png", "screenshot.pdf")).isEqualTo(FileKind.IMAGE)
    }

    @Test
    fun `extension badge is upper case and absent for names without one`() {
        assertThat(item(name = "Meeting notes.md").extension).isEqualTo("MD")
        assertThat(item(name = "README").extension).isNull()
        assertThat(item(name = ".gitignore").extension).isNull()
        assertThat(item(name = "trailing.").extension).isNull()
    }

    @Test
    fun `a short hidden file name is not mistaken for an extension`() {
        assertThat(item(name = ".env").extension).isNull()
    }

    @Test
    fun `long suffixes are not shown as a type badge`() {
        assertThat(item(name = "holiday.jpeg_backup").extension).isNull()
        assertThat(item(name = "archive.tar.zst").extension).isEqualTo("ZST")
    }

    private fun item(name: String) = ShelfItem(
        id = "1",
        name = name,
        mimeType = "application/octet-stream",
        sizeBytes = 0,
        addedAtEpochMillis = 0,
        uri = "content://x",
    )
}
