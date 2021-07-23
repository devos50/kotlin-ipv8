package nl.tudelft.ipv8.messaging.tftp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import mu.KotlinLogging
import nl.tudelft.ipv8.IPv4Address
import nl.tudelft.ipv8.messaging.Endpoint
import nl.tudelft.ipv8.messaging.Packet
import nl.tudelft.ipv8.messaging.tftp.TFTPEndpoint.Companion.PREFIX_TFTP
import org.apache.commons.net.tftp.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.NullPointerException
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

private val logger = KotlinLogging.logger {}

@Volatile
private var numTransmissions = 0

private const val VERBOSE_LOGGING = false

/**
 * Verbose logging
 */
private fun vl(log: () -> String) {
    if (VERBOSE_LOGGING) {
        logger.debug { log() }
    }
}

private fun startTransmission() {
    synchronized(numTransmissions) {
        numTransmissions++
        logger.debug { "increased numTransmissions to $numTransmissions" }
    }
}

private fun endTransmission() {
    synchronized(numTransmissions) {
        numTransmissions--
        logger.debug { "decreased numTransmissions to $numTransmissions" }
    }
}

/**
 * An endpoint that allows to send binary data blobs that are larger than UDP packet size over UDP.
 * It uses a TFTP-like protocol with adjusted client and server to share a single socket instead
 * of control + transfer sockets defined in the TFTP protocol.
 *
 * All packets are prefixed with a [PREFIX_TFTP] byte to allow it to be distinguished from regular
 * IPv8 UDP packets in UDPEndpoint which are prefixed with [Community.PREFIX_IPV8].
 */
class TFTPEndpoint : Endpoint<IPv4Address>() {
    private val tftpClients = ConcurrentHashMap<IPv4Address, ConcurrentHashMap<Byte, TFTPClient>>()
    private var tftpServers = ConcurrentHashMap<IPv4Address, ConcurrentHashMap<Byte, TFTPServer>>()

    var socket: DatagramSocket? = null

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun isOpen(): Boolean {
        return socket?.isBound == true
    }

    fun getNumTransmissions(): Int {
        return numTransmissions
    }

    private fun sendPacket(packet: TFTPPacket, connectionId: Byte) {
            val datagram = packet.newDatagram()
            vl { "Send TFTP packet ${packet.type} to ${packet.port} (:$connectionId)" }
            val wrappedData = byteArrayOf(PREFIX_TFTP, connectionId) + datagram.data
            datagram.setData(wrappedData, 0, wrappedData.size)
            socket!!.send(datagram)
    }

    override fun send(peer: IPv4Address, data: ByteArray) {
        startTransmission()
        scope.launch(Dispatchers.IO) {
            val compressedData = ByteArrayOutputStream().use { os ->
                GZIPOutputStream(os).use { os2 ->
                    os2.write(data)
                }
                os.toByteArray()
            }
            val inputStream = ByteArrayInputStream(compressedData)
            val inetAddress = Inet4Address.getByName(peer.ip)
            var availableConnectionId = Byte.MIN_VALUE
            tftpClients.putIfAbsent(peer, ConcurrentHashMap())
            while (tftpClients[peer]!!.containsKey(availableConnectionId)) {
                availableConnectionId++
            }
            logger.debug { "send()  -  port: ${peer.port}, connectionId: $availableConnectionId" }
            tftpClients[peer]!![availableConnectionId] = TFTPClient()
            TFTPCommunity.tftpIncomingClientPackets.putIfAbsent(peer, ConcurrentHashMap())
            TFTPCommunity.tftpIncomingClientPackets[peer]!!.putIfAbsent(availableConnectionId, Channel(Channel.UNLIMITED))
            try {
                tftpClients[peer]!![availableConnectionId]!!.sendFile(
                    TFTP_FILENAME,
                    TFTP.BINARY_MODE,
                    inputStream,
                    inetAddress,
                    peer.port,
                    availableConnectionId,
                    TFTPCommunity.tftpIncomingClientPackets[peer]!!,
                    this@TFTPEndpoint::sendPacket
                )
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                endTransmission()
                tftpClients[peer]!!.remove(availableConnectionId)
                logger.debug { "Ended transmission of port: ${peer.port}, connectionId: $availableConnectionId" }
            }
        }
    }

    fun onPacket(packet: DatagramPacket) {
        try {
            val connectionId = packet.data[1]
            val unwrappedData = packet.data.copyOfRange(2, packet.length)
            packet.setData(unwrappedData, 0, unwrappedData.size)
            val tftpPacket = TFTPPacket.newTFTPPacket(packet)

            vl { "Received TFTP packet of type ${tftpPacket.type} from ${packet.port}:$connectionId" }

            val address = IPv4Address(tftpPacket.address.hostAddress, tftpPacket.port)
            tftpServers.putIfAbsent(address, ConcurrentHashMap())
            if (tftpPacket is TFTPWriteRequestPacket) {
                tftpServers[address]!![connectionId] = {
                    val instance = TFTPServer()
                    vl { "TFTPWriteRequestPacket => port: ${address.port}, connectionId: $connectionId" }
                    instance.onFileReceived = { data, address2, port ->
                        tftpServers[address]!!.remove(connectionId)
                        val sourceAddress = IPv4Address(address2.hostAddress, port)
                        val uncompressedData = ByteArrayInputStream(data).use { stream ->
                            GZIPInputStream(stream).use { stream2 ->
                                stream2.readBytes()
                            }
                        }
                        val received = Packet(sourceAddress, uncompressedData)
                        logger.debug("Received TFTP file (${data.size} B) from $sourceAddress, connectionId: $connectionId")
                        notifyListeners(received)
                    }
                    instance
                }.invoke()
                val serversPerAddress = tftpServers[address]!!
                val server = serversPerAddress[connectionId]!!
                server.onWriteRequestPacket(tftpPacket, connectionId, this::sendPacket)
            } else if (tftpPacket is TFTPDataPacket) {
                vl { "Packet is DataPacket => going to $address:$connectionId" }
                val serversPerAddress = tftpServers[address]!!
                val server = serversPerAddress[connectionId]!!
                server.onDataPacket(tftpPacket)
            } else if (tftpPacket is TFTPAckPacket) {
                vl { "Packet is AckPacket => going to $address:$connectionId" }
                val channelsPerAddress = TFTPCommunity.tftpIncomingClientPackets[address]!!
                try {
                    val channel = channelsPerAddress[connectionId]!!
                    channel.offer(tftpPacket)
                } catch (e: NullPointerException) {
                    // Probably an acknowledgement arriving after the file has already been sent
                    vl { "Ignoring unrecognized acknowledgement" }
                }
            } else if (tftpPacket is TFTPErrorPacket) {
                logger.debug { "Packet is TFTPErrorPacket => port: ${address.port}, connectionId: $connectionId, error:${tftpPacket.message}" }
                TFTPCommunity.tftpIncomingClientPackets[address]!![connectionId]!!.offer(tftpPacket)
            } else {
                logger.error { "Unsupported TFTP packet type: ReadRequest" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to handle TFTP packet" }
        }
    }

    override fun open() {
        // Skip
    }

    override fun close() {
        // Skip
    }

    companion object {
        private const val TFTP_FILENAME = "ipv8_packet.bin"
        const val PREFIX_TFTP: Byte = 69
    }
}
