package eu.kanade.tachiyomi.extension.es.picazor

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class PicazorFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(Picazor())
}
