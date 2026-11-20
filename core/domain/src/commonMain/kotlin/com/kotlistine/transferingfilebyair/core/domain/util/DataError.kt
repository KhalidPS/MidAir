package com.kotlistine.transferingfilebyair.core.domain.util

sealed interface DataError : Error {

    /** Failures reading or writing files and app storage on this device. */
    enum class Local : DataError {
        FILE_NOT_FOUND,
        PERMISSION_DENIED,
        DISK_FULL,

        /** The file is in a format this device can't read (a video codec it has no decoder for). */
        UNSUPPORTED_FORMAT,

        /** The camera can't be used to watch for air gestures (not allowed, missing, or busy). */
        CAMERA_UNAVAILABLE,

        /** The camera is switched off for apps in the system's privacy settings; the user can switch it back on. */
        CAMERA_BLOCKED,
        UNKNOWN,
    }

    /** Failures talking to the paired device. */
    enum class Network : DataError {
        /** No link to the peer (never paired, or the link dropped). */
        DISCONNECTED,

        /** The message is bigger than the transport accepts in one piece. */
        PAYLOAD_TOO_LARGE,

        /** The bytes received are not a message this version understands. */
        MALFORMED_MESSAGE,

        /** The user hasn't allowed the app to find nearby devices. */
        PERMISSION_MISSING,

        /** Bluetooth or Wi-Fi is switched off. */
        RADIO_OFF,

        /** The phone's Location setting is off, and nearby devices can't be found without it. */
        LOCATION_OFF,

        /** Someone stopped the transfer (on this phone or the other one). */
        CANCELLED,
        UNKNOWN,
    }
}
