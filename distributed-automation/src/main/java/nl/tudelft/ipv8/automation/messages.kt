package nl.tudelft.ipv8.automation

import nl.tudelft.ipv8.messaging.Deserializable
import nl.tudelft.ipv8.messaging.Serializable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

data class MsgNotifyHeartbeat(val unused: Boolean) : Serializable {
    override fun serialize(): ByteArray {
        throw RuntimeException("Only to be used by the slave, not the master")
    }

    companion object Deserializer : Deserializable<MsgNotifyHeartbeat> {
        override fun deserialize(buffer: ByteArray, offset: Int): Pair<MsgNotifyHeartbeat, Int> {
            return Pair(MsgNotifyHeartbeat(true), buffer.size)
        }
    }
}

data class MsgNewTestCommand(val configuration: Map<String, String>, val figureName: String) : Serializable {
    override fun serialize(): ByteArray {
        return ByteArrayOutputStream().use { bos ->
            ObjectOutputStream(bos).use { oos ->
                oos.writeObject(configuration)
                oos.writeObject(figureName)
                oos.flush()
            }
            bos
        }.toByteArray()
    }

    companion object Deserializer : Deserializable<MsgNewTestCommand> {
        override fun deserialize(buffer: ByteArray, offset: Int): Pair<MsgNewTestCommand, Int> {
            // Unused
            throw RuntimeException("Only to be used by the slave, not the master")
        }
    }
}

data class MsgNotifyEvaluation(val evaluation: String) : Serializable {
    override fun serialize(): ByteArray {
        // Unused
        throw RuntimeException("Only to be used by the slave, not the master")
    }

    companion object Deserializer : Deserializable<MsgNotifyEvaluation> {
        override fun deserialize(buffer: ByteArray, offset: Int): Pair<MsgNotifyEvaluation, Int> {
            val croppedBuffer = buffer.copyOfRange(offset, buffer.size)
            ByteArrayInputStream(croppedBuffer).use { bis ->
                ObjectInputStream(bis).use { ois ->
                    return Pair(MsgNotifyEvaluation(ois.readObject() as String), buffer.size)
                }
            }
        }
    }
}

data class MsgNotifyFinished(val unused: Boolean) : Serializable {
    override fun serialize(): ByteArray {
        // Unused
        throw RuntimeException("Only to be used by the slave, not the master")
    }

    companion object Deserializer : Deserializable<MsgNotifyFinished> {
        override fun deserialize(buffer: ByteArray, offset: Int): Pair<MsgNotifyFinished, Int> {
            return Pair(MsgNotifyFinished(true), buffer.size)
        }
    }
}

data class MsgForcedIntroduction(
    val wanPorts: List<Int>,
    val supportsTFTP: Boolean,
    val supportsUTP: Boolean,
    val serviceId: String
) : Serializable {
    override fun serialize(): ByteArray {
        return ByteArrayOutputStream().use { bos ->
            ObjectOutputStream(bos).use { oos ->
                oos.writeObject(wanPorts)
                oos.writeBoolean(supportsTFTP)
                oos.writeBoolean(supportsUTP)
                oos.writeObject(serviceId)
                oos.flush()
            }
            bos
        }.toByteArray()
    }

    companion object Deserializer : Deserializable<MsgForcedIntroduction> {
        override fun deserialize(buffer: ByteArray, offset: Int): Pair<MsgForcedIntroduction, Int> {
            // Unused
            throw RuntimeException("Only to be used by the master, not the slave")
        }
    }
}
