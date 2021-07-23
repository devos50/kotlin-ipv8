package nl.tudelft.ipv8.messaging.utp.channels.impl.alg;

public class UtpAlgConfiguration {

    public static final int MAX_CONNECTION_ATTEMPTS = 5;
    public static final int CONNECTION_ATTEMPT_INTERVAL_MILLIS = 5000;

    public static final long MINIMUM_DELTA_TO_MAX_WINDOW_MICROS = 1000000;
    // ack every second packets
    public static final int SKIP_PACKETS_UNTIL_ACK = 1;

    /**
     * Auto ack every packet that is smaller than ACK_NR from ack packet.
     * Some Implementations like libutp do this.
     */
    public static final boolean AUTO_ACK_SMALLER_THAN_ACK_NUMBER = true;
    /**
     * if oldest mindelay sample is older than that, update it.
     */
    public static final long MINIMUM_DIFFERENCE_TIMESTAMP_MICROSEC = 120000000L;
    /**
     * timeout
     */
    public static final int MINIMUM_TIMEOUT_MILLIS = 500;
    /**
     * Packet size mode
     */
    public static final PacketSizeModus PACKET_SIZE_MODE = PacketSizeModus.CONSTANT_1472;
    /**
     * maximum packet size should be dynamically set once path mtu discovery
     * implemented.
     */
    public static final int MAX_PACKET_SIZE = 1472;
    /**
     * minimum packet size.
     */
    public static final int MIN_PACKET_SIZE = 150;
    /**
     * Minimum path MTU
     */
    public static final int MINIMUM_MTU = 576;
    /**
     * Maximal window increase per RTT - increase to allow uTP throttle up
     * faster.
     */
    public static final int MAX_CWND_INCREASE_PACKETS_PER_RTT = 3000;
    /**
     * maximal buffering delay
     */
    public static final int C_CONTROL_TARGET_MICROS = 100000;
    /**
     * activate burst sending
     */
    public static final boolean SEND_IN_BURST = true;
    /**
     * Reduce burst sending artificially
     */
    public static final int MAX_BURST_SEND = 5;
    /**
     * Minimum number of acks past seqNr=x to trigger a resend of seqNr=x;
     */
    public static final int MIN_SKIP_PACKET_BEFORE_RESEND = 3;
    public static final long MICROSECOND_WAIT_BETWEEN_BURSTS = 28000;
    public static final long TIME_WAIT_AFTER_LAST_PACKET = 3000000;
    public static final boolean ONLY_POSITIVE_GAIN = false;
    public static final boolean DEBUG = false;
}
