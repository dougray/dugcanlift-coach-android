package com.dugcanlift.coach.data

import com.dugcanlift.kit.CompactEncoding
import org.json.JSONObject

/**
 * The shell every plan link shares: `v`, `t`, `l`, `n`, and the `1z`/`1u`
 * envelope around the JSON.
 *
 * One copy, used by both halves of the plan -- [CookPlanEncoder] for the food
 * and [TrainPlanEncoder] for the training -- because a coach sending training
 * and a coach sending a week are sending the same kind of link to the same
 * decoder. Two copies of the envelope is how one of them ends up with a `v` the
 * other does not have.
 *
 * `PLAN-FORMAT.md` in the coach site repo is the written contract.
 */
internal object PlanEnvelope {

    private const val VERSION = 1

    /** Where a plan link points: LIFT's own page, which reads the fragment. */
    const val LIFT_URL = "https://www.dugcanlift.com/lift/"

    /**
     * Mail clients wrap and corrupt long links. The same 16,000-character
     * ceiling Coach web works to (`RISKY_LINK_LENGTH` in `coach/app.js`) and
     * the one PLAN-FORMAT "Size" states.
     */
    const val RISKY_LINK_LENGTH = 16000

    /**
     * @param lifterId the client's id. The decoder refuses a fragment whose `l`
     *   is not the reader's own id (`PlanDecodeResult.NotAddressedToYou`),
     *   which is what stops one client opening another's plan.
     */
    fun payload(lifterId: String, coachName: String): JSONObject = JSONObject()
        .put("v", VERSION)
        .put("t", "plan")
        .put("l", lifterId)
        .put("n", coachName)

    /** The fragment, without a leading `#`. */
    fun fragment(payload: JSONObject): String {
        val json = payload.toString().toByteArray(Charsets.UTF_8)
        return try {
            "1z" + CompactEncoding.base64Url(CompactEncoding.deflateRaw(json))
        } catch (e: Exception) {
            // Uncompressed is a valid encoding, not a failure: the decoder reads
            // `1u` too. A longer link beats no link.
            "1u" + CompactEncoding.base64Url(json)
        }
    }

    /** The whole link a coach sends: the fragment on LIFT's address. */
    fun link(payload: JSONObject): String = LIFT_URL + "#" + fragment(payload)
}
