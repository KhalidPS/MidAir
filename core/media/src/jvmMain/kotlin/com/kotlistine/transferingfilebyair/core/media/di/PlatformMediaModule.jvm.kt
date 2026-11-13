package com.kotlistine.transferingfilebyair.core.media.di

import com.kotlistine.transferingfilebyair.core.media.FfmpegVideoEngine
import com.kotlistine.transferingfilebyair.core.media.VideoEngine
import org.koin.dsl.module

internal actual val platformMediaModule = module {
    factory<VideoEngine> { FfmpegVideoEngine() }
}
