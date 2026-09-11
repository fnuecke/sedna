package li.cil.sedna.device.virtio;

import li.cil.ceres.api.Serialized;
import li.cil.sedna.api.device.Steppable;
import li.cil.sedna.api.device.audio.AudioSink;
import li.cil.sedna.api.device.rtc.RealTimeCounter;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// https://docs.oasis-open.org/virtio/virtio/v1.2/csd01/virtio-v1.2-csd01.pdf
public final class VirtIOSoundDevice extends AbstractVirtIODevice implements Steppable {
    private static final int VIRTQ_CONTROL = 0;
    private static final int VIRTQ_EVENT = 1;
    private static final int VIRTQ_TX = 2;
    private static final int VIRTQ_RX = 3;
    private static final int VIRTQ_COUNT = 4;

    private static final int QUEUE_SIZE = 128;

    private static final int STREAM_COUNT = 1;
    private static final int JACK_COUNT = 0;
    private static final int CHMAP_COUNT = 0;

    private static final int CFG_JACKS_OFFSET = 0;
    private static final int CFG_STREAMS_OFFSET = 4;
    private static final int CFG_CHMAPS_OFFSET = 8;
    private static final int CFG_SIZE = 12;

    private static final int VIRTIO_SND_R_JACK_INFO = 0x0001;
    private static final int VIRTIO_SND_R_JACK_REMAP = 0x0002;
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

    private static final int VIRTIO_SND_D_OUTPUT = 0;

    private static final int VIRTIO_SND_PCM_FMT_MU_LAW = 1;
    private static final int VIRTIO_SND_PCM_FMT_U8 = 4;
    private static final int VIRTIO_SND_PCM_FMT_S16 = 5;

    private static final int VIRTIO_SND_PCM_RATE_11025 = 2;
    private static final int VIRTIO_SND_PCM_RATE_22050 = 4;

    private static final long SUPPORTED_FORMATS =
        (1L << VIRTIO_SND_PCM_FMT_MU_LAW) | (1L << VIRTIO_SND_PCM_FMT_U8) | (1L << VIRTIO_SND_PCM_FMT_S16);
    private static final long SUPPORTED_RATES =
        (1L << VIRTIO_SND_PCM_RATE_11025) | (1L << VIRTIO_SND_PCM_RATE_22050);

    private static final int QUERY_INFO_SIZE = 16;
    private static final int PCM_INFO_SIZE = 32;
    private static final int PCM_HDR_SIZE = 8;
    private static final int SET_PARAMS_SIZE = 24;
    private static final int XFER_SIZE = 4;
    private static final int RESPONSE_BUFFER_SIZE = 64;

    private static final int MIN_PERIOD_BYTES = 32;
    private static final int MAX_PERIOD_BYTES = 64 * 1024;

    private static final short[] MU_LAW_TO_PCM = buildMuLawTable();

    private enum StreamState {
        UNSET,
        SET,
        PREPARED,
        RUNNING,
    }

    @Serialized
    private StreamState state = StreamState.UNSET;
    @Serialized
    private int periodBytes;
    @Serialized
    private int format = VIRTIO_SND_PCM_FMT_S16;
    @Serialized
    private int sampleRate;

    private final transient AudioSink sink;
    private final transient RealTimeCounter clock;
    private final transient ByteBuffer request =
        ByteBuffer.allocate(SET_PARAMS_SIZE).order(ByteOrder.LITTLE_ENDIAN);
    private final transient ByteBuffer response =
        ByteBuffer.allocate(RESPONSE_BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
    private transient ByteBuffer samples = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN);

    private transient long periodTicks;
    private transient long nextPeriodDeadline;
    private transient boolean needsResync = true;

    public VirtIOSoundDevice(final MemoryMap memoryMap, final AudioSink sink, final RealTimeCounter clock) {
        super(memoryMap, VirtIODeviceSpec
            .builder(VirtIODeviceType.VIRTIO_DEVICE_ID_SOUND_DEVICE)
            .queueCount(VIRTQ_COUNT)
            .queueSizeMax(QUEUE_SIZE)
            .configSpaceSize(CFG_SIZE)
            .build());
        this.sink = sink;
        this.clock = clock;
    }

    // ------------------------------------------------------------- //

    @Override
    public void step(final int cycles) {
        if (state != StreamState.RUNNING) {
            return;
        }
        if ((getStatus() & (VIRTIO_STATUS_FAILED | VIRTIO_STATUS_DEVICE_NEEDS_RESET)) != 0) {
            return;
        }
        if (periodTicks <= 0) {
            if (sampleRate <= 0 || periodBytes <= 0) {
                return;
            }
            updatePeriodTiming();
        }

        final long now = clock.getTime();
        if (needsResync) {
            nextPeriodDeadline = now + periodTicks;
            needsResync = false;
        }

        try {
            while (now - nextPeriodDeadline >= 0) {
                if (!completeNextPeriod()) {
                    // Underrun. Resynchronize so recovery does not burst through the backlog.
                    nextPeriodDeadline = now + periodTicks;
                    break;
                }
                nextPeriodDeadline += periodTicks;
            }
        } catch (final VirtIODeviceException | MemoryAccessException e) {
            error();
        }
    }

    // ------------------------------------------------------------- //

    @Override
    protected void initializeConfig() {
        setConfigValue(CFG_JACKS_OFFSET, JACK_COUNT);
        setConfigValue(CFG_STREAMS_OFFSET, STREAM_COUNT);
        setConfigValue(CFG_CHMAPS_OFFSET, CHMAP_COUNT);
    }

    @Override
    protected void handleFeaturesNegotiated() {
        // We pump audio data in step(), wo we don't care about notifications on these queues.
        setQueueNotifications(VIRTQ_EVENT, false);
        setQueueNotifications(VIRTQ_TX, false);
        setQueueNotifications(VIRTQ_RX, false);
    }

    @Override
    protected void handleQueueNotification(final int queueIndex) throws VirtIODeviceException, MemoryAccessException {
        if (queueIndex != VIRTQ_CONTROL) {
            return;
        }

        final VirtqueueIterator queue = getQueueIterator(VIRTQ_CONTROL);
        if (queue == null) {
            return;
        }

        while (queue.hasNext()) {
            final DescriptorChain chain = queue.next();
            handleControlRequest(chain);
            chain.use();
        }
    }

    @Override
    public void reset() {
        super.reset();
        state = StreamState.UNSET;
        periodBytes = 0;
        format = VIRTIO_SND_PCM_FMT_S16;
        sampleRate = 0;
        periodTicks = 0;
        nextPeriodDeadline = 0;
        needsResync = true;
    }

    // ------------------------------------------------------------- //

    private void handleControlRequest(final DescriptorChain chain) throws VirtIODeviceException, MemoryAccessException {
        if (chain.readableBytes() < 4) {
            respond(chain, VIRTIO_SND_S_BAD_MSG);
            return;
        }

        final int code = readRequest(chain, 4).getInt();
        switch (code) {
            case VIRTIO_SND_R_PCM_INFO -> handlePcmInfo(chain);
            case VIRTIO_SND_R_PCM_SET_PARAMS -> handleSetParams(chain);
            case VIRTIO_SND_R_PCM_PREPARE, VIRTIO_SND_R_PCM_START,
                 VIRTIO_SND_R_PCM_STOP, VIRTIO_SND_R_PCM_RELEASE -> handleStreamTransition(chain, code);
            case VIRTIO_SND_R_JACK_INFO, VIRTIO_SND_R_JACK_REMAP, VIRTIO_SND_R_CHMAP_INFO ->
                respond(chain, VIRTIO_SND_S_NOT_SUPP);
            default -> respond(chain, VIRTIO_SND_S_BAD_MSG);
        }
    }

    private void handlePcmInfo(final DescriptorChain chain) throws VirtIODeviceException, MemoryAccessException {
        if (chain.readableBytes() < QUERY_INFO_SIZE - 4) {
            respond(chain, VIRTIO_SND_S_BAD_MSG);
            return;
        }

        final ByteBuffer query = readRequest(chain, QUERY_INFO_SIZE - 4);
        final int startId = query.getInt();
        final int count = query.getInt();
        final int size = query.getInt();

        if (startId < 0 || count < 0 || count > STREAM_COUNT || startId > STREAM_COUNT - count || size < 0) {
            respond(chain, VIRTIO_SND_S_BAD_MSG);
            return;
        }

        chain.skip(chain.readableBytes());

        response.clear();
        response.putInt(VIRTIO_SND_S_OK);
        response.flip();
        put(chain, response);

        for (int i = 0; i < count && chain.writableBytes() > 0; i++) {
            response.clear();
            response.putInt(0); // struct virtio_snd_info { hda_fn_nid }
            response.putInt(0); // le32 features
            response.putLong(SUPPORTED_FORMATS); // le64 formats
            response.putLong(SUPPORTED_RATES); // le64 rates
            response.put((byte) VIRTIO_SND_D_OUTPUT); // u8 direction
            response.put((byte) 1); // u8 channels_min
            response.put((byte) 1); // u8 channels_max
            response.put(new byte[5]); // u8 padding[5]
            response.flip();
            response.limit(Math.min(response.limit(), size));
            put(chain, response);

            for (int padding = size - PCM_INFO_SIZE; padding > 0 && chain.writableBytes() > 0; padding--) {
                chain.put((byte) 0);
            }
        }
    }

    private void handleSetParams(final DescriptorChain chain) throws VirtIODeviceException, MemoryAccessException {
        if (chain.readableBytes() < SET_PARAMS_SIZE - 4) {
            respond(chain, VIRTIO_SND_S_BAD_MSG);
            return;
        }

        final ByteBuffer params = readRequest(chain, SET_PARAMS_SIZE - 4);
        final int streamId = params.getInt();
        final int newBufferBytes = params.getInt();
        final int newPeriodBytes = params.getInt();
        final int features = params.getInt();
        final int channels = params.get() & 0xFF;
        final int newFormat = params.get() & 0xFF;
        final int rate = params.get() & 0xFF;

        if (streamId != 0) {
            respond(chain, VIRTIO_SND_S_BAD_MSG);
            return;
        }
        if (state == StreamState.RUNNING) {
            respond(chain, VIRTIO_SND_S_BAD_MSG);
            return;
        }

        final int newSampleRate = toSampleRate(rate);
        if (features != 0
            || channels != 1
            || newSampleRate == 0
            || (SUPPORTED_FORMATS & (1L << newFormat)) == 0
            || newPeriodBytes < MIN_PERIOD_BYTES
            || newPeriodBytes > MAX_PERIOD_BYTES
            || newBufferBytes < newPeriodBytes) {
            respond(chain, VIRTIO_SND_S_NOT_SUPP);
            return;
        }

        periodBytes = newPeriodBytes;
        format = newFormat;
        sampleRate = newSampleRate;
        state = StreamState.SET;
        updatePeriodTiming();

        respond(chain, VIRTIO_SND_S_OK);
    }

    private void handleStreamTransition(final DescriptorChain chain, final int code)
        throws VirtIODeviceException, MemoryAccessException {
        if (chain.readableBytes() < PCM_HDR_SIZE - 4) {
            respond(chain, VIRTIO_SND_S_BAD_MSG);
            return;
        }

        final int streamId = readRequest(chain, PCM_HDR_SIZE - 4).getInt();
        if (streamId != 0) {
            respond(chain, VIRTIO_SND_S_BAD_MSG);
            return;
        }

        final StreamState target = transitionTarget(code);
        if (target == null) {
            respond(chain, VIRTIO_SND_S_BAD_MSG);
            return;
        }

        state = target;
        if (target == StreamState.RUNNING) {
            nextPeriodDeadline = clock.getTime() + periodTicks;
            needsResync = periodTicks <= 0;
        } else if (code == VIRTIO_SND_R_PCM_RELEASE) {
            // 5.14.6.6.5.1: on release the device must complete all pending I/O messages for the
            // stream. The driver waits for the queue to drain and fails the release if it does not.
            drainTransferQueue();
        }

        respond(chain, VIRTIO_SND_S_OK);
    }

    @Nullable
    private StreamState transitionTarget(final int code) {
        final boolean isConfigured = sampleRate > 0 && periodBytes > 0;
        return switch (code) {
            case VIRTIO_SND_R_PCM_PREPARE -> isConfigured && state != StreamState.RUNNING ? StreamState.PREPARED : null;
            case VIRTIO_SND_R_PCM_START -> state == StreamState.PREPARED ? StreamState.RUNNING : null;
            case VIRTIO_SND_R_PCM_STOP -> state == StreamState.RUNNING ? StreamState.PREPARED : null;
            case VIRTIO_SND_R_PCM_RELEASE ->
                state == StreamState.PREPARED || state == StreamState.RUNNING ? StreamState.SET : null;
            default -> null;
        };
    }

    private void drainTransferQueue() throws VirtIODeviceException, MemoryAccessException {
        final VirtqueueIterator queue = getQueueIterator(VIRTQ_TX);
        if (queue == null) {
            return;
        }

        while (queue.hasNext()) {
            final DescriptorChain chain = queue.next();
            writeTransferStatus(chain, VIRTIO_SND_S_OK);
            chain.use();
        }
    }

    private boolean completeNextPeriod() throws VirtIODeviceException, MemoryAccessException {
        final VirtqueueIterator queue = getQueueIterator(VIRTQ_TX);
        if (queue == null || !queue.hasNext()) {
            return false;
        }

        final DescriptorChain chain = queue.next();
        if (chain.readableBytes() < XFER_SIZE) {
            writeTransferStatus(chain, VIRTIO_SND_S_BAD_MSG);
            chain.use();
            return true;
        }

        final int streamId = readRequest(chain, XFER_SIZE).getInt();
        if (streamId != 0 || chain.readableBytes() != periodBytes) {
            writeTransferStatus(chain, VIRTIO_SND_S_BAD_MSG);
            chain.use();
            return true;
        }

        try {
            playPeriod(chain);
            writeTransferStatus(chain, VIRTIO_SND_S_OK);
        } finally {
            chain.use();
        }
        return true;
    }

    private void playPeriod(final DescriptorChain chain) throws VirtIODeviceException, MemoryAccessException {
        final int available = chain.readableBytes();
        if (available == 0) {
            return;
        }

        final int bytesPerSample = format == VIRTIO_SND_PCM_FMT_S16 ? 2 : 1;
        final int sampleCount = available / bytesPerSample;
        ensureSampleCapacity(sampleCount * 2);

        samples.clear();
        samples.limit(sampleCount * 2);

        if (format == VIRTIO_SND_PCM_FMT_S16) {
            chain.get(samples);
        } else {
            for (int i = 0; i < sampleCount; i++) {
                final int value = chain.get() & 0xFF;
                samples.putShort(format == VIRTIO_SND_PCM_FMT_MU_LAW
                    ? MU_LAW_TO_PCM[value]
                    : (short) ((value - 128) << 8));
            }
        }
        samples.flip();

        sink.write(samples, sampleRate);
    }

    private void writeTransferStatus(final DescriptorChain chain, final int status)
        throws VirtIODeviceException, MemoryAccessException {
        chain.skip(chain.readableBytes());
        response.clear();
        response.putInt(status);
        response.putInt(0);
        response.flip();
        put(chain, response);
    }

    private void updatePeriodTiming() {
        final int bytesPerSample = format == VIRTIO_SND_PCM_FMT_S16 ? 2 : 1;
        final int samplesPerPeriod = Math.max(1, periodBytes / bytesPerSample);
        periodTicks = (long) clock.getFrequency() * samplesPerPeriod / sampleRate;
        needsResync = true;
    }

    private void ensureSampleCapacity(final int bytes) {
        if (samples.capacity() < bytes) {
            samples = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
        }
    }

    private ByteBuffer readRequest(final DescriptorChain chain, final int length)
        throws VirtIODeviceException, MemoryAccessException {
        request.clear();
        request.limit(length);
        chain.get(request);
        request.flip();
        return request;
    }

    private void respond(final DescriptorChain chain, final int status)
        throws VirtIODeviceException, MemoryAccessException {
        chain.skip(chain.readableBytes());
        response.clear();
        response.putInt(status);
        response.flip();
        put(chain, response);
    }

    private static void put(final DescriptorChain chain, final ByteBuffer buffer)
        throws VirtIODeviceException, MemoryAccessException {
        if (buffer.remaining() > chain.writableBytes()) {
            buffer.limit(buffer.position() + chain.writableBytes());
        }
        if (buffer.hasRemaining()) {
            chain.put(buffer);
        }
    }

    private static int toSampleRate(final int rate) {
        return switch (rate) {
            case VIRTIO_SND_PCM_RATE_11025 -> 11025;
            case VIRTIO_SND_PCM_RATE_22050 -> 22050;
            default -> 0;
        };
    }

    private static short[] buildMuLawTable() {
        final short[] table = new short[256];
        for (int i = 0; i < table.length; i++) {
            final int value = ~i & 0xFF;
            int magnitude = ((value & 0x0F) << 3) + 0x84;
            magnitude <<= (value & 0x70) >> 4;
            table[i] = (short) ((value & 0x80) != 0 ? 0x84 - magnitude : magnitude - 0x84);
        }
        return table;
    }
}
