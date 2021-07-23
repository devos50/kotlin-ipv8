package nl.tudelft.ipv8.automation

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import nl.tudelft.ipv8.Community
import nl.tudelft.ipv8.IPv4Address
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.messaging.Deserializable
import nl.tudelft.ipv8.messaging.Packet
import nl.tudelft.ipv8.messaging.payload.IntroductionRequestPayload
import java.io.*
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.fixedRateTimer
import kotlin.concurrent.thread

private val logger = KotlinLogging.logger("AutomationCommunity")

interface MessageListener {
    fun onMessageReceived(messageId: AutomationCommunity.MessageId, peer: Peer, payload: Any)
}

class AutomationCommunity : Community() {
    override val serviceId = "36b098237ff4debfd0278b8b87c583e1c2cce4b7" // MUST BE THE SAME AS FEDMLCOMMUNITY!!!!
    private lateinit var testFinishedLatch: CountDownLatch
    private lateinit var evaluationProcessor: EvaluationProcessor
    private lateinit var localPortToWanAddress: Map<Int, IPv4Address>
    private val wanPortToPeer = mutableMapOf<Int, Peer>()
    private var wanPortToHeartbeat = mutableMapOf<Int, Long>()
    private val currentOS = getOS()
    @Volatile private var receivedUpdate = true

    enum class OS {
        WINDOWS, UNIX
    }

    private fun getOS(): OS {
        val getWanPorts = AutomationCommunity::class.java.classLoader.getResource("GetWanPorts.cmd")!!.path
        return if (getWanPorts.contains("home")) {
            OS.UNIX
        } else {
            OS.WINDOWS
        }
    }

    enum class MessageId(val id: Int, val deserializer: Deserializable<out Any>) {
        MSG_NOTIFY_HEARTBEAT(110, MsgNotifyHeartbeat.Deserializer),
        MSG_NEW_TEST_COMMAND(111, MsgNewTestCommand.Deserializer),
        MSG_NOTIFY_EVALUATION(112, MsgNotifyEvaluation.Deserializer),
        MSG_NOTIFY_FINISHED(113, MsgNotifyFinished.Deserializer),
        MSG_FORCED_INTRODUCTION(114, MsgForcedIntroduction.Deserializer),
    }

    init {
        messageHandlers[MessageId.MSG_NOTIFY_HEARTBEAT.id] = ::onMsgNotifyHeartbeat
        messageHandlers[MessageId.MSG_NOTIFY_EVALUATION.id] = ::onMsgNotifyEvaluation
        messageHandlers[MessageId.MSG_NOTIFY_FINISHED.id] = ::onMsgNotifyFinished

        messageListeners[MessageId.MSG_NOTIFY_EVALUATION]!!.add(object : MessageListener {
            override fun onMessageReceived(messageId: MessageId, peer: Peer, payload: Any) {
                receivedUpdate = true
                val localPort = localPortToWanAddress.filterValues { it.port == peer.address.port }.keys.first()
                logger.info { "====> Evaluation: $localPort" }
                evaluationProcessor.call(localPort, (payload as MsgNotifyEvaluation).evaluation)
            }
        })
        messageListeners[MessageId.MSG_NOTIFY_FINISHED]!!.add(object : MessageListener {
            override fun onMessageReceived(messageId: MessageId, peer: Peer, payload: Any) {
                val localPort = localPortToWanAddress.filterValues { it.port == peer.address.port }.keys.first()
                logger.info { "================> Finished: $localPort" }
                testFinishedLatch.countDown()
                logger.info { "#finished peers: ${localPortToWanAddress.size - testFinishedLatch.count} of ${localPortToWanAddress.size} peers" }
            }
        })
        startAutomation()
    }

    companion object {
        val messageListeners = MessageId.values().associate { it to mutableListOf<MessageListener>() }.toMutableMap()
    }

    @ExperimentalUnsignedTypes
    override fun onPacket(packet: Packet) {
        val sourceAddress = packet.source
        val data = packet.data

        val probablePeer = network.getVerifiedByAddress(sourceAddress)
        if (probablePeer != null) {
            probablePeer.lastResponse = Date()
        }

        val msgId = data[prefix.size].toUByte().toInt()
        val handler = messageHandlers[msgId]

        if (handler != null) {
            try {
                handler(packet)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            logger.info("Received unknown message $msgId from $sourceAddress")
        }
    }

    private fun onMsgNotifyHeartbeat(packet: Packet) {
        onMessage(packet, MessageId.MSG_NOTIFY_HEARTBEAT)
    }

    private fun onMsgNotifyEvaluation(packet: Packet) {
        onMessage(packet, MessageId.MSG_NOTIFY_EVALUATION)
    }

    private fun onMsgNotifyFinished(packet: Packet) {
        onMessage(packet, MessageId.MSG_NOTIFY_FINISHED)
    }

    private fun onMessage(packet: Packet, messageId: MessageId) {
        val (peer, payload) = packet.getAuthPayload(messageId.deserializer)
        messageListeners.getValue(messageId).forEach { it.onMessageReceived(messageId, peer, payload) }
    }

    /*
     * Request handling
     */
    override fun onIntroductionRequest(
        peer: Peer,
        payload: IntroductionRequestPayload
    ) {
        return
    }


    ////////////// AUTOMATION METHODS


    private fun startAutomation() = thread(name = "automation main thread") {
        val evaluationsFolder = Paths.get(System.getProperty("user.home"), "Downloads", "evaluations").toFile()
        evaluationProcessor = EvaluationProcessor(evaluationsFolder, "distributed")
        val automation = loadAutomation()
        val (configs, figureNames) = generateConfigs(automation)



        for (figure in automation.figures.indices) {
            val figureName = figureNames[figure]
//            if (figureName == "Figure 0.1") {
                val figureConfig = configs[figure]

                for (test in figureConfig.indices) {
                    runTest(figureName, figureConfig[test])
                }
//            }
        }
    }

    private fun loadAutomation(): Automation {
        val file = File(AutomationCommunity::class.java.classLoader.getResource("automation.json")!!.path)
        val string = file.readLines().joinToString("")
        return Json.decodeFromString(string)
    }

    private fun runTest(figureName: String, config: List<Map<String, String>>) {
        prepareEnvironment(config.size)
        while (localPortToWanAddress.size < config.size) {
            logger.info { "Too few devices found to run the test: ${localPortToWanAddress.size} devices found, ${config.size} devices needed" }
            Thread.sleep(2000)
        }
        logger.error { "Going to test: $figureName - ${config[0]["gar"]}" }
        evaluationProcessor.newSimulation("$figureName - ${config[0]["gar"]}", config)
        val activeLocalPorts = localPortToWanAddress.toSortedMap().toList().take(config.size)
        testFinishedLatch = CountDownLatch(activeLocalPorts.size)

        for ((i, localPort) in activeLocalPorts.withIndex()) {
            val msgNewTestCommand = MsgNewTestCommand(config[i], figureName)
            val packet = serializePacket(MessageId.MSG_NEW_TEST_COMMAND.id, msgNewTestCommand, true)
            val wanPort = localPort.second.port
            val peer = wanPortToPeer[wanPort]!!
            send(peer, packet, true)
        }

        receivedUpdate = true
        val ticker = fixedRateTimer(period = 3 * 60 * 1000, initialDelay = 2 * 60 * 1000) {
            logger.info { "tick: $receivedUpdate" }
            if (receivedUpdate) {
                receivedUpdate = false
            } else {
                logger.warn { "Forcefully terminating automation and going to next test" }
                repeat(testFinishedLatch.count.toInt()) {
                    testFinishedLatch.countDown()
                    logger.info { "countdown: ${testFinishedLatch.count}" }
                }
                logger.info { "countdown: ${testFinishedLatch.count}" }
            }
        }

        testFinishedLatch.await()
        ticker.cancel()
        logger.warn { "Test finished" }
    }

    private fun prepareEnvironment(numDevices: Int) {
        setupPorts()
        runAppOnAllDevices(numDevices)
        introduceAllPeers(numDevices)
    }

    private fun setupPorts() {
        localPortToWanAddress = getPortMapping()

        logger.debug { "Got port mapping" }
        /** Need localPortToWanAddress to process heartbeats **/
        messageListeners[MessageId.MSG_NOTIFY_HEARTBEAT]!!.add(object : MessageListener {
            override fun onMessageReceived(messageId: MessageId, peer: Peer, payload: Any) {
                val port = peer.address.port
                peer.supportsUTP = true
                wanPortToHeartbeat[port] = System.currentTimeMillis()
                wanPortToPeer[port] = peer.copy(
                    lanAddress = IPv4Address("10.0.2.2", port),
                    wanAddress = IPv4Address(localPortToWanAddress.values.first().ip, port)
                )
            }
        })
        setupPortRedirection(localPortToWanAddress.map { it.key to it.value.port }.toMap())
    }

    private fun getPortMapping(): Map<Int, IPv4Address> {
        var getWanPorts = AutomationCommunity::class.java.classLoader.getResource("GetWanPorts.cmd")!!.path
        if (currentOS == OS.UNIX) {
            getWanPorts = AutomationCommunity::class.java.classLoader.getResource("GetWanPorts.sh")!!.path
            Runtime.getRuntime().exec("chmod 777 $getWanPorts").waitFor()
        }
        Runtime.getRuntime().exec(getWanPorts)
        val waitingTime = 1500L
        logger.debug { "Sleeping $waitingTime ms to finish port mapping script..." }
        Thread.sleep(waitingTime)

        val folder = Paths.get(System.getProperty("user.home"), "Downloads", "wanPorts").toFile()
        return folder.list()!!.associate {
            val fileContents = File(folder, it).readLines()
            it.split('-')[1].toInt() to IPv4Address(fileContents[0], fileContents[1].toInt())
        }
    }

    private fun setupPortRedirection(portMapping: Map<Int, Int>) {
        val tmpDir = Paths.get(System.getProperty("java.io.tmpdir")).toFile()
        val tmpTime = System.currentTimeMillis()
        val setupPortsFile = if (currentOS == OS.WINDOWS) {
            createSetupPortsFileWindows(tmpDir, tmpTime, portMapping)
        } else {
            createSetupPortsFileUnix(tmpDir, tmpTime, portMapping)
        }
        Runtime.getRuntime().exec(setupPortsFile.path)
    }

    private fun createSetupPortsFileWindows(tmpDir: File, tmpTime: Long, portMapping: Map<Int, Int>): File {
        createSetupPortsFileWindowsHelper(tmpDir, tmpTime)
        val sb = StringBuilder("@echo off\n")
        portMapping.onEachIndexed { i, (emulatorPort, wanPort) ->
            sb.append("set ports[$i]=$emulatorPort\n")
            sb.append("set redirects[$i]=$wanPort\n")
        }
        sb.append(
            "set \"x=0\"\n" +
                ":SymLoop\n" +
                "if defined ports[%x%] (\n" +
                "\tcall echo %%ports[%x%]%%\n" +
                "\tset /a \"x+=1\"\n" +
                "\tGOTO :SymLoop \n" +
                ")\n" +
                "set /a \"x-=1\"\n" +
                "echo \"Redirecting %x% AVDs\"\n" +
                "\n" +
                "setlocal EnableDelayedExpansion\n" +
                "for /L %%i in (0, 1, %x%) do (\n" +
                "\techo Forwarding AVD !ports[%%i]! to port !redirects[%%i]!\n" +
                "\tstart telnet.exe localhost !ports[%%i]!\n" +
                "\tcscript SetupPortsHelper$tmpTime.vbs !redirects[%%i]!\n" +
                ")\n" +
                "endlocal\n" +
                "exit\n"
        )

        val mainFile = File(tmpDir, "SetupPorts$tmpTime.bat")
        PrintWriter(mainFile).use {
            it.println(sb.toString())
            it.flush()
        }
        return mainFile
    }

    private fun createSetupPortsFileWindowsHelper(tmpDir: File, tmpTime: Long): File {
        val helperFile = File(tmpDir, "SetupPortsHelper$tmpTime.vbs")
        PrintWriter(helperFile).use {
            it.println(
                "set OBJECT=WScript.CreateObject(\"WScript.Shell\")\n" +
                    "WScript.sleep 50\n" +
                    "OBJECT.SendKeys \"auth fqvo8zH1j32aFoVB{ENTER}\"\n" + // Hardcoded password is too insignificant to remove from the code
                    "WScript.sleep 50\n" +
                    "OBJECT.SendKeys \"redir add udp:\" & WScript.Arguments.Item(0) & \":8090{ENTER}\"\n" +
                    "WScript.sleep 50\n" +
                    "OBJECT.SendKeys \"exit{ENTER}\""
            )
            it.flush()
        }
        return helperFile
    }

    private fun createSetupPortsFileUnix(tmpDir: File, tmpTime: Long, portMapping: Map<Int, Int>): File {
        val sb = StringBuilder()
        sb.append("#!/bin/bash\n")
        sb.append("declare -A mapping\n")
        portMapping.forEach { (emulatorPort, wanPort) ->
            sb.append("mapping[$emulatorPort]=$wanPort\n")
        }
        val auth = Paths.get(System.getProperty("user.home"), ".emulator_console_auth_token").toFile().readText(Charsets.UTF_8)
        sb.append(
            "for localport in \"\${!mapping[@]}\"; do\n" +
                "\techo \"Forwarding AVD \$localPort to wan port \${mapping[\$localport]}\"\n" +
                "\techo telnet localhost \$localport\n" +
                "\techo redir add \"udp:\${mapping[\$localport]}:8090\"\n" +
                "\t\n" +
                "\t(\n" +
                "\t\techo auth $auth\n" +
                "\t\tsleep 1\n" +
                "\t\techo redir add \"udp:\${mapping[\$localport]}:8090\"\n" +
                "\t\tsleep 1\n" +
                "\t\techo quit\n" +
                "\t) | telnet localhost \$localport\n" +
                "done"
        )

        val mainFile = File(tmpDir, "SetupPorts$tmpTime.sh")
        PrintWriter(mainFile).use {
            it.println(sb.toString())
            it.flush()
        }
        Runtime.getRuntime().exec("chmod 777 ${mainFile.path}").waitFor()
        return mainFile
    }

    private fun runAppOnAllDevices(numDevices: Int) = runBlocking {
        localPortToWanAddress.forEach {
            uninstallApp(it.key)
        }
        logger.info { "Delaying 3000ms to uninstall apps" }
        delay(3000L)
        logger.info { "Probably done uninstalling apps" }

        val devices = localPortToWanAddress.toSortedMap().toList().take(numDevices)

        val threads: MutableList<Thread> = arrayListOf()
        devices.forEach {
            threads.add(thread {
                installApk(it.first, getApkFile())
            })
        }
        threads.forEach { it.join() }
        logger.info { "Installed apps" }

        devices.forEach {
            grantPermissions(it.first)
        }
        logger.info { "Delaying 2000ms to grant permissions" }
        delay(2000L)
        logger.info { "Probably done granting permissions" }

        devices.forEach {
            runAppOnDevice(it.first)
        }
        val maxTime = System.currentTimeMillis()
        logger.info { "Delaying 10000ms to start app" }
        delay(10000L)

        setupPorts()  // Ports might have changed
        if (devices.all { wanPortToHeartbeat.getOrDefault(it.second.port, -1) >= maxTime }) {
            logger.info { "Success restarting devices => all peers alive" }
            return@runBlocking
        }
        throw RuntimeException("Failed to restart devices...")
    }

    private fun runAppOnDevice(peer: Int) {
        when (currentOS) {
            OS.WINDOWS -> runAppOnDeviceWindows(peer)
            OS.UNIX -> runAppOnDeviceUnix(peer)
        }
    }

    private fun runAppOnDeviceWindows(peer: Int) {
        val file = Files.createTempFile("runAppOnDevice", ".cmd").toFile()
        PrintWriter(file).use {
            it.println(
                "@echo off\n" +
                    "adb -s emulator-$peer root\n" +
                    "adb -s emulator-$peer shell am force-stop nl.tudelft.trustchain\n" +
                    "adb -s emulator-$peer shell am start -n nl.tudelft.trustchain/nl.tudelft.trustchain.app.ui.dashboard.DashboardActivity -e activity fedml -e automationPart 0 -e enableExternalAutomation true\n"
            )
            it.flush()
        }
        Runtime.getRuntime().exec(file.path)
    }

    private fun runAppOnDeviceUnix(peer: Int) {
        val file = Files.createTempFile("runAppOnDevice", ".sh").toFile()
        PrintWriter(file).use {
            it.println(
                "#!/bin/sh\n" +
                    "adb -s emulator-$peer root\n" +
                    "adb -s emulator-$peer shell am force-stop nl.tudelft.trustchain\n" +
                    "adb -s emulator-$peer shell am start -n nl.tudelft.trustchain/nl.tudelft.trustchain.app.ui.dashboard.DashboardActivity -e activity fedml -e automationPart 0 -e enableExternalAutomation true\n"
            )
            it.flush()
        }
        Runtime.getRuntime().exec("chmod 777 ${file.path}").waitFor()
        Runtime.getRuntime().exec(file.path)
    }

    private fun introduceAllPeers(numDevices: Int) {
        logger.debug { "1:      ${wanPortToPeer.entries}" }

        val devices = localPortToWanAddress.toSortedMap().toList().take(numDevices)
        for (device in devices) {
            val introductions = wanPortToPeer.values.filterNot { it == wanPortToPeer[device.second.port] }
            val wanPorts = introductions.map { it.address.port }
            val msgForcedIntroduction = MsgForcedIntroduction(
                wanPorts,
                supportsTFTP = false,
                supportsUTP = true,
                serviceId = serviceId
            )
            logger.debug("-> $msgForcedIntroduction")
            val packet = serializePacket(MessageId.MSG_FORCED_INTRODUCTION.id, msgForcedIntroduction, true)
            send(wanPortToPeer[device.second.port]!!, packet, true)
        }
        while (!endpoint.udpEndpoint!!.noPendingTFTPMessages()) {
            logger.debug { "Waiting for all UTP messages to be sent" }
            Thread.sleep(300)
        }
    }
}
