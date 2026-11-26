package edu.dlsu.esca_3_2526_c3.dms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CPECAPS: zoom to the driver (dms/AutoZoom.kt). */
class AutoZoomTest {
    // 160 deg lens cropped to 68 deg: a 680 x 510 window in the middle of the 1600 x 1200 sensor.
    private val wide = CameraWindow(460.0, 345.0, 680.0, 510.0, 1600.0, 1200.0, zoom = 1.0, maxZoom = 2.12)

    private fun feed(z: AutoZoom, box: DoubleArray, n: Int = z.samplesNeeded) { repeat(n) { z.add(box, 640, 480) } }

    @Test
    fun smallFaceZoomsInAroundTheFace() {
        val z = AutoZoom()
        // face 96 px wide (15 % of 640), centred at (320, 240)
        feed(z, doubleArrayOf(272.0, 180.0, 368.0, 300.0), z.samplesNeeded - 1)
        assertFalse(z.ready)
        z.add(doubleArrayOf(272.0, 180.0, 368.0, 300.0), 640, 480)
        assertTrue(z.ready)
        val cmd = z.command(wide)
        assertNotNull(cmd)
        assertEquals(2.0, cmd!!.zoom, 1e-9)      // 0.30 / 0.15
        assertEquals(0.5, cmd.cx, 1e-9)
        assertEquals(0.5, cmd.cy, 1e-9)
    }

    @Test
    fun zoomIsLimitedByTheCamera() {
        val z = AutoZoom()
        feed(z, doubleArrayOf(300.0, 200.0, 340.0, 250.0)) // 40 px: would need x4.8
        assertEquals(2.12, z.command(wide)!!.zoom, 1e-9)
    }

    @Test
    fun faceThatFillsTheViewKeepsIt() {
        val z = AutoZoom()
        feed(z, doubleArrayOf(224.0, 140.0, 416.0, 340.0)) // 30 % already, centred
        assertNull(z.command(wide))
    }

    @Test
    fun bigFaceNeverZoomsOut() {
        val z = AutoZoom()
        feed(z, doubleArrayOf(80.0, 20.0, 560.0, 460.0)) // 75 %
        assertNull(z.command(wide))                      // zoom 1 is the widest view
    }

    @Test
    fun offCentreFaceMovesTheCrop() {
        val z = AutoZoom()
        // face at the right side of the picture (x 480 of 640), 15 % wide
        feed(z, doubleArrayOf(432.0, 180.0, 528.0, 300.0))
        val cmd = z.command(wide)!!
        // sensor x = 460 + 0.75 * 680 = 970 -> 0.606
        assertEquals(970.0 / 1600.0, cmd.cx, 1e-3)
        assertEquals(0.5, cmd.cy, 1e-3)
    }

    @Test
    fun noSamplesNoCommand() {
        val z = AutoZoom()
        assertNull(z.faceShare())
        assertNull(z.command(wide))
        z.add(doubleArrayOf(10.0, 10.0, 5.0, 20.0), 640, 480) // empty box is ignored
        assertEquals(0, z.samples)
    }
}
