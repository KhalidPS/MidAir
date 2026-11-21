package com.kotlistine.midair.core.media.di

import com.kotlistine.midair.core.media.FfmpegVideoEngine
import com.kotlistine.midair.core.media.VideoEngine
import org.koin.dsl.module

internal actual val platformMediaModule = module {
    factory<VideoEngine> { FfmpegVideoEngine() }
}
