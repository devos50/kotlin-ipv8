package nl.tudelft.ipv8.messaging.fasttftp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import org.apache.commons.net.tftp.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress

private val logger = KotlinLogging.logger {}

// TODO: Refactor to extend TFTP class and use proxy socket to remove the need for send listener
//  and an input buffe
class FastTFTPServer(
    private val send: (TFTPPacket) -> Unit
) {
    companion object {
        private const val MAX_TIMEOUT_RETRIES = 3
        private const val SO_TIMEOUT = 1000L
    }

    private var shutdownTransfer = false

    private val buffer = Channel<TFTPPacket>(Channel.UNLIMITED)

    // TODO: create a separate channel for each file to allow parallel writes
    private val mutex = Mutex()

    var onFileReceived: ((ByteArray, InetAddress, Int) -> Unit)? = null

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    fun onPacket(packet: TFTPPacket) {
        if (packet is TFTPWriteRequestPacket) {
            scope.launch {
                handleWrite(packet)
            }
        } else {
            buffer.offer(packet)
        }
    }

    /**
     * Handles write requests. It loads all data packets into a memory-backed output stream.
     * Once the entire payload is received, [onFileReceived] listener is invoked with the payload,
     * source address, and port.
     *
     * In the future, the output stream should be exposed via API to allow clients to consume
     * the stream in real-time.
     *
     * @param twrp The first write request packet sent by the client.
     *
     * Inspired by https://github.com/apache/commons-net/blob/eb8ac04598710c952a41c3112877c1ff8e3b3cfa/src/test/java/org/apache/commons/net/tftp/TFTPServer.java
     */
    @Throws(IOException::class, TFTPPacketException::class)
    private suspend fun handleWrite(twrp: TFTPWriteRequestPacket) = mutex.withLock {
        logger.debug { "handleWrite" }

        val bos = ByteArrayOutputStream()

//        var lastBlock = 0
        var lastSentAck = TFTPAckPacket(twrp.address, twrp.port, 0)
        send(lastSentAck)

        logger.debug { "sent first acknowledgment" }

        val packets: MutableMap<Int, TFTPDataPacket> = HashMap()

        var totalBlocks: Int = -1

        while (true) {
            // get the response - ensure it is from the right place.
            if (packets.size == totalBlocks) {
                logger.warn { "All blocks received => done" }
                break
            }
            var dataPacket: TFTPPacket? = null
            var timeoutCount = 0
            while (!shutdownTransfer && (dataPacket == null || dataPacket.address != twrp.address || dataPacket.port != twrp.port)) {
                // listen for an answer.
                if (dataPacket != null) {
                    // The data that we got didn't come from the
                    // expected source, fire back an error, and continue
                    // listening.
                    logger.debug("FastTFTP Server ignoring message from unexpected source.")
                    send(
                        TFTPErrorPacket(
                            dataPacket.address,
                            dataPacket.port, TFTPErrorPacket.UNKNOWN_TID,
                            "Unexpected Host or Port"
                        )
                    )
                }
                logger.debug { "listening for packet" }
                dataPacket = try {
                    withTimeout(SO_TIMEOUT) {
                        buffer.receive()
                    }
                } catch (e: TimeoutCancellationException) {
                    logger.debug { "timeout" }
                    if (timeoutCount >= MAX_TIMEOUT_RETRIES) {
                        logger.debug { "total timeout" }
                        throw e
                    } else {
                        // It didn't get our ack. Resend it.
                        send(lastSentAck)
                        timeoutCount++
                        continue
                    }
                }
                logger.debug { "packet received" }
            }
            if (dataPacket != null && dataPacket is TFTPWriteRequestPacket) {
                // it must have missed our initial ack. Send another.
                lastSentAck = TFTPAckPacket(twrp.address, twrp.port, 0)
                send(lastSentAck)
                logger.debug { "sent initial ack again..." }
            } else if (dataPacket == null || dataPacket !is TFTPDataPacket) {
                if (!shutdownTransfer) {
                    logger.debug("Unexpected response from tftp client during transfer (" + dataPacket + ").  Transfer aborted.")
                }
                break
            } else {
                val blockNumber = dataPacket.blockNumber
                val dataLength = dataPacket.dataLength
                packets[blockNumber] = dataPacket
                lastSentAck = TFTPAckPacket(twrp.address, twrp.port, blockNumber)
                send(lastSentAck)
                logger.debug { "stored packet #${blockNumber} and sent acknowledgement" }
                if (dataLength < TFTPDataPacket.MAX_DATA_LENGTH) {
                    totalBlocks = blockNumber
                }
            }
        }

        logger.debug { "Stitching blocks together..." }
        for (packet in packets.toSortedMap().iterator()) {
            val data = packet.value.data
            val dataLength = packet.value.dataLength
            val dataOffset = packet.value.dataOffset
            bos.write(data, dataOffset, dataLength)
        }
        logger.debug { "Stitching finished" }
        // end of stream signal - The tranfer is complete.
        onFileReceived?.invoke(bos.toByteArray(), twrp.address, twrp.port)

        // But my ack may be lost - so listen to see if I
        // need to resend the ack.
        for (i in 0 until MAX_TIMEOUT_RETRIES) {
            val dataPacket = try {
                withTimeout(1000) {
                    buffer.receive()
                }
            } catch (e: TimeoutCancellationException) {
                // this is the expected route - the client
                // shouldn't be sending any more packets.
                break
            }
            if (dataPacket.address != twrp.address || dataPacket.port != twrp.port) {
                // make sure it was from the right client...
                send(
                    TFTPErrorPacket(
                        dataPacket.address, dataPacket.port,
                        TFTPErrorPacket.UNKNOWN_TID, "Unexpected Host or Port"
                    )
                )
            } else {
                // This means they sent us the last
                // data packet again, must have missed our
                // ack. resend it.
                send(lastSentAck)
            }
        }
        logger.debug { "handleWrite finished" }
    }
}
