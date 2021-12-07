package nl.tudelft.ipv8.messaging.eva

import kotlinx.coroutines.*
import mu.KotlinLogging
import nl.tudelft.ipv8.Community
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.keyvault.Key
import nl.tudelft.ipv8.messaging.Packet
import nl.tudelft.ipv8.util.toHex
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*
import kotlin.math.max

private val logger = KotlinLogging.logger {}

open class EVAProtocol(
    private var community: Community,
    scope: CoroutineScope,
    private var blockSize: Int = BLOCK_SIZE,
    private var windowSizeInBlocks: Int = WINDOW_SIZE_IN_BLOCKS,
    private var startMessageID: Int = START_MESSAGE_ID,
    private var retransmitIntervalInSec: Int = RETRANSMIT_INTERVAL_IN_SEC,
    private var retransmitAttemptCount: Int = RETRANSMIT_ATTEMPT_COUNT,
    private var scheduledSendIntervalInSec: Int = SCHEDULED_SEND_INTERVAL_IN_SEC,
    private var timeoutIntervalInSec: Int = TIMEOUT_INTERVAL_IN_SEC,
    private var binarySizeLimit: Int = BINARY_SIZE_LIMIT,
    private var terminateByTimeoutEnabled: Boolean = true
) {
    private var retransmitEnabled = true

    private var scheduled: MutableMap<Key, Queue<ScheduledTransfer>> = mutableMapOf()
    private var incoming: MutableMap<Key, Transfer> = mutableMapOf()
    private var outgoing: MutableMap<Key, Transfer> = mutableMapOf()
    private var finishedIncoming: MutableMap<Key, MutableSet<String>> = mutableMapOf()
    private var finishedOutgoing: MutableMap<Key, MutableSet<String>> = mutableMapOf()
    private var scheduledTasks = PriorityQueue<ScheduledTask>()

    lateinit var onReceiveProgressCallback: (peer: Peer, info: String, progress: TransferProgress) -> Unit
    lateinit var onReceiveCompleteCallback: (peer: Peer, info: String, id: String, dataBinary: ByteArray?) -> Unit
    lateinit var onSendCompleteCallback: (peer: Peer, info: String, dataBinary: ByteArray?, nonce: ULong) -> Unit
    lateinit var onErrorCallback: (peer: Peer, exception: TransferException) -> Unit

    init {
        logger.debug {
            "EVAPROTOCOL: Initialized. Block size: ${blockSize}. Window size: ${windowSizeInBlocks}." +
                "EVAPROTOCOL: Start message id: ${startMessageID}. Retransmit interval: ${retransmitIntervalInSec}sec." +
                "EVAPROTOCOL: Max retransmit attempts: ${retransmitAttemptCount}. Timeout: ${timeoutIntervalInSec}sec." +
                "EVAPROTOCOL: Scheduled send interval: ${scheduledSendIntervalInSec}sec." +
                "EVAPROTOCOL: Binary size limit: ${binarySizeLimit}."
        }

        /**
         * Coroutine that periodically executes the send scheduled transfers function
         * with a send interval as the delay
         */
        scope.launch {
            while (isActive) {
                sendScheduled()

                delay((scheduledSendIntervalInSec * 1000).toLong())
            }
        }

        /**
         * Coroutine that periodically checks whether there are scheduled tasks planned in the queue
         * and if so, if its time to be executed. Every second this is checked.
         */
        scope.launch {
            while (isActive) {
                while (scheduledTasks.any() && scheduledTasks.peek() != null && scheduledTasks.peek()!!.atTime <= Date().time) {
                    val task = scheduledTasks.poll()!!

                    task.action.invoke()
                }

                delay(1000L)
            }
        }
    }

    /**
     * Get peer if it is connected to the network
     * @param key the key of the peer
     * @return the requested peer or null if not connected
     */
    private fun getConnectedPeer(key: Key): Peer? {
        return community.getPeers().firstOrNull {
            it.key == key
        }
    }

    /**
     * Register an anonymous task to be executed at a particular time
     *
     * @param atTime the time of execution
     * @param task the task/action that must be executed
     */
    private fun scheduleTask(atTime: Long, task: () -> Unit) {
        scheduledTasks.add(
            ScheduledTask(
                atTime,
                task
            )
        )
    }

    /**
     * Send an EVA packet over an endpoint defined in the community
     *
     * @param peer the peer to send to
     * @param packet the packet to send to the peer
     */
    private fun send(peer: Peer, packet: ByteArray) {
        community.endpoint.send(peer, packet)
    }

    /**
     * Check if the transfer of an outgoing file is already scheduled
     *
     * @param key the key of the receiver
     * @param id an id of the file
     */
    private fun isScheduled(key: Key, id: String): Boolean {
        return scheduled.containsKey(key) && scheduled[key]!!.any {
            it.id == id
        }
    }

    /**
     * Check if the transfer of an outgoing file has already started
     *
     * @param key the key of the receiver
     * @param id an id of the file
     */
    private fun isOutgoing(key: Key, id: String): Boolean {
        return outgoing.any {
            it.key == key && it.value.id == id
        }
    }

    /**
     * Check if the transfer of an incoming file has already started
     *
     * @param key the key of the sender
     * @param id an id of the file
     */
    private fun isIncoming(key: Key, id: String): Boolean {
        return incoming.any {
            it.key == key && it.value.id == id
        }
    }

    /**
     * Check if the transfer of an outgoing or incoming file has already been transferred
     *
     * @param key the key of the receiver
     * @param id an id of the file
     * @param container incoming or outgoing finished set containing sent/received file id's
     */
    private fun isTransferred(
        key: Key,
        id: String,
        container: MutableMap<Key, MutableSet<String>>
    ): Boolean {
        return if (container.containsKey(key)) {
            container[key]!!.contains(id)
        } else false
    }

    /**
     * Entrypoint to send binary data using the EVA protocol.
     *
     * @param peer the address to deliver the data
     * @param info string that identifies to which communitu or class it should be delivered
     * @param id file/data identifier that identifies the sent data
     * @param data serialized packet in bytes
     * @param nonce an optional unique number that identifies this transfer
     */
    fun sendBinary(
        peer: Peer,
        info: String,
        id: String,
        data: ByteArray,
        nonce: Long? = null
    ) {
        if (info.isEmpty() || id.isEmpty() || data.isEmpty() || peer == community.myPeer) return

        // Stop if a transfer of the requested file is already scheduled, outgoing or transferred
        if (isScheduled(peer.key, id) || isOutgoing(peer.key, id) || isTransferred(peer.key, id, finishedOutgoing)) return

        val infoBinary = info.toByteArray(Charsets.UTF_8)
        val nonceValue = (nonce ?: (0..MAX_NONCE).random()).toULong()

        logger.debug { "EVAPROTOCOL: Sending binary with id '$id', nonce '$nonceValue' for info '$info'." }

        if (outgoing.containsKey(peer.key) || incoming.containsKey(peer.key) || getConnectedPeer(peer.key) == null) {
            if (!isScheduled(peer.key, id)) {
                scheduled.addValue(
                    peer.key,
                    ScheduledTransfer(infoBinary, data, nonceValue, id, 0)
                )

                onReceiveProgressCallback(
                    peer,
                    info,
                    TransferProgress(
                        id,
                        TransferState.SCHEDULED,
                        0.0
                    )
                )
            }

            return
        }

        startOutgoingTransfer(peer, infoBinary, id, data, nonceValue)
    }

    /**
     * Start an outgoing transfer of data
     *
     * @param peer the receiver of the data
     * @param infoBinary a string converted to bytes that defines the requested class/community
     * @param id unique file/data identifier that enables identification on sender and receiver side
     * @param data the data in bytes
     * @param nonce an unique number that defines this transfer
     */
    private fun startOutgoingTransfer(peer: Peer, infoBinary: ByteArray, id: String, data: ByteArray, nonce: ULong) {
        logger.debug { "EVAPROTOCOL: Start outgoing transfer." }

        val info = String(infoBinary, Charsets.UTF_8)

        if (getConnectedPeer(peer.key) == null ||
            isOutgoing(peer.key, id) ||
            isTransferred(peer.key, id, finishedOutgoing) ||
            outgoing.containsKey(peer.key) ||
            incoming.containsKey(peer.key)
        ) {
            if (!isScheduled(peer.key, id)) {
                scheduled.addValue(
                    peer.key,
                    ScheduledTransfer(infoBinary, data, nonce, id, 0)
                )

                onReceiveProgressCallback(
                    peer,
                    info,
                    TransferProgress(
                        id,
                        TransferState.SCHEDULED,
                        0.0
                    )
                )
            }

            return
        }

        val dataSize = data.size
        val blockCount = BigDecimal(dataSize).divide(BigDecimal(blockSize), RoundingMode.UP).toInt()

        val scheduledTransfer = scheduled[peer.key]?.firstOrNull { it.id == id } ?: ScheduledTransfer(
            infoBinary,
            byteArrayOf(),
            nonce,
            id,
            blockCount
        )

        val transfer = Transfer(TransferType.OUTGOING, scheduledTransfer)

        logger.debug { "EVAPROTOCOL: Outgoing transfer blockCount: ${transfer.blockCount}, size: ${dataSize}, nonce: ${transfer.nonce}"}

        if (dataSize > binarySizeLimit) {
            notifyError(
                peer,
                SizeException(
                    "Current data size limit(${binarySizeLimit}) has been exceeded",
                    info,
                    transfer
                )
            )
            return
        }

//        transfer.blockCount = BigDecimal(dataSize).divide(BigDecimal(blockSize), RoundingMode.UP).toInt()
        transfer.dataBinary = data

        outgoing[peer.key] = transfer
        scheduleTerminate(outgoing, peer, transfer)

        val writeRequestPacket = community.createEVAWriteRequest(dataSize, transfer.blockCount, nonce, id, infoBinary)
        send(peer, writeRequestPacket)

        val packet = Packet(peer.address, writeRequestPacket)

        val (_, des) = packet.getAuthPayload(EVAWriteRequestPayload.Deserializer)

        logger.debug { "EVAPROTOCOL: NONCE IS $nonce AND ${des.nonce}" }
    }

    /**
     * Upon receipt of an EVA write request the incoming transfer is announced and acknowledged
     *
     * @param peer the sender of the write request
     * @param payload information about the coming transfer (size, blocks, nonce, id, class)
     */
    fun onWriteRequest(peer: Peer, payload: EVAWriteRequestPayload) {
        logger.debug { "EVAPROTOCOL: On write request. Nonce: ${payload.nonce}. Peer: ${peer.publicKey.keyToBin().toHex()}. Info: ${String(payload.infoBinary, Charsets.UTF_8)}. BlockCount: ${payload.blockCount}. Payload datasize: ${payload.dataSize}, allowed datasize: $binarySizeLimit" }

        if (isIncoming(peer.key, payload.id) || isTransferred(peer.key, payload.id, finishedIncoming)) return

        val scheduledTransfer = ScheduledTransfer(payload.infoBinary, byteArrayOf(), payload.nonce, payload.id, payload.blockCount)
        val transfer = Transfer(TransferType.INCOMING, scheduledTransfer)

        when {
            payload.dataSize <= 0 ->  {
                incomingError(
                    peer,
                    transfer,
                    ValueException(
                        "Data size can not be less or equal to 0",
                        String(payload.infoBinary, Charsets.UTF_8),
                        transfer
                    )
                )
                return
            }
            payload.dataSize > binarySizeLimit -> {
                incomingError(
                    peer,
                    transfer,
                    SizeException(
                        "Current data size limit($binarySizeLimit) has been exceeded",
                        String(payload.infoBinary, Charsets.UTF_8),
                        transfer
                    )
                )
                return
            }
        }
//        if (payload.dataSize <= 0) {
//            incomingError(peer, transfer, ValueException("Data size can not be less or equal to 0"))
//            return
//        }
//
//        if (payload.dataSize > binarySizeLimit) {
//            val exception = SizeException("Current data size limit($binarySizeLimit) has been exceeded", transfer)
//            incomingError(peer, transfer, exception)
//            return
//        }

        transfer.dataSize = payload.dataSize
//        transfer.blockCount = payload.blockCount
        transfer.windowSize = windowSizeInBlocks
        transfer.attempt = 0

        if (incoming.contains(peer.key) || outgoing.contains(peer.key)) {
            logger.debug { "EVAPROTOCOL: There is already a transfer ongoing with peer" }

            incomingError(
                peer,
                transfer,
                PeerBusyException(
                    "There is already a transfer ongoing with peer",
                    String(payload.infoBinary, Charsets.UTF_8),
                    transfer
                )
            )
            return
        }

        incoming[peer.key] = transfer

        sendAcknowledgement(peer, transfer)
        scheduleTerminate(incoming, peer, transfer)
        scheduleResendAcknowledge(peer, transfer)
    }

    /**
     * Upon receipt of an EVA acknowledgement the blocks are sent in windows. On acknowledgement of
     * the previous window the blocks in the next window are transmitted.
     *
     * @param peer the sender of the acknowledgement
     * @param payload acknowledgement of sent blocks with info about number, windowsize and nonce
     */
    fun onAcknowledgement(peer: Peer, payload: EVAAcknowledgementPayload) {
        logger.debug { "EVAPROTOCOL: On acknowledgement(${payload.number}). Window size: ${payload.windowSize}." }

        val transfer = outgoing[peer.key] ?: return
        logger.debug { "EVAPROTOCOL: Transfer: $transfer"}

        if (transfer.blockNumber > payload.number || transfer.nonce != payload.nonce) return

        transfer.blockNumber = payload.number
        if (transfer.blockNumber > transfer.blockCount - 1) {
            finishOutgoingTransfer(peer, transfer)
            return
        }

        transfer.windowSize = max(MIN_WINDOWS_SIZE, kotlin.math.min(payload.windowSize, binarySizeLimit))
        transfer.updated = Date().time

        for (blockNumber in (transfer.blockNumber until transfer.blockNumber + transfer.windowSize)) {
            if (blockNumber > transfer.blockCount - 1) return

            val start = blockNumber * blockSize
            val stop = start + blockSize

            val data = transfer.dataBinary?.takeInRange(start, stop)

            if (data?.isEmpty() == true) return

            logger.debug { "EVAPROTOCOL: Transmit($blockNumber/${transfer.blockCount})" }

            val dataPacket = community.createEVAData(peer, blockNumber, transfer.nonce, data!!)
            send(peer, dataPacket)
        }
    }

    /**
     * Upon receipt of an EVA data block, the binary data is added to the already received data.
     * An acknowledgement is sent when the block is the last of its window.
     *
     * @param peer the sender of the data block
     * @param payload data block consisting of blocknumber, nonce and binary data
     */
    fun onData(peer: Peer, payload: EVADataPayload) {
        logger.debug { "EVAPROTOCOL: On data(${payload.blockNumber}). Nonce ${payload.nonce}. Peer hash: ${peer.mid}." }

        val transfer = incoming[peer.key] ?: return

        if (transfer.blockNumber != payload.blockNumber - 1 || transfer.nonce != payload.nonce) return

        transfer.blockNumber = payload.blockNumber

        val info = String(transfer.infoBinary ?: byteArrayOf(), Charsets.UTF_8)

        when {
            transfer.blockNumber == 0 -> {
                TransferProgress(
                    transfer.id,
                    TransferState.INITIALIZING,
                    0.0
                )
            }
            transfer.isProgressMarker() -> {
                TransferProgress(
                    transfer.id,
                    TransferState.DOWNLOADING,
                    transfer.getProgressMarker()!!
                )
            }
            else -> null
        }.also {
            if (it != null) {
                onReceiveProgressCallback(
                    peer,
                    info,
                    it
                )
            }
        }

        transfer.dataBinary?.size?.plus(payload.dataBinary.size)?.let { dataSize ->
            if (dataSize > binarySizeLimit) {
                incomingError(
                    peer,
                    transfer,
                    SizeException(
                        "Current data size limit(${binarySizeLimit}) has been exceeded",
                        info,
                        transfer
                    )
                )
                return
            }
        }

        transfer.dataBinary = transfer.dataBinary?.plus(payload.dataBinary)
        transfer.attempt = 0
        transfer.updated = Date().time

        if (transfer.blockNumber == transfer.blockCount - 1) {
            logger.debug { "EVAPROTOCOL: Final data packet received"}
            sendAcknowledgement(peer, transfer)
            finishIncomingTransfer(peer, transfer)
            return
        }

        val timeToAcknowledge = transfer.acknowledgementNumber + transfer.windowSize <= transfer.blockNumber + 1
        if (timeToAcknowledge) {
            sendAcknowledgement(peer, transfer)
        }
    }

    /**
     * Upon receipt of an EVA error block the error is notified to the protocol and the outgoing
     * transfer is terminated and error callbacks are fired.
     *
     * @param peer the sender of the error block
     * @param payload contains the error message
     */
    fun onError(peer: Peer, payload: EVAErrorPayload) {
        logger.debug { "EVAPROTOCOL: On error with message: ${payload.message}" }

        val transfer = outgoing[peer.key] ?: return
        terminate(outgoing, peer, transfer)

        notifyError(
            peer,
            TransferException(
                payload.message,
                payload.info,
                transfer
            )
        )
        sendScheduled()
    }

    /**
     * The process of sending an acknowledgement block
     *
     * @param peer the receiver of the acknowledgement
     * @param transfer the corresponding transfer object for the acknowledgement
     */
    private fun sendAcknowledgement(peer: Peer, transfer: Transfer) {
        transfer.acknowledgementNumber = transfer.blockNumber + 1

        logger.debug { "EVAPROTOCOL: Acknowledgement (${transfer.acknowledgementNumber}) sent to ${peer.mid} with windowSize (${transfer.windowSize}) and nonce ${transfer.nonce}"}

        val ackPacket = community.createEVAAcknowledgement(transfer.acknowledgementNumber, transfer.windowSize, transfer.nonce)
        send(peer, ackPacket)
    }

    /**
     * Finishing an incoming transfer and callbacks to its listeners
     *
     * @param peer sender of the transfer
     * @param transfer corresponding transfer
     */
    private fun finishIncomingTransfer(peer: Peer, transfer: Transfer) {
        val data = transfer.dataBinary
        val info = if (transfer.infoBinary != null) String(transfer.infoBinary!!, Charsets.UTF_8) else ""

        logger.debug { "EVAPROTOCOL: Incoming transfer finished: id ${transfer.id} and info $info" }

        finishedIncoming.add(peer.key, transfer.id)
        terminate(incoming, peer, transfer)

        onReceiveProgressCallback(
            peer,
            info,
            TransferProgress(
                transfer.id,
                TransferState.FINISHED,
                100.0
            )
        )

        onReceiveCompleteCallback(
            peer,
            info,
            transfer.id,
            data
        )
    }

    /**
     * Finishing an outgoing transfer and callbacks to its listeners
     *
     * @param peer sender of the transfer
     * @param transfer corresponding transfer
     */
    private fun finishOutgoingTransfer(peer: Peer, transfer: Transfer) {
        val data = transfer.dataBinary
        val info = if (transfer.infoBinary != null) String(transfer.infoBinary!!, Charsets.UTF_8) else ""
        val nonce = transfer.nonce

        logger.debug { "EVAPROTOCOL: Outgoing transfer finished: id: ${transfer.id}, nonce: ${transfer.nonce} and info $info" }

        finishedOutgoing.add(peer.key, transfer.id)
        terminate(outgoing, peer, transfer)

        onSendCompleteCallback(peer, info, data, nonce)

        sendScheduled()
    }

    /**
     * Send the next scheduled transfer
     */
    private fun sendScheduled() {
        val idlePeerKeys = scheduled
            .filter { !outgoing.contains(it.key) }
            .map { it.key }

        for (key in idlePeerKeys) {
            val peer = getConnectedPeer(key) ?: continue

            if (!scheduled.containsKey(key)) continue

            scheduled.popValue(key)?.let { transfer ->
                startOutgoingTransfer(
                    peer,
                    transfer.infoBinary,
                    transfer.id,
                    transfer.dataBinary,
                    transfer.nonce,
                )
            }
        }
    }

    /**
     * Terminate a particular transfer by releasing it and removing it from the in/out set
     *
     * @param container incoming or outgoing set
     * @param peer transfer with peer
     * @param transfer corresponding transfer
     */
    private fun terminate(container: MutableMap<Key, Transfer>, peer: Peer, transfer: Transfer) {
        logger.debug { "EVAPROTOCOL: Transfer terminated: $transfer" }

        transfer.release()
        container.remove(peer.key)
    }

    /**
     * Create and send an error during an incoming transfer
     *
     * @param peer sender of block
     * @param transfer (optional) corresponding transfer object
     * @param exception transfer exception
     */
    private fun incomingError(peer: Peer, transfer: Transfer, exception: TransferException) {
        terminate(incoming, peer, transfer)

        val errorPacket = community.createEVAError(exception.localizedMessage ?: "", exception.info)
        send(peer, errorPacket)

        notifyError(peer, exception)
    }

    /**
     * Callbacks for the errors during transfer
     *
     * @param peer transfer with peer
     * @param exception transfer exception
     */
    private fun notifyError(peer: Peer, exception: TransferException) {
        onErrorCallback(peer, exception)
    }

    /**
     * Schedule to terminate transfer (if enabled) after a predefined timeout interval has passed.
     *
     * @param container transfer in incoming/outgoing set of transfers
     * @param peer the sender/receiver
     * @param transfer transfer to be terminated
     */
    private fun scheduleTerminate(container: MutableMap<Key, Transfer>, peer: Peer, transfer: Transfer) {
        if (!terminateByTimeoutEnabled) return

        logger.debug { "EVAPROTOCOL: Schedule terminate for transfer with id ${transfer.id} and nonce ${transfer.nonce}" }

        scheduleTask(Date().time + timeoutIntervalInSec * 1000) {
            terminateByTimeout(container, peer, transfer)
        }
    }

    /**
     * Terminate an transfer when timeout passed and no remaining time left or schedule new timeout
     * task to check the timeout conditions.
     *
     * @param container transfer in incoming/outgoing set of transfers
     * @param peer the sender/receiver
     * @param transfer transfer to be terminated
     */
    private fun terminateByTimeout(container: MutableMap<Key, Transfer>, peer: Peer, transfer: Transfer) {
        logger.debug { "EVAPROTOCOL: Scheduled terminate task executed. Transfer is released: ${transfer.released}" }

        if (transfer.released || !terminateByTimeoutEnabled) return

        val timeout = timeoutIntervalInSec * 1000
        val remainingTime = timeout - (Date().time - transfer.updated)

        if (remainingTime > 0) {
            scheduleTask(Date().time + remainingTime) {
                terminateByTimeout(container, peer, transfer)
            }
            return
        }

        terminate(container, peer, transfer)
        notifyError(
            peer,
            TimeoutException(
                "Terminated by timeout. Timeout is $timeout sec",
                String(transfer.infoBinary ?: byteArrayOf(), Charsets.UTF_8),
                transfer
            )
        )
    }

    /**
     * Schedule the resend of an acknowledge
     *
     * @param peer the receiver of the acknowledgement
     * @param transfer corresponding transfer
     */
    private fun scheduleResendAcknowledge(peer: Peer, transfer: Transfer) {
        if (!retransmitEnabled) return

        scheduleTask(Date().time + retransmitIntervalInSec * 1000) {
            resendAcknowledge(peer, transfer)
        }
    }

    /**
     * Resend acknowledge to peer of transfer when the transfer has not been released (yet) and
     * there are still retransmit attempts left. Resend is needed when the time between the last
     * received block and now is bigger than a predefined retransmit interval. In total a predefined
     * number of retransmits are allowed.
     *
     * @param peer the receiver of the acknowledgement
     * @param transfer corresponding transfer
     */
    private fun resendAcknowledge(peer: Peer, transfer: Transfer) {
        if (transfer.released || !retransmitEnabled || transfer.attempt >= retransmitAttemptCount - 1) return

        if (Date().time - transfer.updated >= retransmitIntervalInSec * 1000) {
            transfer.acknowledgementNumber = transfer.blockNumber + 1
            transfer.attempt += 1

            logger.debug { "EVAPROTOCOL: Re-acknowledgement attempt ${transfer.attempt + 1}/$retransmitAttemptCount: (${transfer.acknowledgementNumber})." }

            sendAcknowledgement(peer, transfer)
        }

        scheduleTask(Date().time + retransmitIntervalInSec * 1000) {
            resendAcknowledge(peer, transfer)
        }
    }

    companion object {
        const val MIN_WINDOWS_SIZE = 1
        const val MAX_NONCE = Integer.MAX_VALUE.toLong() * 2

        const val BLOCK_SIZE = 1000
        const val WINDOW_SIZE_IN_BLOCKS = 64
        const val START_MESSAGE_ID = 186
        const val RETRANSMIT_INTERVAL_IN_SEC = 3
        const val RETRANSMIT_ATTEMPT_COUNT = 3
        const val SCHEDULED_SEND_INTERVAL_IN_SEC = 5
        const val TIMEOUT_INTERVAL_IN_SEC = 20
        const val BINARY_SIZE_LIMIT = 1024 * 1024 * 1024
    }
}

/**
 * Add functionality for a mutable map in which the value contains a queue
 *
 * @property MutableMap the map consisting of a key and value (queue)
 * @param key the key in the map of type K
 * @param value the value, a queue, in the map of type V
 */
fun <K, V> MutableMap<K, Queue<V>>.addValue(key: K, value: V) {
    if (this.containsKey(key)) {
        this[key]?.add(value)
    } else {
        this[key] = LinkedList(listOf(value))
    }
}

/**
 * Pop value in queue for a mutable map in which the value contains a queue
 *
 * @property MutableMap the map consisting of a key of type K and value (queue) of type V
 * @param key the key in the map of which the queue is the value
 * @return the popped value or null
 */
fun <K, V> MutableMap<K, Queue<V>>.popValue(key: K): V? {
    if (this.containsKey(key)) {
        val value = this[key]?.poll()

        if (this[key]?.isEmpty() == true) {
            this.remove(key)
        }

        return value
    }

    return null
}

/**
 * Add functionality for a mutable map in which the value contains a set
 *
 * @property MutableMap the map consisting of a key and value (mutable set)
 * @param key the key in the map of type K
 * @param value the value, a mutable set, in the map of type V
 */
fun <K, V> MutableMap<K, MutableSet<V>>.add(key: K, value: V) {
    if (this.containsKey(key)) {
        this[key]?.add(value)
    } else {
        this[key] = mutableSetOf(value)
    }
}

/**
 * Copy range of a bytearray, given from and to index, depending on size of array.
 *
 * @property ByteArray the byte array
 * @param fromIndex start index
 * @param toIndex end index, may be less if size of array is smaller
 * @return range of copied values from array
 */
fun ByteArray.takeInRange(fromIndex: Int, toIndex: Int): ByteArray {
    val to = if (toIndex > this.size) {
        this.size
    } else toIndex

    return this.copyOfRange(fromIndex, to)
}


