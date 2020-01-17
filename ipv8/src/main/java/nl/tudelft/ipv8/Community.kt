package nl.tudelft.ipv8

import android.util.Log
import nl.tudelft.ipv8.exception.PacketDecodingException
import nl.tudelft.ipv8.keyvault.LibNaClPK
import nl.tudelft.ipv8.keyvault.PrivateKey
import nl.tudelft.ipv8.messaging.Endpoint
import nl.tudelft.ipv8.messaging.Packet
import nl.tudelft.ipv8.messaging.Serializable
import nl.tudelft.ipv8.messaging.payload.*
import nl.tudelft.ipv8.peerdiscovery.Network
import nl.tudelft.ipv8.util.hexToBytes
import java.util.*

abstract class Community(
    override val myPeer: Peer,
    override val endpoint: Endpoint,
    override val network: Network,
    val maxPeers: Int = DEFAULT_MAX_PEERS
) : Overlay {
    abstract val serviceId: String

    protected val prefix: ByteArray
        get() = ByteArray(0) + 0.toByte() + VERSION + serviceId.hexToBytes()

    var myEstimatedWan: Address = Address("0.0.0.0", 0)
    var myEstimatedLan: Address = Address("0.0.0.0", 0)

    private var lastBootstrap: Date? = null

    internal val messageHandlers = mutableMapOf<Int, (Packet) -> Unit>()

    init {
        messageHandlers[MessageId.PUNCTURE_REQUEST] = ::handlePunctureRequest
        messageHandlers[MessageId.PUNCTURE] = ::handlePuncture
        messageHandlers[MessageId.INTRODUCTION_REQUEST] = ::handleIntroductionRequest
        messageHandlers[MessageId.INTRODUCTION_RESPONSE] = ::handleIntroductionResponse
    }

    override fun load() {
        super.load()

        network.registerServiceProvider(serviceId, this)
        network.blacklistMids.add(myPeer.mid)
        network.blacklist.addAll(DEFAULT_ADDRESSES)

        // TODO: Get LAN address
    }

    override fun bootstrap() {
        if (Date().time - (lastBootstrap?.time ?: 0L) < BOOTSTRAP_TIMEOUT_MS) return
        lastBootstrap = Date()

        for (socketAddress in DEFAULT_ADDRESSES) {
            walkTo(socketAddress)
        }
    }

    override fun walkTo(address: Address) {
        val packet = createIntroductionRequest(address)
        send(address, packet)
    }

    /**
     * Get a new introduction, or bootstrap if there are no available peers.
     */
    override fun getNewIntroduction(fromPeer: Peer?) {
        var peer = fromPeer

        if (peer == null) {
            val available = getPeers()
            if (available.isNotEmpty()) {
                peer = available.random()
            } else {
                bootstrap()
                return
            }
        }

        val packet = createIntroductionRequest(peer.address)
        send(peer.address, packet)
    }

    override fun getPeerForIntroduction(exclude: Peer?): Peer? {
        val available = getPeers() - exclude
        return if (available.isNotEmpty()) {
            available.random()
        } else {
            null
        }
    }

    override fun getWalkableAddresses(): List<Address> {
        return network.getWalkableAddresses(serviceId)
    }

    override fun getPeers(): List<Peer> {
        return network.getPeersForService(serviceId)
    }

    override fun onPacket(packet: Packet) {
        val sourceAddress = packet.source
        val data = packet.data

        val probablePeer = network.getVerifiedByAddress(sourceAddress)
        if (probablePeer != null) {
            probablePeer.lastResponse = Date()
        }

        val packetPrefix = data.copyOfRange(0, prefix.size)
        if (!packetPrefix.contentEquals(prefix)) {
            Log.d("Community", "prefix not matching")
            return
        }

        val msgId = data.copyOfRange(prefix.size, prefix.size + 1).first()
        val handler = messageHandlers[msgId.toUByte().toInt()]

        if (handler != null) {
            try {
                handler(packet)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            Log.d(TAG, "Received unknown message $msgId from $sourceAddress")
        }
    }

    /*
     * Introduction and puncturing requests creation
     */

    internal fun createIntroductionRequest(socketAddress: Address): ByteArray {
        val globalTime = claimGlobalTime()
        val payload =
            IntroductionRequestPayload(
                socketAddress,
                myEstimatedLan,
                myEstimatedWan,
                true,
                ConnectionType.UNKNOWN,
                (globalTime % 65536u).toInt()
            )
        val auth = BinMemberAuthenticationPayload(myPeer.publicKey.keyToBin())
        val dist = GlobalTimeDistributionPayload(globalTime)

        Log.d("Community", "-> $payload")

        return serializePacket(prefix, MessageId.INTRODUCTION_REQUEST, listOf(auth, dist, payload))
    }

    internal fun createIntroductionResponse(
        lanSocketAddress: Address,
        socketAddress: Address,
        identifier: Int,
        introduction: Peer? = null
    ): ByteArray {
        val globalTime = claimGlobalTime()
        var introductionLan = Address("0.0.0.0", 0)
        var introductionWan = Address("0.0.0.0", 0)
        var introduced = false
        val other = network.getVerifiedByAddress(socketAddress)
        var intro = introduction
        if (intro == null) {
            intro = getPeerForIntroduction(exclude = other)
        }
        if (intro != null) {
            // TODO: understand how this works
            if (addressIsLan(intro.address)) {
                introductionLan = intro.address
                introductionWan = Address(myEstimatedWan.ip, introductionLan.port)
            } else {
                introductionWan = intro.address
            }
            introduced = true
        }
        val payload = IntroductionResponsePayload(
            socketAddress,
            myEstimatedLan,
            myEstimatedWan,
            introductionLan,
            introductionWan,
            ConnectionType.UNKNOWN,
            false,
            identifier
        )
        val auth = BinMemberAuthenticationPayload(myPeer.publicKey.keyToBin())
        val dist = GlobalTimeDistributionPayload(globalTime)

        if (introduced) {
            // TODO: Seems like a bad practice to send a packet in the create method...
            val packet = createPunctureRequest(lanSocketAddress, socketAddress, identifier)
            val punctureRequestAddress = if (introductionLan.isEmpty())
                introductionWan else introductionLan
            send(punctureRequestAddress, packet)
        }

        Log.d("Community", "-> $payload")

        return serializePacket(prefix, MessageId.INTRODUCTION_RESPONSE, listOf(auth, dist, payload))
    }

    internal fun createPuncture(lanWalker: Address, wanWalker: Address, identifier: Int): ByteArray {
        val globalTime = claimGlobalTime()
        val payload = PuncturePayload(lanWalker, wanWalker, identifier)
        val auth = BinMemberAuthenticationPayload(myPeer.publicKey.keyToBin())
        val dist = GlobalTimeDistributionPayload(globalTime)

        Log.d("Community", "-> $payload")

        return serializePacket(prefix, MessageId.PUNCTURE, listOf(auth, dist, payload))
    }

    internal fun createPunctureRequest(lanWalker: Address, wanWalker: Address, identifier: Int): ByteArray {
        Log.d("Community", "-> punctureRequest")
        val globalTime = claimGlobalTime()
        val payload = PunctureRequestPayload(lanWalker, wanWalker, identifier)
        val dist = GlobalTimeDistributionPayload(globalTime)
        return serializePacket(prefix, MessageId.PUNCTURE_REQUEST, listOf(dist, payload), sign = false)
    }

    /**
     * Serializes multiple payloads into a binary packet that can be sent over the transport.
     *
     * @param prefix The packet prefix consisting of a zero byte, version, and master peer mid
     * @param messageId The message type ID
     * @param payload The list of payloads
     * @param sign True if the packet should be signed
     */
    protected fun serializePacket(
        prefix: ByteArray,
        messageId: Int,
        payload: List<Serializable>,
        sign: Boolean = true
    ): ByteArray {
        var packet = prefix
        packet += messageId.toChar().toByte()

        for (item in payload) {
            packet += item.serialize()
        }

        val myPeerKey = myPeer.key
        if (sign && myPeerKey is PrivateKey) {
            packet += myPeerKey.sign(packet)
        }

        return packet
    }

    /*
     * Request deserialization
     */

    internal fun deserializeIntroductionRequest(packet: Packet): Triple<Peer, GlobalTimeDistributionPayload, IntroductionRequestPayload> {
        val (peer, remainder) = unwrapAuthPacket(packet)
        val (dist, distSize) = GlobalTimeDistributionPayload.deserialize(remainder)
        val (payload, _) = IntroductionRequestPayload.deserialize(remainder, distSize)
        return Triple(peer, dist, payload)
    }

    private fun handleIntroductionRequest(packet: Packet) {
        val (peer, dist, payload) = deserializeIntroductionRequest(packet)
        onIntroductionRequest(peer, dist, payload)
    }

    internal fun handleIntroductionResponse(packet: Packet) {
        val (peer, remainder) = unwrapAuthPacket(packet)
        val (dist, distSize) = GlobalTimeDistributionPayload.deserialize(remainder)
        val (payload, _) = IntroductionResponsePayload.deserialize(remainder, distSize)
        onIntroductionResponse(peer, dist, payload)
    }

    internal fun handlePuncture(packet: Packet) {
        val (peer, remainder) = unwrapAuthPacket(packet)
        val (dist, distSize) = GlobalTimeDistributionPayload.deserialize(remainder)
        val (payload, _) = PuncturePayload.deserialize(remainder, distSize)
        onPuncture(peer, dist, payload)
    }

    internal fun handlePunctureRequest(packet: Packet) {
        val remainder = unwrapUnauthPacket(packet)
        val (dist, distSize) = GlobalTimeDistributionPayload.deserialize(remainder)
        val (payload, _) = PunctureRequestPayload.deserialize(remainder, distSize)
        onPunctureRequest(packet.source, dist, payload)
    }

    /**
     * Checks the signature of the authenticated packet and returns the packet payloads excluding
     * BinMemberAuthenticationPayload if it is valid.
     *
     * @throws PacketDecodingException if the signature is invalid
     */
    protected fun unwrapAuthPacket(packet: Packet): Pair<Peer, ByteArray> {
        val bytes = packet.data

        // prefix + message type
        val authOffset = prefix.size + 1
        val (auth, authSize) = BinMemberAuthenticationPayload.deserialize(bytes, authOffset)
        val publicKey = LibNaClPK.fromBin(auth.publicKey)
        val signatureOffset = bytes.size - publicKey.getSignatureLength()
        val signature = bytes.copyOfRange(signatureOffset, bytes.size)

        // Verify signature
        val message = bytes.copyOfRange(0, signatureOffset)
        val isValidSignature = publicKey.verify(signature, message)
        if (!isValidSignature)
            throw PacketDecodingException("Incoming packet has an invalid signature")

        // Return the peer and remaining payloads
        val peer = Peer(LibNaClPK.fromBin(auth.publicKey), packet.source)
        val remainder = bytes.copyOfRange(authOffset + authSize, bytes.size - publicKey.getSignatureLength())
        return Pair(peer, remainder)
    }

    /**
     * Returns the packet payload excluding the prefix and message type.
     */
    protected fun unwrapUnauthPacket(packet: Packet): ByteArray {
        return packet.data.copyOfRange(prefix.size + 1, packet.data.size)
    }

    /*
     * Request handling
     */

    internal open fun onIntroductionRequest(
        peer: Peer,
        dist: GlobalTimeDistributionPayload,
        payload: IntroductionRequestPayload
    ) {
        Log.d("Community", "<- $payload")

        network.addVerifiedPeer(peer)
        network.discoverServices(peer, listOf(serviceId))

        val packet = createIntroductionResponse(
            payload.destinationAddress,
            peer.address,
            payload.identifier
        )

        send(peer.address, packet)
    }

    internal open fun onIntroductionResponse(
        peer: Peer,
        dist: GlobalTimeDistributionPayload,
        payload: IntroductionResponsePayload
    ) {
        Log.d("Community", "<- $payload")

        myEstimatedWan = payload.destinationAddress

        network.addVerifiedPeer(peer)
        network.discoverServices(peer, listOf(serviceId))

        // TODO: understand, document and test all cases

        if (!payload.wanIntroductionAddress.isEmpty() &&
            payload.wanIntroductionAddress.ip != myEstimatedWan.ip) {
            if (!payload.lanIntroductionAddress.isEmpty()) {
                network.discoverAddress(peer, payload.lanIntroductionAddress, serviceId)
            }
            network.discoverAddress(peer, payload.wanIntroductionAddress, serviceId)
        } else if (!payload.lanIntroductionAddress.isEmpty() &&
            payload.wanIntroductionAddress.ip == myEstimatedWan.ip) {
            network.discoverAddress(peer, payload.lanIntroductionAddress, serviceId)
        } else if (!payload.wanIntroductionAddress.isEmpty()) {
            network.discoverAddress(peer, payload.wanIntroductionAddress, serviceId)
            network.discoverAddress(peer,
                Address(myEstimatedLan.ip, payload.wanIntroductionAddress.port), serviceId)
        }
    }

    internal open fun onPuncture(
        peer: Peer,
        dist: GlobalTimeDistributionPayload,
        payload: PuncturePayload
    ) {
        Log.d("Community", "<- $payload")
        // NOOP
    }

    internal open fun onPunctureRequest(
        address: Address,
        dist: GlobalTimeDistributionPayload,
        payload: PunctureRequestPayload
    ) {
        Log.d("Community", "<- $payload")
        var target = payload.wanWalkerAddress
        if (payload.wanWalkerAddress.ip == myEstimatedWan.ip) {
            target = payload.lanWalkerAddress
        }

        val packet = createPuncture(myEstimatedLan, payload.wanWalkerAddress, payload.identifier)
        send(target, packet)
    }

    private fun addressIsLan(address: Address): Boolean {
        // TODO
        return address.ip == "10.0.0.0"
    }

    protected fun send(address: Address, data: ByteArray) {
        val probablePeer = network.getVerifiedByAddress(address)
        if (probablePeer != null) {
            probablePeer.lastRequest = Date()
        }
        endpoint.send(address, data)
    }

    companion object {
        private val DEFAULT_ADDRESSES = listOf(
            // Dispersy
            // Address("130.161.119.206", 6421),
            // Address("130.161.119.206", 6422),
            // Address("131.180.27.155", 6423),
            // Address("131.180.27.156", 6424),
            // Address("131.180.27.161", 6427),
            // IPv8
            /*
            Address("131.180.27.161", 6521),
            Address("131.180.27.161", 6522),
            Address("131.180.27.162", 6523),
            Address("131.180.27.162", 6524),
            Address("130.161.119.215", 6525),
            Address("130.161.119.215", 6526),
            Address("81.171.27.194", 6527),
            Address("81.171.27.194", 6528)
             */
            // IPv8 + LibNaCL
            Address("131.180.27.161", 6427)
        )

        // Timeout before we bootstrap again (bootstrap kills performance)
        private const val BOOTSTRAP_TIMEOUT_MS = 30_000
        private const val DEFAULT_MAX_PEERS = 30

        private const val VERSION: Byte = 2

        private const val TAG = "Community"
    }

    object MessageId {
        const val PUNCTURE_REQUEST = 250
        const val PUNCTURE = 249
        const val INTRODUCTION_REQUEST = 246
        const val INTRODUCTION_RESPONSE = 245
    }
}
