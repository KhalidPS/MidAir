package com.kotlistine.transferingfilebyair.core.domain.util

sealed interface DataError : Error {

    /** Failures reading or writing files and app storage on this device. */
    enum class Local : DataError {
        FILE_NOT_FOUND,
        PERMISSION_DENIED,
        DISK_FULL,
        UNKNOWN,
    }
}
