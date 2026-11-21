package com.cerniera.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Senso's rules, the same scenarios the emulator test injects. */
class SensoRulesTest {
    private val zone = ZoneId.of("Europe/London")
    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, 18, h, m, 0, 0, zone).toInstant().toEpochMilli()
    private val noon = at(14)
    private val min = 60_000L
    private val pilot = setOf(Kind.RAIN_COMING, Kind.LEAVING_SOON, Kind.LISTENING)

    private fun offer(s: Signals, now: Long = noon, held: Kind? = null, dismissed: Set<Kind> = emptySet()) =
        Rules.pick(Rules.read(s, now, zone, dismissed), pilot, held)

    @Test fun silentByDefault() {
        assertNull(offer(Signals()))
    }

    @Test fun rainSoonAndLikely() {
        val m = offer(Signals(rain = SensoRain(noon + 40 * min, 0.8f)))
        assertEquals(Kind.RAIN_COMING, m?.kind)
        assertEquals("Rain from 14:40 · Cielo", m?.why)
    }

    @Test fun rainFarAndUnlikelyStaysQuiet() {
        assertNull(offer(Signals(rain = SensoRain(noon + 90 * min, 0.5f))))
        assertNull(offer(Signals(rain = SensoRain(noon + 3 * 60 * min, 0.95f))))
    }

    @Test fun goingOutRaisesRain() {
        val alone = Rules.rain(Signals(rain = SensoRain(noon + 90 * min, 0.7f)), noon, zone)!!
        val out = Rules.rain(Signals(rain = SensoRain(noon + 90 * min, 0.7f),
            event = SensoEvent("Dentist", noon + 100 * min, noon + 130 * min, "Park Square")), noon, zone)!!
        assertEquals(alone.confidence + 0.15f, out.confidence, 0.001f)
    }

    @Test fun leavingForSomewhere() {
        val m = offer(Signals(event = SensoEvent("Dentist", noon + 20 * min, noon + 60 * min, "Park Square")))
        assertEquals(Kind.LEAVING_SOON, m?.kind)
        assertEquals("Dentist at 14:20 · Park Square", m?.why)
    }

    @Test fun nobodyLeavesForACall() {
        assertNull(Rules.leaving(Signals(event = SensoEvent("Stand-up", noon + 10 * min, noon + 25 * min, "Microsoft Teams Meeting")), noon, zone))
        assertNull(Rules.leaving(Signals(event = SensoEvent("Sync", noon + 10 * min, noon + 25 * min, "https://meet.google.com/abc")), noon, zone))
    }

    @Test fun noPlaceIsLessSure() {
        val m = Rules.leaving(Signals(event = SensoEvent("Something", noon + 40 * min, noon + 60 * min, null)), noon, zone)!!
        assertTrue(m.confidence < Rules.THRESHOLD)
    }

    @Test fun eventTooFarOrStarted() {
        assertNull(Rules.leaving(Signals(event = SensoEvent("Later", noon + 50 * min, noon + 80 * min, "Leeds")), noon, zone))
        assertNull(Rules.leaving(Signals(event = SensoEvent("Now", noon - 5 * min, noon + 30 * min, "Leeds")), noon, zone))
    }

    @Test fun listeningInHeadphones() {
        val m = offer(Signals(playing = SensoPlaying("com.spotify.music", "Teardrop", "Massive Attack", true, noon), headphones = true))
        assertEquals(Kind.LISTENING, m?.kind)
        assertEquals(0.9f, m!!.confidence, 0.001f)
    }

    @Test fun headphonesInWithATrackPausedOffersResume() {
        val m = Rules.listening(Signals(playing = SensoPlaying("p", "Teardrop", "", false, noon - 10 * min),
            headphones = true, headphonesSince = noon - 30_000L), noon)
        assertEquals("Resume Teardrop", m?.why)
        // Long after the headphones went in and the pause: nothing
        assertNull(Rules.listening(Signals(playing = SensoPlaying("p", "Teardrop", "", false, noon - 11 * min),
            headphones = true, headphonesSince = noon - 10 * min), noon))
    }

    @Test fun justPausedStaysForTenMinutes() {
        val m = Rules.listening(Signals(playing = SensoPlaying("p", "Teardrop", "", false, noon - 4 * min)), noon)
        assertEquals("Paused: Teardrop", m?.why)
        assertTrue(m!!.confidence >= Rules.THRESHOLD)
        assertNull(Rules.listening(Signals(playing = SensoPlaying("p", "Teardrop", "", false, noon - 10 * min)), noon))
    }

    @Test fun dismissedIsNeverOffered() {
        val s = Signals(rain = SensoRain(noon + 30 * min, 0.9f))
        assertNull(offer(s, dismissed = setOf(Kind.RAIN_COMING)))
        assertTrue(Rules.read(s, noon, zone, setOf(Kind.RAIN_COMING)).first().dismissed)
    }

    @Test fun oneAtATimeAndHeldWithoutFlicker() {
        val s = Signals(rain = SensoRain(noon + 30 * min, 0.82f),
            playing = SensoPlaying("p", "T", "", true, noon), headphones = true)
        // Listening (0.90) is surer than rain (0.82), but rain is held: it must be beaten by OVERTAKE
        assertEquals(Kind.RAIN_COMING, offer(s, held = Kind.RAIN_COMING)?.kind)
        assertEquals(Kind.LISTENING, offer(s)?.kind)
        // A held moment slipping just under the threshold stays
        val weak = Signals(rain = SensoRain(noon + 30 * min, 0.55f))
        assertEquals(Kind.RAIN_COMING, offer(weak, held = Kind.RAIN_COMING)?.kind)
        assertNull(offer(weak))
    }

    @Test fun appOnlyHearsItsKinds() {
        val s = Signals(lux = 2f, charging = true)
        val late = at(23, 30)
        assertNotNull(Rules.pick(Rules.read(s, late, zone)))
        assertNull(offer(s, late))
        assertEquals(Kind.WINDING_DOWN, Rules.read(s, late, zone).first().kind)
    }

    @Test fun readingNeedsTheMainScreenAndTime() {
        val s = Signals(unfolded = true, front = "com.reader", frontSince = noon - 20 * min)
        assertNotNull(Rules.reading(s, noon))
        assertNull(Rules.reading(s.copy(unfolded = false), noon))
        assertNull(Rules.reading(s.copy(homes = setOf("com.reader")), noon))
        assertNull(Rules.reading(s.copy(frontSince = noon - 3 * min), noon))
    }

    @Test fun wakingJustOffTheCharger() {
        val m = Rules.waking(Signals(charging = false, chargingSince = at(7, 50)), at(8), zone)!!
        assertTrue(m.confidence >= Rules.THRESHOLD)
        assertNull(Rules.waking(Signals(), at(12), zone))
    }

    @Test fun occasionInTheMorning() {
        val o = SensoEvent("Compleanno", at(0), at(23, 59), null, allDay = true)
        assertEquals(Kind.OCCASION, Rules.pick(Rules.read(Signals(occasion = o), at(9), zone))?.kind)
        assertNull(Rules.pick(Rules.read(Signals(occasion = o), at(14), zone)))
        assertNull(Rules.occasion(Signals(occasion = o), at(20), zone))
    }

    @Test fun sunsetOnADryEvening() {
        // 18 Nov 2026, Leeds: sunset about 16:05
        val set = Sky.day(Sky.Place.LEEDS, java.time.LocalDate.of(2026, 11, 18), zone).sunset!!.toEpochMilli()
        val m = Rules.sunset(Signals(), set - 10 * min, zone)
        assertNotNull(m)
        assertTrue(m!!.why.startsWith("Sunset at "))
        assertNull(Rules.sunset(Signals(), set - 60 * min, zone))
        assertNull(Rules.sunset(Signals(), set + 5 * min, zone))
        assertNull(Rules.sunset(Signals(rain = SensoRain(set, 0.8f)), set - 10 * min, zone))
    }
}
