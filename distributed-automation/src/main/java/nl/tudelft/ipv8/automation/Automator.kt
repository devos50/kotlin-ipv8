package nl.tudelft.ipv8.automation

import mu.KotlinLogging
import nl.tudelft.ipv8.*
import nl.tudelft.ipv8.keyvault.defaultCryptoProvider
import nl.tudelft.ipv8.messaging.EndpointAggregator
import nl.tudelft.ipv8.messaging.udp.UdpEndpoint
import java.net.InetAddress
import kotlin.system.exitProcess

private const val PROPERTY_PORT = "port"
private const val DEFAULT_PORT = 55555
private val logger = KotlinLogging.logger {}

// First argument is your password to get sudo rights => needed to increase UDP buffers
fun main(args: Array<String>) {
//    updateSysctl(args[0], "net.core.rmem_default", 80000000)
//    updateSysctl(args[0], "net.core.rmem_max", 80000000)
//    updateSysctl(args[0], "net.core.wmem_default", 80000000)
//    updateSysctl(args[0], "net.core.wmem_max", 80000000)
    val port = System.getProperty(PROPERTY_PORT, DEFAULT_PORT.toString()).toIntOrNull() ?: DEFAULT_PORT
    val udpEndpoint = UdpEndpoint(port, InetAddress.getByName("0.0.0.0"), localNetworksSupportsUTP = true)
    val endpoint = EndpointAggregator(udpEndpoint, null)
    val automationOverlay = OverlayConfiguration(
        Overlay.Factory(AutomationCommunity::class.java),
        walkers = listOf(SimpleChurn.Factory()),
        maxPeers = Int.MAX_VALUE
    )
    val config = IPv8Configuration(overlays = listOf(automationOverlay))
    val key = defaultCryptoProvider.generateKey()
    val myPeer = Peer(key)
    val ipv8 = IPv8(endpoint, config, myPeer)
    ipv8.start()

    logger.info { "Started automator" }

    while (ipv8.isStarted()) {
        Thread.sleep(1000)
    }
}

fun updateSysctl(password: String, variable: String, value: Int) {
    val cmd = "$variable=$value"
    val exec = Runtime.getRuntime().exec(arrayOf("/bin/bash", "-c", "echo \"$password\"| sudo -S sysctl -w $cmd"))
    exec.inputStream.bufferedReader(Charsets.UTF_8).use {
        val txt = it.readLines().joinToString()
        logger.debug { txt }
        if (txt != cmd.replace("=", " = ")) {
            logger.error { "Updating sysctl udp buffers failed..." }
            exitProcess(1)
        }
    }
}
