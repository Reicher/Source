package com.source.self

import android.content.Context

object SelfStores {
    @Volatile private var bronzeStore: BronzeStore? = null
    @Volatile private var silverStore: SilverStore? = null

    fun bronze(context: Context): BronzeStore = bronzeStore ?: synchronized(this) {
        bronzeStore ?: BronzeStore(context.applicationContext).also { bronzeStore = it }
    }

    fun silver(context: Context): SilverStore = silverStore ?: synchronized(this) {
        silverStore ?: SilverStore(context.applicationContext).also { silverStore = it }
    }
}
