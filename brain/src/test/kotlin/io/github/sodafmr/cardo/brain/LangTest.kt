package io.github.sodafmr.cardo.brain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LangTest {
    @Test
    fun fromTag() {
        assertEquals(Lang.ES, Lang.fromTag("es-ES"))
        assertEquals(Lang.EN, Lang.fromTag("en"))
        assertNull(Lang.fromTag("fr"))
    }

    @Test
    fun fromTagMatchesLanguageSubtagIgnoringCase() {
        assertEquals(Lang.ES, Lang.fromTag("ES"))
        assertEquals(Lang.EN, Lang.fromTag("en-US"))
        assertEquals(Lang.EN, Lang.fromTag("EN_gb"))
        assertNull(Lang.fromTag(""))
        assertNull(Lang.fromTag("esp"))
    }
}
