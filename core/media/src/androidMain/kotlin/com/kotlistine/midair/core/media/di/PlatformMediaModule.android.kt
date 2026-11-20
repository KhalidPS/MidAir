package com.kotlistine.midair.core.media.di

import android.content.Context
import com.kotlistine.midair.core.media.ExoVideoEngine
import com.kotlistine.midair.core.media.VideoEngine
import org.koin.dsl.module

internal actual val platformMediaModule = module {
    factory<VideoEngine> { ExoVideoEngine(context = get<Context>()) }
}
