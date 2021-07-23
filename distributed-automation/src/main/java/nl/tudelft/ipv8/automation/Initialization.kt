package nl.tudelft.ipv8.automation

import kotlinx.coroutines.*
import mu.KotlinLogging
import java.io.File
import java.io.PrintWriter
import java.nio.file.Paths
import java.util.concurrent.Callable
import kotlin.concurrent.thread

private val logger = KotlinLogging.logger("Initialization")

fun main() {
    val avdDir = Paths.get(System.getProperty("user.home"), ".android", "avd").toFile()

    createDevicesFile()

    val threads = (0 until 16).map { i ->
        thread {
            val port = 5554 + 2 * i

            if (!emulatorFilesExist(i, avdDir)) {
                createFiles(i, avdDir)
            } else {
                logger.debug { "Skipping copying files for emulator $port" }
            }

            if (!isEmulatorRunning(port)) {
                startEmulator(i, port)
            } else {
                logger.debug { "Skipping starting emulator $port" }
            }

            getRootAccess(port)

//            if (isAppInstalled(port)) {
//                uninstallApp(port)
//            } else {
//                logger.debug { "Skipping uninstalling app $port" }
                installApk(port, getApkFile())
//            }

            grantPermissions(port)
            if (!isAppRunning(port)) {
                runApp(port)
            }
            grantPermissions(port)
        }
    }
    threads.forEach { it.join() }
}

fun createDevicesFile() {
    val file = Paths.get(System.getProperty("user.home"), ".android", "devices.xml").toFile()
    PrintWriter(file).use {
        it.println(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n" +
                "<d:devices xmlns:d=\"http://schemas.android.com/sdk/devices/5\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n" +
                "  <d:device>\n" +
                "    <d:name>MyDevice</d:name>\n" +
                "    <d:manufacturer>User</d:manufacturer>\n" +
                "    <d:meta/>\n" +
                "    <d:hardware>\n" +
                "      <d:screen>\n" +
                "        <d:screen-size>large</d:screen-size>\n" +
                "        <d:diagonal-length>7.00</d:diagonal-length>\n" +
                "        <d:pixel-density>xxhdpi</d:pixel-density>\n" +
                "        <d:screen-ratio>long</d:screen-ratio>\n" +
                "        <d:dimensions>\n" +
                "          <d:x-dimension>1080</d:x-dimension>\n" +
                "          <d:y-dimension>3200</d:y-dimension>\n" +
                "        </d:dimensions>\n" +
                "        <d:xdpi>482.48</d:xdpi>\n" +
                "        <d:ydpi>482.48</d:ydpi>\n" +
                "        <d:touch>\n" +
                "          <d:multitouch>jazz-hands</d:multitouch>\n" +
                "          <d:mechanism>finger</d:mechanism>\n" +
                "          <d:screen-type>capacitive</d:screen-type>\n" +
                "        </d:touch>\n" +
                "      </d:screen>\n" +
                "      <d:networking>\n" +
                "Bluetooth\n" +
                "Wifi\n" +
                "NFC</d:networking>\n" +
                "      <d:sensors>\n" +
                "Accelerometer\n" +
                "Barometer\n" +
                "Compass\n" +
                "GPS\n" +
                "Gyroscope\n" +
                "LightSensor\n" +
                "ProximitySensor</d:sensors>\n" +
                "      <d:mic>true</d:mic>\n" +
                "      <d:camera>\n" +
                "        <d:location>back</d:location>\n" +
                "        <d:autofocus>true</d:autofocus>\n" +
                "        <d:flash>true</d:flash>\n" +
                "      </d:camera>\n" +
                "      <d:camera>\n" +
                "        <d:location>front</d:location>\n" +
                "        <d:autofocus>true</d:autofocus>\n" +
                "        <d:flash>true</d:flash>\n" +
                "      </d:camera>\n" +
                "      <d:keyboard>nokeys</d:keyboard>\n" +
                "      <d:nav>nonav</d:nav>\n" +
                "      <d:ram unit=\"MiB\">3000</d:ram>\n" +
                "      <d:buttons>soft</d:buttons>\n" +
                "      <d:internal-storage unit=\"GiB\">\n" +
                "4</d:internal-storage>\n" +
                "      <d:removable-storage unit=\"TiB\"/>\n" +
                "      <d:cpu>Generic CPU</d:cpu>\n" +
                "      <d:gpu>Generic GPU</d:gpu>\n" +
                "      <d:abi>\n" +
                "armeabi\n" +
                "armeabi-v7a\n" +
                "arm64-v8a\n" +
                "x86\n" +
                "x86_64\n" +
                "mips\n" +
                "mips64</d:abi>\n" +
                "      <d:dock/>\n" +
                "      <d:power-type>battery</d:power-type>\n" +
                "    </d:hardware>\n" +
                "    <d:software>\n" +
                "      <d:api-level>-</d:api-level>\n" +
                "      <d:live-wallpaper-support>true</d:live-wallpaper-support>\n" +
                "      <d:bluetooth-profiles/>\n" +
                "      <d:gl-version>2.0</d:gl-version>\n" +
                "      <d:gl-extensions/>\n" +
                "      <d:status-bar>false</d:status-bar>\n" +
                "    </d:software>\n" +
                "    <d:state default=\"true\" name=\"Portrait\">\n" +
                "      <d:description>The device in portrait orientation</d:description>\n" +
                "      <d:screen-orientation>port</d:screen-orientation>\n" +
                "      <d:keyboard-state>keyssoft</d:keyboard-state>\n" +
                "      <d:nav-state>navhidden</d:nav-state>\n" +
                "    </d:state>\n" +
                "    <d:state name=\"Landscape\">\n" +
                "      <d:description>The device in landscape orientation</d:description>\n" +
                "      <d:screen-orientation>land</d:screen-orientation>\n" +
                "      <d:keyboard-state>keyssoft</d:keyboard-state>\n" +
                "      <d:nav-state>navhidden</d:nav-state>\n" +
                "    </d:state>\n" +
                "  </d:device>\n" +
                "</d:devices>\n"
        )
        it.flush()
    }
}

fun emulatorFilesExist(i: Int, avdDir: File): Boolean {
    val file_emul_i_ini = File(avdDir, "emul_$i.ini")
    return file_emul_i_ini.exists()
}

fun createFiles(i: Int, avdDir: File) {
    val file_emul_i_ini = File(avdDir, "emul_$i.ini")
    val file_emul_i_avd = File(avdDir, "emul_$i.avd")
    val relativeLocation = file_emul_i_avd.path.substring(file_emul_i_avd.path.indexOf("avd"))
    PrintWriter(file_emul_i_ini).use {
        it.println(
            "avd.ini.encoding=UTF-8\n" +
                "path=${file_emul_i_avd.path}\n" +
                "path.rel=${relativeLocation}\n" +
                "target=android-30\n"
        )
        it.flush()
    }

    file_emul_i_avd.mkdir()
    val file_config_ini = File(file_emul_i_avd, "config.ini")
    PrintWriter(file_config_ini).use {
        it.println(
            "AvdId=emul_$i\n" +
                "PlayStore.enabled=false\n" +
                "abi.type=x86_64\n" +
                "avd.ini.displayname=emul_$i\n" +
                "avd.ini.encoding=UTF-8\n" +
                "disk.dataPartition.size=2500M\n" +
                "fastboot.chosenSnapshotFile=\n" +
                "fastboot.forceChosenSnapshotBoot=no\n" +
                "fastboot.forceColdBoot=no\n" +
                "fastboot.forceFastBoot=yes\n" +
                "hw.accelerometer=yes\n" +
                "hw.arc=false\n" +
                "hw.audioInput=yes\n" +
                "hw.battery=yes\n" +
                "hw.camera.back=virtualscene\n" +
                "hw.camera.front=emulated\n" +
                "hw.cpu.arch=x86_64\n" +
                "hw.dPad=no\n" +
                "hw.device.hash2=MD5:136aea7d36133232419000067684a792\n" +
                "hw.device.manufacturer=User\n" +
                "hw.device.name=MyDevice\n" +
                "hw.gps=yes\n" +
                "hw.gpu.enabled=yes\n" +
                "hw.gpu.mode=auto\n" +
                "hw.initialOrientation=Portrait\n" +
                "hw.keyboard=yes\n" +
                "hw.lcd.density=480\n" +
                "hw.lcd.height=3200\n" +
                "hw.lcd.width=1080\n" +
                "hw.mainKeys=no\n" +
                "hw.ramSize=55000\n" +
                "hw.sdCard=yes\n" +
                "hw.sensors.orientation=yes\n" +
                "hw.sensors.proximity=yes\n" +
                "hw.trackBall=no\n" +
                "image.sysdir.1=${Paths.get("system-images", "android-30", "google_apis", "x86_64").toFile().relativeTo(File("")).path}\n" +
                "runtime.network.latency=none\n" +
                "runtime.network.speed=full\n" +
                "showDeviceFrame=no\n" +
                "skin.dynamic=yes\n" +
                "skin.name=1080x3200\n" +
                "skin.path=_no_skin\n" +
                "skin.path.backup=_no_skin\n" +
                "tag.display=Google APIs\n" +
                "tag.id=google_apis\n" +
                "vm.heapSize=256\n"
        )
        it.flush()
    }
    val file_userdata_img = File(Automation::class.java.classLoader.getResource("userdata.img")!!.path)
    val file_userdata_img_target = File(file_emul_i_avd, "userdata.img")
    file_userdata_img.copyTo(file_userdata_img_target)
    logger.debug { "Successfully wrote files for emulator $i" }
}

fun isEmulatorRunning(port: Int): Boolean {
    val running = Runtime.getRuntime().exec("adb devices")  // -s emulator-$i shell getprop")
    running.inputStream.bufferedReader(Charsets.UTF_8).use {
        val txt = it.readLines().joinToString()
        logger.debug { txt }
        if (txt.contains(port.toString())) {
            return true
        }
    }
    return false
}

fun startEmulator(i: Int, port: Int) {
    val emulatorPath = Paths.get(System.getenv("ANDROID_SDK_ROOT"), "emulator", "emulator").toAbsolutePath()
    Runtime.getRuntime()
        .exec("$emulatorPath -avd emul_$i -no-snapshot -port $port")
    var stop = false
    while (!stop) {
        val booting = Runtime.getRuntime().exec("adb -s emulator-$port shell getprop init.svc.bootanim")
        booting.inputStream.reader(Charsets.UTF_8).use {
            if (it.readText().startsWith("stopped")) {
                stop = true
            }
        }
        logger.debug { "Waiting until booted..." }
        Thread.sleep(1000)
    }
    Thread.sleep(20000)
}

fun getRootAccess(port: Int) {
    val rooting = Runtime.getRuntime().exec("adb -s emulator-$port root")
    rooting.inputStream.reader(Charsets.UTF_8).use {
        val result = it.readText()
        logger.debug { result }
        if (result.startsWith("restarting adbd as root")) {
            val rooting2 = Runtime.getRuntime().exec("adb -s emulator-$port root")
            rooting2.inputStream.reader(Charsets.UTF_8).use {
                val other = it.readText()
                logger.debug { other }
            }
        }
    }
}

fun isAppInstalled(port: Int): Boolean {
    val rooting = Runtime.getRuntime().exec("adb -s emulator-$port shell pm list packages nl.tudelft.trustchain")
    rooting.inputStream.reader(Charsets.UTF_8).use {
        val result = it.readText()
        logger.debug { result }
        return result.startsWith("package:nl.tudelft.trustchain")
    }
}

fun uninstallApp(port: Int) {
    Runtime.getRuntime().exec("adb -s emulator-$port uninstall nl.tudelft.trustchain")
}

fun getApkFile(): String {
    val file_apk =
        Automation::class.java.classLoader.getResource("app-debug.apk")!!.path
    return if (file_apk.contains("home")) file_apk
    else file_apk.subSequence(1, file_apk.length).toString()  // First character is a misplaced slash
}

fun installApk(port: Int, file_apk: String) {
    logger.debug { "$port: initiating installation" }
    val installing = Runtime.getRuntime().exec("adb -s emulator-$port install -t $file_apk")
    installing.inputStream.bufferedReader(Charsets.UTF_8).use {
        var txt = it.readLine()
        logger.debug { "$port: $txt" }
        txt = it.readLine()
        logger.debug { "$port: $txt" }
    }
}

fun grantPermissions(port: Int) {
    Runtime.getRuntime()
        .exec("adb -s emulator-$port shell pm grant nl.tudelft.trustchain android.permission.WRITE_EXTERNAL_STORAGE")
    Runtime.getRuntime()
        .exec("adb -s emulator-$port shell pm grant nl.tudelft.trustchain android.permission.READ_EXTERNAL_STORAGE")
}

fun isAppRunning(port: Int): Boolean {
    val running = Runtime.getRuntime().exec("adb -s emulator-$port shell ps | grep nl.tudelft.trustchain")
    running.inputStream.reader(Charsets.UTF_8).use {
        val result = it.readText()
        logger.debug { result }
        return result.isNotEmpty()
    }
}

fun runApp(port: Int) {
    val start = Runtime.getRuntime()
        .exec("adb -s emulator-$port shell am start -n nl.tudelft.trustchain/nl.tudelft.trustchain.app.ui.dashboard.DashboardActivity -e activity fedml -e automationPart 0 -e enableExternalAutomation true")
    start.inputStream.reader(Charsets.UTF_8).use {
        logger.debug { "$port: ${it.readText()}" }
    }
}
