package edu.dlsu.esca_3_2526_c3.monitor

import edu.dlsu.esca_3_2526_c3.dms.Detection
import edu.dlsu.esca_3_2526_c3.dms.EngineOutput
import edu.dlsu.esca_3_2526_c3.dms.FaceObservation
import edu.dlsu.esca_3_2526_c3.dms.FrameFeatures
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File

/**
 * Developer mode > Detailed recording: everything the face model and the detectors saw in each picture,
 * next to frames.jsonl (which keeps the desktop format for scripts/evaluate_behaviors.py):
 *   frames_detail.jsonl  per picture: t, i (same as frames.jsonl), picture size, face box, the landmark points
 *                        of the eyes, lips (outer + inner), nose, chin and face outline, all 52 blendshapes,
 *                        every evidence source (eyes, mouth) with the fused value, head pose, eye-classifier
 *                        probability, every YOLO box, and why the eyes were not judged (if so).
 * About 3-4 KB per picture (~30 MB per 10 minutes at 15 pictures per second). Measurements only, no pictures.
 */
class DetailLog(dir: File) {
    private val out: BufferedWriter = File(dir.apply { mkdirs() }, "frames_detail.jsonl").bufferedWriter()
    private var n = 0
    @Volatile private var closed = false

    private fun r(x: Double, d: Int = 2): Double { val f = Math.pow(10.0, d.toDouble()); return Math.round(x * f) / f }

    private fun points(lm: Array<DoubleArray>, idx: IntArray) = JSONArray().apply {
        for (i in idx) if (i < lm.size) put(JSONArray().put(r(lm[i][0], 1)).put(r(lm[i][1], 1)).put(r(lm[i].getOrElse(2) { 0.0 }, 1)))
    }

    @Synchronized
    fun log(index: Int, t: Double, width: Int, height: Int, face: FaceObservation?, dets: List<Detection>,
            f: FrameFeatures, out2: EngineOutput) {
        if (closed) return
        val rec = JSONObject().put("t", r(t, 4)).put("i", index).put("image", JSONArray().put(width).put(height))
        if (face != null) {
            val lm = face.landmarks
            rec.put("face_box", JSONArray().apply { face.bbox.forEach { put(r(it, 1)) } })
            rec.put("landmarks", JSONObject()
                .put("right_eye", points(lm, RIGHT_EYE)).put("left_eye", points(lm, LEFT_EYE))
                .put("lips_outer", points(lm, LIPS_OUTER)).put("lips_inner", points(lm, LIPS_INNER))
                .put("nose", points(lm, NOSE)).put("chin", points(lm, CHIN)).put("face_oval", points(lm, FACE_OVAL)))
            rec.put("blendshapes", JSONObject().apply { for ((k, v) in face.blendshapes) put(k, r(v, 3)) })
        } else rec.put("face_box", JSONObject.NULL)
        val s = out2.states
        rec.put("eye", JSONObject().put("sources", JSONObject().apply { for ((k, v) in s.eyeSources) put(k, r(v, 3)) })
            .put("fused", s.eyeEvidence?.let { r(it, 3) } ?: JSONObject.NULL).put("closed", s.eyesClosed ?: JSONObject.NULL)
            .put("not_judged", s.eyesNotJudged ?: JSONObject.NULL)
            .put("ear_left", f.earLeft ?: JSONObject.NULL).put("ear_right", f.earRight ?: JSONObject.NULL)
            .put("classifier_p_closed", f.eyeClosedProb ?: JSONObject.NULL).put("eye_width_px", f.eyeWidthPx ?: JSONObject.NULL))
        rec.put("mouth", JSONObject().put("sources", JSONObject().apply { for ((k, v) in s.mouthSources) put(k, r(v, 3)) })
            .put("fused", s.mouthEvidence?.let { r(it, 3) } ?: JSONObject.NULL).put("open", s.mouthOpen ?: JSONObject.NULL)
            .put("mar", f.mar ?: JSONObject.NULL).put("jaw_open", f.jawOpen ?: JSONObject.NULL))
        rec.put("pose", JSONObject().put("yaw", f.yaw ?: JSONObject.NULL).put("pitch", f.pitch ?: JSONObject.NULL)
            .put("roll", f.roll ?: JSONObject.NULL).put("evidence", s.poseEvidence ?: JSONObject.NULL)
            .put("head_away", s.headAway ?: JSONObject.NULL).put("face_status", s.faceStatus))
        rec.put("detections", JSONArray().apply {
            for (d in dets) put(JSONObject().put("label", d.label).put("conf", r(d.confidence, 3)).put("role", d.role ?: JSONObject.NULL)
                .put("box", JSONArray().apply { d.box.forEach { put(r(it, 1)) } }).put("near_driver", d.inDriverRoi))
        })
        out.write(rec.toString()); out.newLine()
        if (++n % 30 == 0) out.flush()
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        try { out.close() } catch (_: Exception) { }
    }

    companion object {
        val RIGHT_EYE = intArrayOf(33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246)
        val LEFT_EYE = intArrayOf(362, 382, 381, 380, 374, 373, 390, 249, 263, 466, 388, 387, 386, 385, 384, 398)
        val LIPS_OUTER = intArrayOf(61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291, 409, 270, 269, 267, 0, 37, 39, 40, 185)
        val LIPS_INNER = intArrayOf(78, 95, 88, 178, 87, 14, 317, 402, 318, 324, 308, 415, 310, 311, 312, 13, 82, 81, 80, 191)
        val NOSE = intArrayOf(1, 4, 5, 195, 197, 6, 168)
        val CHIN = intArrayOf(152, 148, 176, 149, 150, 377, 400, 378, 379)
        val FACE_OVAL = intArrayOf(10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377, 152,
            148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109)
    }
}
