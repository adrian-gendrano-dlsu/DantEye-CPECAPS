package edu.dlsu.esca_3_2526_c3.dms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CPECAPS: high speed raises the risk one tier when the driver shows a sign. */
class SpeedRiskTest {
    private fun sev(drowsy: Double = 0.0, gaze: Double = 0.0) = SeverityReport(
        Severity(drowsy, ""), Severity(0.0, ""), Severity(gaze, ""), Severity(0.0, ""))

    private fun assess(s: SeverityReport, kmh: Double) =
        DriverRiskModel(Config()).assess(s, ContextReading(speedKmh = kmh, motionG = 0.0, speedSource = "gps"))

    @Test
    fun ruleCountIs93() {
        val n = Rules.fatigueRules().size + Rules.distractionRules().size + Rules.contextRules().size + Rules.overallRules().size
        assertEquals(93, n)
    }

    @Test
    fun noSignStaysNoneAtHighSpeed() {
        assertEquals("NONE", assess(sev(), 110.0).rawTier)
    }

    @Test
    fun highSpeedRaisesASignOneTier() {
        val normal = assess(sev(drowsy = 0.66), 40.0)
        val fast = assess(sev(drowsy = 0.66), 110.0)
        val i = RISK_TIERS.indexOf(normal.rawTier)
        assertTrue("normal speed should give a tier above NONE, got ${normal.rawTier}", i > 0)
        assertEquals(RISK_TIERS[minOf(3, i + 1)], fast.rawTier)
        assertTrue(fast.risk > normal.risk)
    }

    @Test
    fun stoppedStillLowersTheRisk() {
        val normal = assess(sev(drowsy = 0.66), 40.0)
        val stopped = assess(sev(drowsy = 0.66), 0.0)
        assertTrue(stopped.risk < normal.risk)
    }
}
