package com.cerniera.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android's refusal of an oversized widget update, read and answered (27 Nov 2026). */
class WidgetBudgetRulesTest {
    @Test fun readsTheRefusal() {
        val m = "RemoteViews for widget update exceeds maximum bitmap memory usage (used: 15913120, max: 1228800)"
        assertEquals(15913120L to 1228800L, WidgetBudgetRules.parse(m))
        assertNull(WidgetBudgetRules.parse("something else"))
        assertNull(WidgetBudgetRules.parse(null))
    }

    @Test fun theNextTryFitsWithRoom() {
        val s = WidgetBudgetRules.nextScale(1f, 20_000_000L, 16_000_000L)
        assertTrue(s * 20_000_000L <= 16_000_000L * 0.86f)
        assertEquals(0.68f, s, 0.001f)
    }

    @Test fun unnamedCapHalves() {
        assertEquals(0.5f, WidgetBudgetRules.nextScale(1f, 0L, 0L), 0.0001f)
        assertEquals(0.05f, WidgetBudgetRules.nextScale(0.06f, 0L, 0L), 0.0001f)
    }
}
