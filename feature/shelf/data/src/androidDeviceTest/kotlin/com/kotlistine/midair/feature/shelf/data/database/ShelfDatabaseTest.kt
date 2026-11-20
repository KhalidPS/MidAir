package com.kotlistine.midair.feature.shelf.data.database

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real Room schema and SQL on a file-backed database. Names are camelCase because
 * method names with spaces are not valid in DEX below API 30.
 */
@RunWith(AndroidJUnit4::class)
class ShelfDatabaseTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: ShelfDatabase

    @Before
    fun setUp() {
        context.deleteDatabase(DB_NAME)
        database = createShelfDatabase(context, DB_NAME)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun itemsSurviveClosingAndReopeningTheDatabase() = runTest {
        database.shelfItemDao().insert(entity("content://photo", addedAt = 1, textPreview = "# Notes"))

        database.close()
        database = createShelfDatabase(context, DB_NAME)

        val items = database.shelfItemDao().observeAll().first()
        assertThat(items.map { it.uri }).containsExactly("content://photo")
        assertThat(items.single().textPreview).isEqualTo("# Notes")
    }

    @Test
    fun newestItemsComeFirstAndTiesKeepTheLatestInsertFirst() = runTest {
        val dao = database.shelfItemDao()
        dao.insert(entity("content://old", addedAt = 1))
        dao.insert(entity("content://tie-first", addedAt = 5))
        dao.insert(entity("content://tie-second", addedAt = 5))

        val uris = dao.observeAll().first().map { it.uri }

        assertThat(uris).containsExactly("content://tie-second", "content://tie-first", "content://old")
    }

    @Test
    fun insertingAnAlreadyStagedUriIsIgnored() = runTest {
        val dao = database.shelfItemDao()

        val firstId = dao.insert(entity("content://photo", addedAt = 1))
        val secondId = dao.insert(entity("content://photo", addedAt = 2))

        assertThat(firstId).isGreaterThan(0L)
        assertThat(secondId).isEqualTo(-1L)
        assertThat(dao.existsByUri("content://photo")).isEqualTo(true)
        assertThat(dao.observeAll().first().map { it.addedAtEpochMillis }).containsExactly(1L)
    }

    @Test
    fun deletingByIdAndClearingRemoveRows() = runTest {
        val dao = database.shelfItemDao()
        val keptId = dao.insert(entity("content://kept", addedAt = 1))
        val removedId = dao.insert(entity("content://removed", addedAt = 2))

        dao.deleteById(removedId)
        assertThat(dao.getById(removedId)).isEqualTo(null)
        assertThat(dao.getAllUris()).containsExactly("content://kept")
        assertThat(dao.getById(keptId)?.uri).isEqualTo("content://kept")

        dao.deleteAll()
        assertThat(dao.observeAll().first()).isEmpty()
    }

    private fun entity(uri: String, addedAt: Long, textPreview: String? = null) = ShelfItemEntity(
        uri = uri,
        name = uri.substringAfterLast('/'),
        mimeType = "application/octet-stream",
        sizeBytes = 1,
        addedAtEpochMillis = addedAt,
        widthPx = null,
        heightPx = null,
        textPreview = textPreview,
    )

    private companion object {
        const val DB_NAME = "shelf-device-test.db"
    }
}
