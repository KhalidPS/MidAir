package com.kotlistine.midair.core.domain.util

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import kotlin.test.Test

class ResultTest {

    @Test
    fun `map transforms success data`() {
        val result: Result<Int, DataError.Local> = Result.Success(2)

        assertThat(result.map { it * 10 }).isEqualTo(Result.Success(20))
    }

    @Test
    fun `map keeps failure untouched and never calls transform`() {
        val result: Result<Int, DataError.Local> = Result.Failure(DataError.Local.DISK_FULL)
        var called = false

        val mapped = result.map { called = true; it * 10 }

        assertThat(mapped).isEqualTo(Result.Failure(DataError.Local.DISK_FULL))
        assertThat(called).isFalse()
    }

    @Test
    fun `asEmptyResult drops the success payload`() {
        val result: Result<String, DataError.Local> = Result.Success("file")

        assertThat(result.asEmptyResult()).isEqualTo(Result.Success(Unit))
    }

    @Test
    fun `asEmptyResult keeps the failure`() {
        val result: Result<String, DataError.Local> = Result.Failure(DataError.Local.PERMISSION_DENIED)

        assertThat(result.asEmptyResult()).isEqualTo(Result.Failure(DataError.Local.PERMISSION_DENIED))
    }

    @Test
    fun `onSuccess runs only for success and onFailure only for failure`() {
        var seenData: String? = null
        var seenError: DataError.Local? = null

        val success: Result<String, DataError.Local> = Result.Success("ok")
        success.onSuccess { seenData = it }.onFailure { seenError = it }
        assertThat(seenData).isEqualTo("ok")
        assertThat(seenError).isNull()

        seenData = null
        val failure: Result<String, DataError.Local> = Result.Failure(DataError.Local.FILE_NOT_FOUND)
        failure.onSuccess { seenData = it }.onFailure { seenError = it }
        assertThat(seenData).isNull()
        assertThat(seenError).isEqualTo(DataError.Local.FILE_NOT_FOUND)
    }
}
