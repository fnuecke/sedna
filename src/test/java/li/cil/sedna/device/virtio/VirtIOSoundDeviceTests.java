package li.cil.sedna.device.virtio;

import li.cil.ceres.BinarySerialization;
import li.cil.sedna.Sedna;
import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.audio.AudioSink;
import li.cil.sedna.api.device.rtc.RealTimeCounter;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.memory.SimpleMemoryMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public final class VirtIOSoundDeviceTests {
    private static final int VIRTIO_MMIO_DRIVER_FEATURES = 0x020;
    private static final int VIRTIO_MMIO_DRIVER_FEATURES_SEL = 0x024;
    private static final int VIRTIO_MMIO_QUEUE_SEL = 0x030;
    private static final int VIRTIO_MMIO_QUEUE_NUM = 0x038;
    private static final int VIRTIO_MMIO_QUEUE_READY = 0x044;
    private static final int VIRTIO_MMIO_QUEUE_NOTIFY = 0x050;
    private static final int VIRTIO_MMIO_STATUS = 0x070;
    private static final int VIRTIO_MMIO_CONFIG = 0x100;
    private static final int VIRTIO_MMIO_QUEUE_DESC_LOW = 0x080;
    private static final int VIRTIO_MMIO_QUEUE_DESC_HIGH = 0x084;
    private static final int VIRTIO_MMIO_QUEUE_DRIVER_LOW = 0x090;
    private static final int VIRTIO_MMIO_QUEUE_DRIVER_HIGH = 0x094;
    private static final int VIRTIO_MMIO_QUEUE_DEVICE_LOW = 0x0A0;
    private static final int VIRTIO_MMIO_QUEUE_DEVICE_HIGH = 0x0A4;

    private static final int FEATURES_HIGH_SEL = 1;
    private static final int VERSION_1_HIGH = 1 << 0;

    private static final int VIRTQ_DESC_F_NEXT = 1;
    private static final int VIRTQ_DESC_F_WRITE = 2;

    private static final int VIRTQ_CONTROL = 0;
    private static final int VIRTQ_TX = 2;
    private static final int QUEUE_COUNT = 4;
    private static final int QUEUE_SIZE = 128;

    private static final int VIRTIO_SND_R_PCM_INFO = 0x0100;
    private static final int VIRTIO_SND_R_PCM_SET_PARAMS = 0x0101;
    private static final int VIRTIO_SND_R_PCM_PREPARE = 0x0102;
    private static final int VIRTIO_SND_R_PCM_RELEASE = 0x0103;
    private static final int VIRTIO_SND_R_PCM_START = 0x0104;
    private static final int VIRTIO_SND_R_PCM_STOP = 0x0105;
    private static final int VIRTIO_SND_R_CHMAP_INFO = 0x0200;

    private static final int VIRTIO_SND_S_OK = 0x8000;
    private static final int VIRTIO_SND_S_BAD_MSG = 0x8001;
    private static final int VIRTIO_SND_S_NOT_SUPP = 0x8002;

    private static final int FMT_MU_LAW = 1;
    private static final int FMT_U8 = 4;
    private static final int FMT_S16 = 5;

    private static final int RATE_11025 = 2;
    private static final int RATE_22050 = 4;
    private static final int RATE_48000 = 7;

    private static final int PCM_INFO_SIZE = 32;
    private static final int STATUS_SIZE = 8;

    private static final long MEMORY_START = 0x80000000L;
    private static final int MEMORY_LENGTH = 1024 * 1024;
    private static final long QUEUE_STRIDE = 0x20000;
    private static final int BUFFER_SIZE = 4096;

    /**
     * 100 ms at 22050 Hz, one byte per sample.
     */
    private static final int PERIOD_BYTES = 2205;
    private static final long PERIOD_NANOS = 100_000_000L;
    /**
     * The smallest period the device accepts.
     */
    private static final int MIN_PERIOD = 32;

    private MemoryMap memoryMap;
    private VirtIOSoundDevice device;
    private RecordingSink sink;
    private long now;
    private final RealTimeCounter clock = new RealTimeCounter() {
        @Override
        public long getTime() {
            return now;
        }

        @Override
        public int getFrequency() {
            return 1_000_000_000; // Nanoseconds, so the time constants above read as durations.
        }
    };

    private final int[] descriptorsUsed = new int[QUEUE_COUNT];
    private final int[] availIndex = new int[QUEUE_COUNT];
    private final int[] usedRead = new int[QUEUE_COUNT];

    @BeforeAll
    public static void setUpAll() {
        Sedna.initialize();
    }

    @BeforeEach
    public void setUp() {
        memoryMap = new SimpleMemoryMap();
        memoryMap.addDevice(MEMORY_START, Memory.create(MEMORY_LENGTH));
        sink = new RecordingSink();
        now = 0;
        device = new VirtIOSoundDevice(memoryMap, sink, clock);
    }

    // ------------------------------------------------------------- //

    @Test
    public void configAdvertisesASinglePlaybackStream() {
        device.reset();

        assertEquals(0, device.load(VIRTIO_MMIO_CONFIG, Sizes.SIZE_32_LOG2),
            "no jacks: the guest has nothing to plug in");
        assertEquals(1, device.load(VIRTIO_MMIO_CONFIG + 4, Sizes.SIZE_32_LOG2),
            "exactly one playback stream");
        assertEquals(0, device.load(VIRTIO_MMIO_CONFIG + 8, Sizes.SIZE_32_LOG2),
            "no channel maps: mono needs none");
    }

    @Test
    public void pcmInfoReportsSupportedFormatsAndRates() throws Exception {
        bringUp();

        final ByteBuffer info = queryPcmInfo();

        assertEquals(VIRTIO_SND_S_OK, info.getInt());
        info.getInt(); // hda_fn_nid
        assertEquals(0, info.getInt(), "no optional stream features are supported");

        final long formats = info.getLong();
        assertNotEquals(0, formats & (1L << FMT_U8), "U8 is what /dev/dsp defaults to");
        assertNotEquals(0, formats & (1L << FMT_S16), "S16 is what alsa-lib apps use");
        assertNotEquals(0, formats & (1L << FMT_MU_LAW), "mu-law lets the guest hand us the wire format");

        final long rates = info.getLong();
        assertNotEquals(0, rates & (1L << RATE_11025), "11025 is DOOM's native rate");
        assertNotEquals(0, rates & (1L << RATE_22050));
        assertEquals(0, rates & (1L << RATE_48000), "advertising a rate we do not want to be sent");

        assertEquals(0, info.get(), "direction must be output");
        assertEquals(1, info.get(), "channels_min");
        assertEquals(1, info.get(), "channels_max: a positional speaker is mono");
    }

    @Test
    public void unsupportedParametersAreRejected() throws Exception {
        bringUp();

        assertEquals(VIRTIO_SND_S_NOT_SUPP, setParams(2, FMT_U8, RATE_22050, PERIOD_BYTES),
            "stereo is not offered");
        assertEquals(VIRTIO_SND_S_NOT_SUPP, setParams(1, FMT_U8, RATE_48000, PERIOD_BYTES),
            "48 kHz is not offered");
        assertEquals(VIRTIO_SND_S_NOT_SUPP, setParams(1, 0, RATE_22050, PERIOD_BYTES),
            "IMA ADPCM is not offered");
        assertEquals(VIRTIO_SND_S_OK, setParams(1, FMT_U8, RATE_22050, PERIOD_BYTES));
    }

    @Test
    public void streamMustBePreparedBeforeItStarts() throws Exception {
        bringUp();

        assertEquals(VIRTIO_SND_S_BAD_MSG, streamRequest(VIRTIO_SND_R_PCM_START),
            "starting an unconfigured stream is a driver bug, not something to play");

        assertEquals(VIRTIO_SND_S_OK, setParams(1, FMT_U8, RATE_22050, PERIOD_BYTES));
        assertEquals(VIRTIO_SND_S_BAD_MSG, streamRequest(VIRTIO_SND_R_PCM_START),
            "parameters alone are not enough, the stream has to be prepared");

        assertEquals(VIRTIO_SND_S_OK, streamRequest(VIRTIO_SND_R_PCM_PREPARE));
        assertEquals(VIRTIO_SND_S_OK, streamRequest(VIRTIO_SND_R_PCM_START));
        assertEquals(VIRTIO_SND_S_BAD_MSG, streamRequest(VIRTIO_SND_R_PCM_STOP + 0x1000),
            "an unknown request code is rejected");
    }

    @Test
    public void prepareIsAcceptedAgainAfterAnUnderrun() throws Exception {
        bringUp();
        startStream(FMT_U8, RATE_22050);

        assertEquals(VIRTIO_SND_S_OK, streamRequest(VIRTIO_SND_R_PCM_STOP));
        assertEquals(VIRTIO_SND_S_OK, streamRequest(VIRTIO_SND_R_PCM_PREPARE),
            "ALSA re-prepares after an xrun without setting parameters again");
        assertEquals(VIRTIO_SND_S_OK, streamRequest(VIRTIO_SND_R_PCM_START));
    }

    @Test
    public void chmapAndJackRequestsAreRefusedRatherThanIgnored() throws Exception {
        bringUp();

        assertEquals(VIRTIO_SND_S_NOT_SUPP, streamRequest(VIRTIO_SND_R_CHMAP_INFO));
    }

    @Test
    public void periodsAreConsumedOnTheClock() throws Exception {
        bringUp();
        startStream(FMT_U8, RATE_22050);
        for (int i = 0; i < 5; i++) {
            publishPeriod(silence(PERIOD_BYTES));
        }

        device.step(1000);
        assertTrue(sink.periods.isEmpty(),
            "completing a buffer the instant the stream starts would run the driver's hardware "
                + "pointer past what the application has written");

        now += PERIOD_NANOS;
        device.step(1000);
        assertEquals(1, sink.periods.size(), "the first period is due one period after the start");

        device.step(1000);
        assertEquals(1, sink.periods.size(), "without time passing nothing more may be consumed");

        now += PERIOD_NANOS;
        device.step(1000);
        assertEquals(2, sink.periods.size(), "one period per period-length of guest time");

        now += PERIOD_NANOS * 2;
        device.step(1000);
        assertEquals(4, sink.periods.size(), "a late step catches up the periods it owes");
    }

    @Test
    public void playbackIsNotPacedByTheCycleCount() throws Exception {
        bringUp();
        startStream(FMT_U8, RATE_22050);
        for (int i = 0; i < 4; i++) {
            publishPeriod(silence(PERIOD_BYTES));
        }

        now += PERIOD_NANOS;
        for (int i = 0; i < 1000; i++) {
            device.step(1_000_000);
        }

        assertEquals(1, sink.periods.size(),
            "a fast emulator must not play audio faster; the guest derives its clock from us");
    }

    @Test
    public void nothingIsConsumedBeforeTheStreamStarts() throws Exception {
        bringUp();
        assertEquals(VIRTIO_SND_S_OK, setParams(1, FMT_U8, RATE_22050, PERIOD_BYTES));
        assertEquals(VIRTIO_SND_S_OK, streamRequest(VIRTIO_SND_R_PCM_PREPARE));
        publishPeriod(silence(PERIOD_BYTES));

        now += PERIOD_NANOS * 10;
        device.step(1000);

        assertTrue(sink.periods.isEmpty(), "a prepared but unstarted stream plays nothing");
    }

    @Test
    public void unsignedSamplesAreConvertedToSigned() throws Exception {
        bringUp();
        startStream(FMT_U8, RATE_22050, MIN_PERIOD);
        publishPeriod(padded(new byte[]{(byte) 0x00, (byte) 0x80, (byte) 0xFF}, MIN_PERIOD, (byte) 0x80));

        now += PERIOD_NANOS;
        device.step(1000);

        final short[] played = sink.periods.get(0);
        assertArrayEquals(new short[]{-32768, 0, 32512}, java.util.Arrays.copyOf(played, 3));
        assertEquals(22050, sink.rates.get(0));
    }

    @Test
    public void muLawSamplesAreDecoded() throws Exception {
        bringUp();
        startStream(FMT_MU_LAW, RATE_11025, MIN_PERIOD);
        publishPeriod(padded(new byte[]{(byte) 0xFF, (byte) 0x7F, (byte) 0x00}, MIN_PERIOD, (byte) 0xFF));

        // Half the sample rate, so the same period size takes twice as long.
        now += PERIOD_NANOS * 2;
        device.step(1000);

        final short[] played = sink.periods.get(0);
        assertEquals(0, played[0], "0xFF is positive zero");
        assertEquals(0, played[1], "0x7F is negative zero");
        assertEquals(-32124, played[2], "0x00 is the largest negative magnitude");
        assertEquals(11025, sink.rates.get(0));
    }

    @Test
    public void signedSamplesArePassedThrough() throws Exception {
        bringUp();
        startStream(FMT_S16, RATE_22050, MIN_PERIOD);
        final ByteBuffer payload = ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        payload.putShort((short) -1234).putShort((short) 4321);
        publishPeriod(padded(payload.array(), MIN_PERIOD, (byte) 0));

        now += PERIOD_NANOS;
        device.step(1000);

        assertArrayEquals(new short[]{-1234, 4321}, java.util.Arrays.copyOf(sink.periods.get(0), 2));
    }

    @Test
    public void everyPlayedPeriodIsReturnedToTheDriver() throws Exception {
        bringUp();
        startStream(FMT_U8, RATE_22050);
        publishPeriod(silence(PERIOD_BYTES));

        now += PERIOD_NANOS;
        device.step(1000);

        assertEquals(1, usedCount(VIRTQ_TX), "the buffer must go back or the driver runs out");
        assertEquals(VIRTIO_SND_S_OK, readTransferStatus(0));
    }

    @Test
    public void playbackSurvivesASaveAndRestore() throws Exception {
        bringUp();
        startStream(FMT_U8, RATE_22050);
        for (int i = 0; i < 4; i++) {
            publishPeriod(silence(PERIOD_BYTES));
        }

        now += PERIOD_NANOS;
        device.step(1000);
        assertEquals(1, sink.periods.size(), "precondition: one period played before the save");

        final ByteBuffer saved = BinarySerialization.serialize(device);

        final RecordingSink restoredSink = new RecordingSink();
        final VirtIOSoundDevice restored = new VirtIOSoundDevice(memoryMap, restoredSink, clock);
        BinarySerialization.deserialize(saved, restored);

        // The three buffers still in the ring must play. If a descriptor chain had been held
        // across the save it would be lost here: the available ring index has already moved
        // past it, so it would never be re-issued and never returned to the driver.
        // The first step after a restore re-derives the period clock, so playback resumes a
        // period later; what matters is that no buffer is lost.
        for (int i = 0; i < 4; i++) {
            now += PERIOD_NANOS;
            restored.step(1000);
        }

        assertEquals(3, restoredSink.periods.size(),
            "every buffer left in the ring must still play after a restore");
        assertEquals(4, usedCount(VIRTQ_TX), "and all four buffers must be back with the driver");
    }

    @Test
    public void underrunDoesNotBurstThroughLaterBuffers() throws Exception {
        bringUp();
        startStream(FMT_U8, RATE_22050);

        // Guest starves the device for a while.
        now += PERIOD_NANOS * 20;
        device.step(1000);
        assertTrue(sink.periods.isEmpty(), "nothing to play while starved");

        for (int i = 0; i < 4; i++) {
            publishPeriod(silence(PERIOD_BYTES));
        }
        device.step(1000);
        assertTrue(sink.periods.isEmpty(), "recovery waits a period rather than replaying the backlog");

        now += PERIOD_NANOS;
        device.step(1000);
        assertEquals(1, sink.periods.size(), "and then resumes at the normal rate");
    }

    @Test
    public void releaseReturnsEveryPendingBuffer() throws Exception {
        bringUp();
        startStream(FMT_U8, RATE_22050);
        for (int i = 0; i < 4; i++) {
            publishPeriod(silence(PERIOD_BYTES));
        }

        now += PERIOD_NANOS;
        device.step(1000);
        assertEquals(1, sink.periods.size(), "precondition: one period played, three still queued");

        assertEquals(VIRTIO_SND_S_OK, streamRequest(VIRTIO_SND_R_PCM_STOP));
        assertEquals(VIRTIO_SND_S_OK, streamRequest(VIRTIO_SND_R_PCM_RELEASE));

        assertEquals(4, usedCount(VIRTQ_TX),
            "the spec requires release to complete every pending I/O message; the driver waits "
                + "for the queue to drain and fails the release if it does not");
        assertEquals(1, sink.periods.size(), "abandoned buffers must not be played on the way out");
    }

    @Test
    public void streamCanBePreparedAgainAfterRelease() throws Exception {
        bringUp();
        startStream(FMT_U8, RATE_22050);

        assertEquals(VIRTIO_SND_S_OK, streamRequest(VIRTIO_SND_R_PCM_STOP));
        assertEquals(VIRTIO_SND_S_OK, streamRequest(VIRTIO_SND_R_PCM_RELEASE));

        // snd_pcm_do_prepare runs snd_pcm_sync_stop -- which releases the stream -- immediately
        // before ops->prepare, with no set-parameters in between. Refusing this breaks every xrun
        // recovery and every replay within one open.
        assertEquals(VIRTIO_SND_S_OK, streamRequest(VIRTIO_SND_R_PCM_PREPARE),
            "release must keep the negotiated parameters so prepare is still legal");
        assertEquals(VIRTIO_SND_S_OK, streamRequest(VIRTIO_SND_R_PCM_START));

        publishPeriod(silence(PERIOD_BYTES));
        now += PERIOD_NANOS;
        device.step(1000);
        assertEquals(1, sink.periods.size(), "and the stream actually plays again");
    }

    @Test
    public void prepareIsRefusedUntilParametersAreSet() throws Exception {
        bringUp();

        assertEquals(VIRTIO_SND_S_BAD_MSG, streamRequest(VIRTIO_SND_R_PCM_PREPARE),
            "a stream that was never configured has nothing to prepare");
    }

    @Test
    public void parametersCannotChangeWhileRunning() throws Exception {
        bringUp();
        startStream(FMT_U8, RATE_22050);

        assertEquals(VIRTIO_SND_S_BAD_MSG, setParams(1, FMT_S16, RATE_11025, PERIOD_BYTES),
            "reformatting a running stream would reinterpret buffers already in the ring");
    }

    @Test
    public void aBufferThatIsNotOnePeriodIsRejected() throws Exception {
        bringUp();
        startStream(FMT_U8, RATE_22050, MIN_PERIOD);
        publishPeriod(silence(MIN_PERIOD * 2));

        now += PERIOD_NANOS;
        device.step(1000);

        assertTrue(sink.periods.isEmpty(),
            "playing an oversized buffer would hand the sink an unbounded chunk while the "
                + "clock advanced by a single period");
        assertEquals(1, usedCount(VIRTQ_TX), "but it still has to go back to the driver");
        assertEquals(VIRTIO_SND_S_BAD_MSG, readTransferStatus(0));
    }

    @Test
    public void aTransferForAnotherStreamIsRejectedAcrossDescriptors() throws Exception {
        bringUp();
        startStream(FMT_U8, RATE_22050);

        // A multi-descriptor payload makes the device skip across a descriptor boundary on the
        // way to the status, which is the case SplitVirtqueue.skip's bounds check gets wrong.
        publishPeriodForStream(1, silence(PERIOD_BYTES));

        now += PERIOD_NANOS;
        device.step(1000);

        assertTrue(sink.periods.isEmpty(), "the stream id does not exist");
        assertEquals(VIRTIO_SND_S_BAD_MSG, readTransferStatus(0));
    }

    @Test
    public void pcmInfoRejectsAnOutOfRangeQueryWithoutOverflowing() throws Exception {
        bringUp();

        // start_id + count overflows a naive range check, and the item loop would then run
        // billions of times on the emulator thread.
        final long start = System.nanoTime();
        assertEquals(VIRTIO_SND_S_BAD_MSG, queryPcmInfo(2, Integer.MAX_VALUE - 1, PCM_INFO_SIZE).getInt());
        assertTrue(System.nanoTime() - start < 1_000_000_000L, "must be rejected, not iterated");
    }

    @Test
    public void playbackDoesNotResumeAfterTheDeviceEntersItsErrorState() throws Exception {
        bringUp();
        startStream(FMT_U8, RATE_22050);
        device.error(); // what a malformed chain does

        publishPeriod(silence(PERIOD_BYTES));
        now += PERIOD_NANOS * 4;
        device.step(1000);

        assertTrue(sink.periods.isEmpty(), "a device that needs a reset must stop playing");
    }

    // ------------------------------------------------------------- //

    private void bringUp() {
        device.store(VIRTIO_MMIO_STATUS, 0, Sizes.SIZE_32_LOG2);
        device.store(VIRTIO_MMIO_STATUS, AbstractVirtIODevice.VIRTIO_STATUS_ACKNOWLEDGE, Sizes.SIZE_32_LOG2);
        device.store(VIRTIO_MMIO_STATUS, AbstractVirtIODevice.VIRTIO_STATUS_ACKNOWLEDGE
            | AbstractVirtIODevice.VIRTIO_STATUS_DRIVER, Sizes.SIZE_32_LOG2);

        device.store(VIRTIO_MMIO_DRIVER_FEATURES_SEL, 0, Sizes.SIZE_32_LOG2);
        device.store(VIRTIO_MMIO_DRIVER_FEATURES, 0, Sizes.SIZE_32_LOG2);
        device.store(VIRTIO_MMIO_DRIVER_FEATURES_SEL, FEATURES_HIGH_SEL, Sizes.SIZE_32_LOG2);
        device.store(VIRTIO_MMIO_DRIVER_FEATURES, VERSION_1_HIGH, Sizes.SIZE_32_LOG2);

        device.store(VIRTIO_MMIO_STATUS, AbstractVirtIODevice.VIRTIO_STATUS_ACKNOWLEDGE
            | AbstractVirtIODevice.VIRTIO_STATUS_DRIVER
            | AbstractVirtIODevice.VIRTIO_STATUS_FEATURES_OK, Sizes.SIZE_32_LOG2);

        for (int queue = 0; queue < QUEUE_COUNT; queue++) {
            device.store(VIRTIO_MMIO_QUEUE_SEL, queue, Sizes.SIZE_32_LOG2);
            device.store(VIRTIO_MMIO_QUEUE_NUM, QUEUE_SIZE, Sizes.SIZE_32_LOG2);
            storeAddress(VIRTIO_MMIO_QUEUE_DESC_LOW, VIRTIO_MMIO_QUEUE_DESC_HIGH, desc(queue));
            storeAddress(VIRTIO_MMIO_QUEUE_DRIVER_LOW, VIRTIO_MMIO_QUEUE_DRIVER_HIGH, avail(queue));
            storeAddress(VIRTIO_MMIO_QUEUE_DEVICE_LOW, VIRTIO_MMIO_QUEUE_DEVICE_HIGH, used(queue));
            device.store(VIRTIO_MMIO_QUEUE_READY, 1, Sizes.SIZE_32_LOG2);
        }

        device.store(VIRTIO_MMIO_STATUS, AbstractVirtIODevice.VIRTIO_STATUS_ACKNOWLEDGE
            | AbstractVirtIODevice.VIRTIO_STATUS_DRIVER
            | AbstractVirtIODevice.VIRTIO_STATUS_FEATURES_OK
            | AbstractVirtIODevice.VIRTIO_STATUS_DRIVER_OK, Sizes.SIZE_32_LOG2);
    }

    private void startStream(final int format, final int rate) throws MemoryAccessException {
        startStream(format, rate, PERIOD_BYTES);
    }

    private void startStream(final int format, final int rate, final int periodBytes)
        throws MemoryAccessException {
        assertEquals(VIRTIO_SND_S_OK, setParams(1, format, rate, periodBytes));
        assertEquals(VIRTIO_SND_S_OK, streamRequest(VIRTIO_SND_R_PCM_PREPARE));
        assertEquals(VIRTIO_SND_S_OK, streamRequest(VIRTIO_SND_R_PCM_START));
    }

    private int setParams(final int channels, final int format, final int rate, final int periodBytes)
        throws MemoryAccessException {
        final ByteBuffer request = ByteBuffer.allocate(24).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        request.putInt(VIRTIO_SND_R_PCM_SET_PARAMS);
        request.putInt(0); // stream_id
        request.putInt(periodBytes * 4); // buffer_bytes
        request.putInt(periodBytes);
        request.putInt(0); // features
        request.put((byte) channels);
        request.put((byte) format);
        request.put((byte) rate);
        request.put((byte) 0); // padding
        return controlRequest(request.array(), 4).getInt();
    }

    private int streamRequest(final int code) throws MemoryAccessException {
        final ByteBuffer request = ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        request.putInt(code);
        request.putInt(0); // stream_id
        return controlRequest(request.array(), 4).getInt();
    }

    private ByteBuffer queryPcmInfo() throws MemoryAccessException {
        return queryPcmInfo(0, 1, PCM_INFO_SIZE);
    }

    private ByteBuffer queryPcmInfo(final int startId, final int count, final int size)
        throws MemoryAccessException {
        final ByteBuffer request = ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        request.putInt(VIRTIO_SND_R_PCM_INFO);
        request.putInt(startId);
        request.putInt(count);
        request.putInt(size);
        return controlRequest(request.array(), 4 + PCM_INFO_SIZE);
    }

    /**
     * Publishes a control request as a read-only descriptor chained to a write-only one, notifies
     * the device, and returns what it wrote back.
     */
    private ByteBuffer controlRequest(final byte[] request, final int responseSize) throws MemoryAccessException {
        final int head = descriptorsUsed[VIRTQ_CONTROL];
        writeDescriptor(VIRTQ_CONTROL, request, false, true);
        final int responseIndex = descriptorsUsed[VIRTQ_CONTROL];
        writeDescriptor(VIRTQ_CONTROL, new byte[responseSize], true, false);
        publishChain(VIRTQ_CONTROL, head);

        device.store(VIRTIO_MMIO_QUEUE_NOTIFY, VIRTQ_CONTROL, Sizes.SIZE_32_LOG2);

        final byte[] response = new byte[responseSize];
        final long address = data(VIRTQ_CONTROL, responseIndex);
        for (int i = 0; i < responseSize; i++) {
            response[i] = (byte) memoryMap.load(address + i, Sizes.SIZE_8_LOG2);
        }
        return ByteBuffer.wrap(response).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Publishes one period the way the driver does: the transfer header and the samples as
     * read-only descriptors, the status as a write-only one.
     */
    private void publishPeriod(final byte[] samples) throws MemoryAccessException {
        publishPeriodForStream(0, samples);
    }

    private void publishPeriodForStream(final int streamId, final byte[] samples) throws MemoryAccessException {
        final ByteBuffer header = ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        header.putInt(streamId);

        final int head = descriptorsUsed[VIRTQ_TX];
        writeDescriptor(VIRTQ_TX, header.array(), false, true);
        writeDescriptor(VIRTQ_TX, samples, false, true);
        writeDescriptor(VIRTQ_TX, new byte[STATUS_SIZE], true, false);
        publishChain(VIRTQ_TX, head);
    }

    private void writeDescriptor(final int queue, final byte[] contents, final boolean deviceWritable,
                                 final boolean hasNext) throws MemoryAccessException {
        final int index = descriptorsUsed[queue]++;
        assertTrue(contents.length <= BUFFER_SIZE, "test buffer too small for " + contents.length + " bytes");

        final long buffer = data(queue, index);
        for (int i = 0; i < contents.length; i++) {
            memoryMap.store(buffer + i, contents[i], Sizes.SIZE_8_LOG2);
        }

        final long descriptor = desc(queue) + (long) index * 16;
        memoryMap.store(descriptor, buffer, Sizes.SIZE_64_LOG2);
        memoryMap.store(descriptor + 8, contents.length, Sizes.SIZE_32_LOG2);
        memoryMap.store(descriptor + 12, (deviceWritable ? VIRTQ_DESC_F_WRITE : 0)
            | (hasNext ? VIRTQ_DESC_F_NEXT : 0), Sizes.SIZE_16_LOG2);
        memoryMap.store(descriptor + 14, index + 1, Sizes.SIZE_16_LOG2);
    }

    private void publishChain(final int queue, final int head) throws MemoryAccessException {
        final int ring = availIndex[queue]++;
        memoryMap.store(avail(queue) + 4 + (long) (ring % QUEUE_SIZE) * 2, head, Sizes.SIZE_16_LOG2);
        memoryMap.store(avail(queue) + 2, availIndex[queue], Sizes.SIZE_16_LOG2); // idx last
    }

    private int usedCount(final int queue) throws MemoryAccessException {
        return (int) (memoryMap.load(used(queue) + 2, Sizes.SIZE_16_LOG2) & 0xFFFF);
    }

    /**
     * Reads the status the device wrote for the n-th completed transfer.
     */
    private int readTransferStatus(final int index) throws MemoryAccessException {
        final long entry = used(VIRTQ_TX) + 4 + (long) (index % QUEUE_SIZE) * 8;
        final int head = (int) memoryMap.load(entry, Sizes.SIZE_32_LOG2);
        // The status descriptor is the third in the chain.
        return (int) memoryMap.load(data(VIRTQ_TX, head + 2), Sizes.SIZE_32_LOG2);
    }

    private void storeAddress(final int lowRegister, final int highRegister, final long address) {
        device.store(lowRegister, (int) address, Sizes.SIZE_32_LOG2);
        device.store(highRegister, (int) (address >>> 32), Sizes.SIZE_32_LOG2);
    }

    private long base(final int queue) {
        return MEMORY_START + QUEUE_STRIDE * queue;
    }

    private long desc(final int queue) {
        return base(queue);
    }

    private long avail(final int queue) {
        return base(queue) + 0x4000;
    }

    private long used(final int queue) {
        return base(queue) + 0x8000;
    }

    private long data(final int queue, final int index) {
        return base(queue) + 0xC000 + (long) index * BUFFER_SIZE;
    }

    private static byte[] padded(final byte[] head, final int length, final byte fill) {
        final byte[] result = new byte[length];
        java.util.Arrays.fill(result, fill);
        System.arraycopy(head, 0, result, 0, head.length);
        return result;
    }

    private static byte[] silence(final int length) {
        final byte[] samples = new byte[length];
        java.util.Arrays.fill(samples, (byte) 0x80);
        return samples;
    }

    private static final class RecordingSink implements AudioSink {
        final List<short[]> periods = new ArrayList<>();
        final List<Integer> rates = new ArrayList<>();

        @Override
        public void write(final ByteBuffer samples, final int sampleRate) {
            final short[] copy = new short[samples.remaining() / 2];
            samples.asShortBuffer().get(copy);
            periods.add(copy);
            rates.add(sampleRate);
        }
    }
}
