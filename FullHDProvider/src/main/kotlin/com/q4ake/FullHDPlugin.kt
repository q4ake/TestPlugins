package com.q4akeimport com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context@CloudstreamPlugin
class FullHDPlugin: Plugin() {
override fun load(context: Context) {
registerMainAPI(FullHDProvider())
}
}