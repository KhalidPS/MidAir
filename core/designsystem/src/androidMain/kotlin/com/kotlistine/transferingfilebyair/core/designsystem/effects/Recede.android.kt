package com.kotlistine.transferingfilebyair.core.designsystem.effects

import android.os.Build

internal actual val isBlurSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
