package nl.tudelft.ipv8.messaging.udp

import kotlinx.coroutines.*
import mu.KotlinLogging
import nl.tudelft.ipv8.Community
import nl.tudelft.ipv8.IPv4Address
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.messaging.Endpoint
import nl.tudelft.ipv8.messaging.EndpointListener
import nl.tudelft.ipv8.messaging.Packet
import nl.tudelft.ipv8.messaging.fasttftp.FastTFTPEndpoint
import nl.tudelft.ipv8.messaging.tftp.TFTPEndpoint
import nl.tudelft.ipv8.messaging.utp.UTPEndpoint
import nl.tudelft.ipv8.peerdiscovery.Network
import java.io.IOException
import java.net.*
import kotlin.concurrent.thread

private val logger = KotlinLogging.logger {}

open class UdpEndpoint(
    private val port: Int,
    private val ip: InetAddress,
    private val tftpEndpoint: TFTPEndpoint = TFTPEndpoint(),
    private val utpEndpoint: UTPEndpoint = UTPEndpoint(),
    private val fastTftpEndpoint: FastTFTPEndpoint = FastTFTPEndpoint(),
    private val localNetwork: Boolean = false,
    private val localNetworksSupportsUTP: Boolean = false
) : Endpoint<Peer>() {
    private var socket: DatagramSocket? = null
    private lateinit var network: Network
    private var wan: IPv4Address? = null

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private var bindJob: Thread? = null
    private var lanEstimationJob: Job? = null

    init {
        tftpEndpoint.addListener(object : EndpointListener {
            override fun onPacket(packet: Packet) {
                logger.debug("Received TFTP packet (${packet.data.size} B) from ${packet.source}")
                notifyListeners(packet)
            }

            override fun onEstimatedLanChanged(address: IPv4Address) {
            }
        })
        fastTftpEndpoint.addListener(object : EndpointListener {
            override fun onPacket(packet: Packet) {
                logger.debug(
                    "Received FastTFTP packet (${packet.data.size} B) from ${packet.source}"
                )
                notifyListeners(packet)
            }

            override fun onEstimatedLanChanged(address: IPv4Address) {
            }
        })
        utpEndpoint.addListener(object : EndpointListener {
            override fun onPacket(packet: Packet) {
//                logger.debug("Received UTP packet (${packet.data.size} B) from ${packet.source}")
                notifyListeners(packet)
            }

            override fun onEstimatedLanChanged(address: IPv4Address) {
            }
        })
    }

    override fun isOpen(): Boolean {
        return socket?.isBound == true
    }

    override fun send(peer: Peer, data: ByteArray) {
        send(peer, data, false)
    }

    fun noPendingTFTPMessages(): Boolean {
        return tftpEndpoint.getNumTransmissions() == 0
    }

    fun noPendingUTPMessages(): Boolean {
        return utpEndpoint.getNumTransmissions() == 0
    }

    /**
     * @param reliable when the package should be retransmitted until the other peer acknowledges that the packet was received
     */
    fun send(peer: Peer, data: ByteArray, reliable: Boolean) {
        if (!isOpen()) throw IllegalStateException("UDP socket is closed")

        if (wan == null) {
            wan = network.wanLog.estimateWan()
        }
        val redirect = localNetwork || peer.address.ip == wan?.ip ?: IPv4Address.EMPTY
        val address = if (redirect) {
            IPv4Address("10.0.2.2", peer.address.port)
        } else {
            peer.address
        }

        if (address.ip == "10.0.2.2" || address.ip == "127.0.0.1") {
            peer.supportsUTP = true
        }

//      logger.debug("Send packet (${data.size} B) to $address ($peer)")
        try {
            if (data.size > UDP_PAYLOAD_LIMIT || reliable) {
                when {
                    peer.supportsUTP -> tftpEndpoint.send(address, data)
                    peer.supportsFastTFTP -> fastTftpEndpoint.send(address, data)
                    peer.supportsTFTP -> tftpEndpoint.send(address, data)
                    else -> logger.warn { "The packet is larger then UDP_PAYLOAD_LIMIT and the peer does not support TFTP or UTP: $address" }
                }
            } else {
                send(address, data)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun send(address: IPv4Address, data: ByteArray) = scope.launch(Dispatchers.IO) {
        val redirect = localNetwork || address.ip == network.wanLog.estimateWan()?.ip ?: IPv4Address.EMPTY
        val toAddress: IPv4Address = if (redirect) {
            IPv4Address("10.0.2.2", address.port)
        } else {
            address
        }
        val datagramPacket = DatagramPacket(data, data.size, toAddress.toSocketAddress())
        try {
            logger.debug { "Sending with UDP to ${socket == null}, address: $address, ${socket?.isBound}"}
            socket?.send(datagramPacket)
        } catch (e: Exception) {
            logger.error("Sending DatagramPacket failed", e)
        }
    }

    override fun open() {
        val socket = getDatagramSocket()
        logger.info { "Opened socket on port: ${socket.port}" }
        this.socket = socket

        tftpEndpoint.socket = socket
        tftpEndpoint.open()

        fastTftpEndpoint.socket = socket
        fastTftpEndpoint.open()

        utpEndpoint.socket = socket
        utpEndpoint.open()

        logger.info { "Opened UDP socket on port ${socket.localPort}" }

        startLanEstimation()

        bindJob = bindSocket(socket)
    }

    /**
     * Finds the nearest unused socket.
     */
    private fun getDatagramSocket(): DatagramSocket {
        for (i in 0 until 100) {
            try {
                return DatagramSocket(port + i, ip)
            } catch (e: Exception) {
                // Try another port
            }
        }
        // Use any available port
        return DatagramSocket()
    }

    override fun close() {
        if (!isOpen()) throw IllegalStateException("UDP socket is already closed")

        stopLanEstimation()

        bindJob?.interrupt()
        bindJob = null

        tftpEndpoint.close()
        fastTftpEndpoint.close()
        utpEndpoint.close()

        socket?.close()
        socket = null
    }

    open fun startLanEstimation() {
        lanEstimationJob = scope.launch {
            while (isActive) {
                estimateLan()
                delay(60_000)
            }
        }
    }

    fun setNetwork(network: Network) {
        this.network = network
    }

    private fun estimateLan() {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        for (intf in interfaces) {
            for (intfAddr in intf.interfaceAddresses) {
                if (intfAddr.address is Inet4Address && !intfAddr.address.isLoopbackAddress) {
                    val estimatedAddress =
                        IPv4Address(intfAddr.address.hostAddress, getSocketPort())
                    setEstimatedLan(estimatedAddress)
                }
            }
        }
    }

    open fun stopLanEstimation() {
        lanEstimationJob?.cancel()
        lanEstimationJob = null
    }

    fun getSocketPort(): Int {
        return socket?.localPort ?: port
    }

    private fun bindSocket(socket: DatagramSocket) = thread {
        while (true) {
            try {
                val receiveData = ByteArray(UDP_PAYLOAD_LIMIT)
                val receivePacket = DatagramPacket(receiveData, receiveData.size)
                socket.receive(receivePacket)
                handleReceivedPacket(receivePacket)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    internal fun handleReceivedPacket(receivePacket: DatagramPacket) {
//        logger.debug("Received packet from " + "${receivePacket.address.hostAddress}:${receivePacket.port}")

        // Check whether prefix is IPv8, TFTP, or UTP
        when (receivePacket.data[0]) {
            Community.PREFIX_IPV8 -> {
                val sourceAddress = IPv4Address(receivePacket.address.hostAddress, receivePacket.port)
                val packet = Packet(sourceAddress, receivePacket.data.copyOf(receivePacket.length))
//                logger.debug("Received UDP packet (${receivePacket.length} B) from $sourceAddress")
                notifyListeners(packet)
            }
            TFTPEndpoint.PREFIX_TFTP -> {
                tftpEndpoint.onPacket(receivePacket)
            }
            FastTFTPEndpoint.PREFIX_FASTTFTP -> {
                fastTftpEndpoint.onPacket(receivePacket)
            }
            UTPEndpoint.PREFIX_UTP -> {
                utpEndpoint.onPacket(receivePacket)
            }
            else -> {
                logger.warn { "Invalid packet prefix" }
            }
        }
    }

    companion object {
        // 1500 - 20 (IPv4 header) - 8 (UDP header)
        const val UDP_PAYLOAD_LIMIT = 1472
    }
}
