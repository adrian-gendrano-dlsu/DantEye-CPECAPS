package edu.dlsu.esca_3_2526_c3.dms

/**
 * CPECAPS: the camera's current crop as reported by the ESP32-CAM (GET /camera), in full-sensor pixels
 * (OV2640: 1600 x 1200). zoom / maxZoom: the camera's current and strongest zoom factor.
 */
data class CameraWindow(
    val x: Double, val y: Double, val w: Double, val h: Double,
    val sensorW: Double, val sensorH: Double,
    val zoom: Double, val maxZoom: Double,
)

/** Zoom factor and crop centre (share of the sensor, 0..1) to send to the camera (/camera?zoom=&cx=&cy=). */
data class ZoomCommand(val zoom: Double, val cx: Double, val cy: Double)

/**
 * CPECAPS: zoom the camera to the driver. At the start of a trip the face is measured on a few pictures;
 * the camera is then cropped so the face fills about [targetFaceShare] of the picture width and sits in the
 * middle. A share of 0.30 leaves room for turning the head and for a phone next to the face.
 * The zoom is chosen once per trip (calibration starts again afterwards), so the measurements of one trip
 * all come from the same view.
 */
class AutoZoom(
    val targetFaceShare: Double = 0.30,
    val samplesNeeded: Int = 15,
    /** Smaller zoom changes than this (relative) are not worth a new calibration. */
    val minChange: Double = 0.08,
) {
    private val shares = ArrayList<Double>()
    private val centersX = ArrayList<Double>()
    private val centersY = ArrayList<Double>()

    val samples: Int get() = shares.size
    val ready: Boolean get() = shares.size >= samplesNeeded

    fun reset() { shares.clear(); centersX.clear(); centersY.clear() }

    /** Add one picture's face box [x1, y1, x2, y2] (pixels). Returns true when enough pictures are collected. */
    fun add(faceBox: DoubleArray, imageW: Int, imageH: Int): Boolean {
        if (imageW <= 0 || imageH <= 0 || faceBox.size < 4) return ready
        val w = faceBox[2] - faceBox[0]
        if (w <= 0) return ready
        shares += w / imageW
        centersX += ((faceBox[0] + faceBox[2]) / 2) / imageW
        centersY += ((faceBox[1] + faceBox[3]) / 2) / imageH
        return ready
    }

    /** Face width as a share of the picture width (median of the samples), or null before any sample. */
    fun faceShare(): Double? = if (shares.isEmpty()) null else Num.median(shares)

    /**
     * The zoom to send, or null when the view is already about right. The face centre is moved from
     * picture coordinates to sensor coordinates with the camera's current window.
     */
    fun command(window: CameraWindow): ZoomCommand? {
        val share = faceShare() ?: return null
        val cxImg = Num.median(centersX)
        val cyImg = Num.median(centersY)
        val cx = ((window.x + cxImg * window.w) / window.sensorW).coerceIn(0.0, 1.0)
        val cy = ((window.y + cyImg * window.h) / window.sensorH).coerceIn(0.0, 1.0)
        val wanted = (window.zoom * targetFaceShare / share).coerceIn(1.0, maxOf(1.0, window.maxZoom))
        val zoomChange = kotlin.math.abs(wanted - window.zoom) / window.zoom
        // Off-centre by more than a tenth of the picture is worth fixing even without a zoom change.
        val offCentre = kotlin.math.abs(cxImg - 0.5) > 0.1 || kotlin.math.abs(cyImg - 0.5) > 0.1
        if (zoomChange < minChange && !(offCentre && wanted > 1.0)) return null
        return ZoomCommand(Num.pyRound(wanted, 2), Num.pyRound(cx, 3), Num.pyRound(cy, 3))
    }
}
