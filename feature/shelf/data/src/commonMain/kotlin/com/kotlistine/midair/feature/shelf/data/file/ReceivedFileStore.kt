package com.kotlistine.midair.feature.shelf.data.file

import com.kotlistine.midair.core.domain.util.DataError
import com.kotlistine.midair.core.domain.util.Result

/** Where received files are kept (Downloads/TransferingFileByAir on Android). */
interface ReceivedFileStore {
    /**
     * Copies a file that just arrived (at [location], a platform reference) into the received-files folder as [name].
     * Returns the lasting reference to the kept copy.
     */
    suspend fun keep(location: String, name: String, mimeType: String): Result<String, DataError.Local>
}
