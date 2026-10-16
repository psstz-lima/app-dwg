package com.appdwg.core.drainage

import com.appdwg.core.I18n
import com.appdwg.core.IdfCatalog

/**
 * Entry point for [MinifiedCatalogTest]. R8 keeps this class and repackages [CatalogLoader];
 * the counts below are read from that minified program, not from the test classpath.
 */
object CatalogReleaseProbe {
    @JvmStatic
    fun deviceCount(): Int = CatalogLoader.bundled().devices.size

    @JvmStatic
    fun englishCount(): Int = I18n.english.size

    @JvmStatic
    fun idfCount(): Int = IdfCatalog.entries.size
}
