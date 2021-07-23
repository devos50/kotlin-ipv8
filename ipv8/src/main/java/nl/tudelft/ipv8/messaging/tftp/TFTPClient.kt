package nl.tudelft.ipv8.messaging.tftp

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import org.apache.commons.net.tftp.*
import org.apache.commons.net.tftp.TFTPClient.DEFAULT_MAX_TIMEOUTS
import java.io.*
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/***
 * The size to use for TFTP packet buffers. It's 4 + TFTPPacket.SEGMENT_SIZE, i.e. 516.
 */
private const val PACKET_SIZE = TFTPPacket.SEGMENT_SIZE + 4

private const val VERBOSE_LOGGING = false

/**
 * Verbose logging
 */
private fun vl(log: () -> String) {
    if (VERBOSE_LOGGING) {
        logger.debug { log() }
    }
}

class TFTPClient {
    private var _totalBytesSent = 0L
    private val _sendBuffer: ByteArray = ByteArray(PACKET_SIZE)

    companion object {
        private const val SO_TIMEOUT = 1000L
    }

    suspend fun sendFile(
        filename: String,
        mode: Int,
        input: InputStream,
        host: InetAddress,
        port: Int,
        connectionId: Byte,
        channel: ConcurrentHashMap<Byte, Channel<TFTPPacket>>,
        send: (TFTPPacket, Byte) -> Unit,
    ) {
        var block = 0
        var lastAckWait = false

        _totalBytesSent = 0L

        var sent: TFTPPacket = TFTPWriteRequestPacket(host, port, filename, mode)
        val data = TFTPDataPacket(host, port, 0, _sendBuffer, 4, 0)

        do { // until eof
            // first time: block is 0, lastBlock is 0, send a request packet.
            // subsequent: block is integer starting at 1, send data packet.
            var wantReply = true
            var timeouts = 0
            do {
                try {
                    send(sent, connectionId)
                    vl { "Waiting for receive... ($port:$connectionId)" }

                    val received = withTimeout(SO_TIMEOUT) { channel[connectionId]!!.receive() }

                    vl { "!!! Received TFTP packet of type ${received.type} ($port:$connectionId)" }

                    val recdAddress = received.address
                    val recdPort = received.port

                    // Comply with RFC 783 indication that an error acknowledgment
                    // should be sent to originator if unexpected TID or host.
                    if (host == recdAddress && port == recdPort) {
                        when (received.type) {
                            TFTPPacket.ERROR -> {
                                val error = received as TFTPErrorPacket
                                throw IOException("Error code ${error.error} received: ${error.message}")
                            }
                            TFTPPacket.ACKNOWLEDGEMENT -> {
                                val lastBlock = (received as TFTPAckPacket).blockNumber
                                vl { "ACK block: $lastBlock, expected: $block ($port):$connectionId" }
                                if (lastBlock == block) {
                                    ++block
                                    vl { "lastBlock: $lastBlock, block: $block, port: $port:$connectionId" }
                                    if (block > 65535) {
                                        // wrap the block number
                                        block = 0
                                    }
                                    wantReply = false
                                } else {
                                    vl { "ignoring block $block ($port:$connectionId)" }
                                }
                            }
                            else -> throw IOException("Received unexpected packet type.")
                        }
                    } else {
                        // wrong host or TID; send error
                        val error = TFTPErrorPacket(
                            recdAddress,
                            recdPort,
                            TFTPErrorPacket.UNKNOWN_TID,
                            "Unexpected host or port"
                        )
                        send(error, connectionId)
                    }
                } catch (e: SocketException) {
                    if (++timeouts >= DEFAULT_MAX_TIMEOUTS) {
                        throw IOException("Connection timed out ($port:$connectionId)")
                    }
                } catch (e: InterruptedIOException) {
                    if (++timeouts >= DEFAULT_MAX_TIMEOUTS) {
                        throw IOException("Connection timed out ($port:$connectionId)")
                    }
                } catch (e: TimeoutCancellationException) {
                    if (++timeouts >= DEFAULT_MAX_TIMEOUTS) {
                        throw IOException("Connection timed out ($port:$connectionId)")
                    }
                } catch (e: TFTPPacketException) {
                    throw IOException("Bad packet: " + e.message)
                }
                // retry until a good ack
            } while (wantReply)
            if (lastAckWait) {
                break // we were waiting for this; now all done
            }
            if (block % 500 == 0) {
                logger.debug { "Sent block $block to ${port}:$connectionId" }
            }
            var dataLength = TFTPPacket.SEGMENT_SIZE
            var offset = 4
            var totalThisPacket = 0
            var bytesRead = 0
            while (dataLength > 0 && input.read(_sendBuffer, offset, dataLength).also { bytesRead = it } > 0) {
                offset += bytesRead
                dataLength -= bytesRead
                totalThisPacket += bytesRead
            }
            if (totalThisPacket < TFTPPacket.SEGMENT_SIZE) {
                /* this will be our last packet -- send, wait for ack, stop */
                lastAckWait = true
            }
            data.blockNumber = block
            vl { "Sending blockNumber: ${data.blockNumber} of $block ($port:$connectionId)" }
            data.setData(_sendBuffer, 4, totalThisPacket)
            sent = data
            _totalBytesSent += totalThisPacket.toLong()
        } while (true) // loops until after lastAckWait is set
        logger.debug { "sendFile finished ($port:$connectionId)" }
        channel.remove(connectionId)
    }
}
