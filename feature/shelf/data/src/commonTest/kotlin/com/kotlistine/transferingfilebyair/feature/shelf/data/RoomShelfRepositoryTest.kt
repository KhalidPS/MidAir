package com.kotlistine.transferingfilebyair.feature.shelf.data

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsOnly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.single
import com.kotlistine.transferingfilebyair.core.domain.shelf.ShelfItem
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.feature.shelf.data.file.FileMetadata
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class RoomShelfRepositoryTest {

    private lateinit var dao: FakeShelfItemDao
    private lateinit var files: FakeFileMetadataSource
    private lateinit var repository: RoomShelfRepository
    private var now = 1_000L

    @BeforeTest
    fun setUp() {
        dao = FakeShelfItemDao()
        files = FakeFileMetadataSource()
        repository = RoomShelfRepository(dao = dao, files = files, nowEpochMillis = { now++ })
    }

    @Test
    fun `adding a file stores its metadata and keeps access to it`() = runTest {
        files.files[PHOTO] = FileMetadata("Golden Hour.jpg", "image/jpeg", 367_000, widthPx = 4000, heightPx = 3000)

        val result = repository.addFile(PHOTO)

        assertThat(result).isEqualTo(Result.Success(Unit))
        assertThat(files.retained).containsOnly(PHOTO)
        repository.observeItems().test {
            assertThat(awaitItem()).single().isEqualTo(
                ShelfItem(
                    id = "1",
                    name = "Golden Hour.jpg",
                    mimeType = "image/jpeg",
                    sizeBytes = 367_000,
                    addedAtEpochMillis = 1_000,
                    uri = PHOTO,
                    widthPx = 4000,
                    heightPx = 3000,
                ),
            )
        }
    }

    @Test
    fun `newest items come first`() = runTest {
        files.files[PHOTO] = metadata("Golden Hour.jpg")
        files.files[REPORT] = metadata("Q4 Report.pdf")

        repository.addFile(PHOTO)
        repository.addFile(REPORT)

        repository.observeItems().test {
            assertThat(awaitItem().map { it.name }).containsExactly("Q4 Report.pdf", "Golden Hour.jpg")
        }
    }

    @Test
    fun `adding the same file twice keeps a single entry`() = runTest {
        files.files[PHOTO] = metadata("Golden Hour.jpg")

        repository.addFile(PHOTO)
        val second = repository.addFile(PHOTO)

        assertThat(second).isEqualTo(Result.Success(Unit))
        assertThat(dao.all.map { it.uri }).containsExactly(PHOTO)
    }

    @Test
    fun `re-adding a staged file does not read it again`() = runTest {
        files.files[PHOTO] = metadata("Golden Hour.jpg")
        repository.addFile(PHOTO)
        // Even if the file has since become unreadable, re-picking it is still a harmless no-op.
        files.files.remove(PHOTO)

        val second = repository.addFile(PHOTO)

        assertThat(second).isEqualTo(Result.Success(Unit))
        assertThat(files.reads[PHOTO]).isEqualTo(1)
    }

    @Test
    fun `an unreadable file is reported and not stored`() = runTest {
        val result = repository.addFile(PHOTO)

        assertThat(result).isEqualTo(Result.Failure(DataError.Local.FILE_NOT_FOUND))
        assertThat(dao.all).isEmpty()
        assertThat(files.retained).isEmpty()
    }

    @Test
    fun `a file whose access cannot be kept is not stored`() = runTest {
        files.files[PHOTO] = metadata("Golden Hour.jpg")
        files.retainFailure = DataError.Local.PERMISSION_DENIED

        val result = repository.addFile(PHOTO)

        assertThat(result).isEqualTo(Result.Failure(DataError.Local.PERMISSION_DENIED))
        assertThat(dao.all).isEmpty()
    }

    @Test
    fun `a full disk while saving gives the access back and reports it`() = runTest {
        files.files[PHOTO] = metadata("Golden Hour.jpg")
        // The exact message BundledSQLiteDriver produces for SQLITE_FULL.
        dao.insertFailure = IllegalStateException("Error code: 13, message: database or disk is full")

        val result = repository.addFile(PHOTO)

        assertThat(result).isEqualTo(Result.Failure(DataError.Local.DISK_FULL))
        assertThat(files.retained).isEmpty()
        assertThat(dao.all).isEmpty()
    }

    @Test
    fun `any other database failure while saving is unknown and gives the access back`() = runTest {
        files.files[PHOTO] = metadata("Golden Hour.jpg")
        dao.insertFailure = IllegalStateException("Error code: 5, message: database is locked")

        val result = repository.addFile(PHOTO)

        assertThat(result).isEqualTo(Result.Failure(DataError.Local.UNKNOWN))
        assertThat(files.retained).isEmpty()
    }

    @Test
    fun `text previews are stored with the item`() = runTest {
        files.files[REPORT] = metadata("Meeting notes.md").copy(textPreview = "# Agenda")

        repository.addFile(REPORT)

        repository.observeItems().test {
            assertThat(awaitItem().single().textPreview).isEqualTo("# Agenda")
        }
    }

    @Test
    fun `removing an item deletes it and releases its access`() = runTest {
        files.files[PHOTO] = metadata("Golden Hour.jpg")
        files.files[REPORT] = metadata("Q4 Report.pdf")
        repository.addFile(PHOTO)
        repository.addFile(REPORT)

        val result = repository.removeItem("1")

        assertThat(result).isEqualTo(Result.Success(Unit))
        assertThat(dao.all.map { it.uri }).containsExactly(REPORT)
        assertThat(files.retained).containsOnly(REPORT)
    }

    @Test
    fun `removing an unknown item fails without touching the shelf`() = runTest {
        files.files[PHOTO] = metadata("Golden Hour.jpg")
        repository.addFile(PHOTO)

        assertThat(repository.removeItem("42")).isEqualTo(Result.Failure(DataError.Local.FILE_NOT_FOUND))
        assertThat(repository.removeItem("not-a-number")).isEqualTo(Result.Failure(DataError.Local.FILE_NOT_FOUND))
        assertThat(dao.all.map { it.uri }).containsExactly(PHOTO)
    }

    @Test
    fun `clearing empties the shelf and releases every access`() = runTest {
        files.files[PHOTO] = metadata("Golden Hour.jpg")
        files.files[REPORT] = metadata("Q4 Report.pdf")
        repository.addFile(PHOTO)
        repository.addFile(REPORT)

        val result = repository.clear()

        assertThat(result).isEqualTo(Result.Success(Unit))
        assertThat(dao.all).isEmpty()
        assertThat(files.retained).isEmpty()
    }

    @Test
    fun `clearing keeps access when the delete fails`() = runTest {
        files.files[PHOTO] = metadata("Golden Hour.jpg")
        repository.addFile(PHOTO)
        dao.deleteFailure = IllegalStateException("Error code: 5, message: database is locked")

        val result = repository.clear()

        assertThat(result).isEqualTo(Result.Failure(DataError.Local.UNKNOWN))
        assertThat(files.retained).containsOnly(PHOTO)
        assertThat(dao.all.map { it.uri }).containsExactly(PHOTO)
    }

    @Test
    fun `removing keeps access when the delete fails`() = runTest {
        files.files[PHOTO] = metadata("Golden Hour.jpg")
        repository.addFile(PHOTO)
        dao.deleteFailure = IllegalStateException("Error code: 5, message: database is locked")

        val result = repository.removeItem("1")

        assertThat(result).isEqualTo(Result.Failure(DataError.Local.UNKNOWN))
        assertThat(files.retained).containsOnly(PHOTO)
    }

    private fun metadata(name: String) =
        FileMetadata(name = name, mimeType = "application/octet-stream", sizeBytes = 1, widthPx = null, heightPx = null)

    private companion object {
        const val PHOTO = "content://media/golden-hour"
        const val REPORT = "content://docs/q4-report"
    }
}
