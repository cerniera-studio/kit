package com.cerniera.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The family look's decisions (26 Nov 2026): one finish everywhere, never a silent fall-back. */
class FamilyRulesTest {
    private val known = setOf("VETRO", "CRISTALLO", "ROSSO", "CARBONIO", "OTTONE")
    private fun a(f: String, r: Int = 14, s: Long = 1L) = FamilyRules.Answer(f, r, s)

    @Test fun foldWidgetsAnswerAlwaysWins() {
        val d = FamilyRules.decide(true, a("ROSSO"), a("CARBONIO"), "CRISTALLO", 32, known)
        assertEquals(FamilyRules.Decision("ROSSO", 14, FamilyRules.Source.HOST), d)
    }

    @Test fun noAnswerKeepsTheLastLookNotVetro() {
        // The bug: a slow Fold Widgets left one widget in Vetro at 24 beside the others in Rosso
        val d = FamilyRules.decide(true, null, a("ROSSO", 14), "VETRO", 24, known)
        assertEquals(FamilyRules.Decision("ROSSO", 14, FamilyRules.Source.REMEMBERED), d)
    }

    @Test fun unknownFinishKeepsTheLastLook() {
        val d = FamilyRules.decide(true, a("TITANIO"), a("OTTONE", 24), "VETRO", 24, known)
        assertEquals("OTTONE", d.finish); assertEquals(FamilyRules.Source.REMEMBERED, d.source)
    }

    @Test fun ownChoiceOnlyWithoutFoldWidgets() {
        assertEquals(FamilyRules.Decision("CRISTALLO", 32, FamilyRules.Source.OWN), FamilyRules.decide(false, a("ROSSO"), a("ROSSO"), "CRISTALLO", 32, known))
        // Installed, but nothing ever answered: the app's own, as before
        assertEquals(FamilyRules.Source.OWN, FamilyRules.decide(true, null, null, "CRISTALLO", 32, known).source)
        assertEquals("VETRO", FamilyRules.decide(false, null, null, "GONE", 24, known).finish)
    }

    @Test fun cornersStayInRange() {
        assertEquals(40, FamilyRules.decide(true, a("ROSSO", 99), null, "VETRO", 24, known).radius)
        assertEquals(0, FamilyRules.decide(true, a("ROSSO", -3), null, "VETRO", 24, known).radius)
    }

    @Test fun onlyTheFamilyIsFamily() {
        assertTrue(FamilyRules.isFamily("com.cerniera.settimana"))
        assertTrue(FamilyRules.isFamily("com.fergusonconsultancy.foldtaskbar"))
        assertFalse(FamilyRules.isFamily("com.example.cerniera"))
        assertFalse(FamilyRules.isFamily(null))
    }

    @Test fun eachPlaceGoesToOneWidget() {
        // Places at x = 10 and 300; widgets last seen at x = 290 (id 1) and 0 (id 2)
        val last = mapOf(1 to 290f, 2 to 0f)
        val out = FamilyRules.assign(listOf(10f, 300f), listOf(1, 2)) { s, id -> Math.abs(last.getValue(id) - s) }
        assertEquals(mapOf(2 to 10f, 1 to 300f), out)
        // More places than widgets: the extra is left
        assertEquals(1, FamilyRules.assign(listOf(1f, 2f, 3f), listOf(7)) { _, _ -> 0f }.size)
    }

    @Test fun imageUriCarriesTheVersion() {
        assertEquals("content://a.look/image/main?v=42", FamilyRules.imageUri("a.look", "main", 42L))
    }
}
