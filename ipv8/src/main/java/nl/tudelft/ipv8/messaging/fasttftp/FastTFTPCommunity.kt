package nl.tudelft.ipv8.messaging.fasttftp

import nl.tudelft.ipv8.Community
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.messaging.payload.IntroductionRequestPayload
import nl.tudelft.ipv8.messaging.payload.IntroductionResponsePayload

/**
 * A community that is used only to signal support for TFTP transport. It does not implement
 * any messaging and should not use any discovery strategies.
 */
class FastTFTPCommunity : Community() {
    override val serviceId = SERVICE_ID

    companion object {
        const val SERVICE_ID = "72488436558bab6d1794fe980a2c1441d1f1df88"
    }
}
