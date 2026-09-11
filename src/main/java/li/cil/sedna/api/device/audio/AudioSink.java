package li.cil.sedna.api.device.audio;

import java.nio.ByteBuffer;

/**
 * Receives audio played back by an emulated sound device.
 */
public interface AudioSink {
    /**
     * Called with samples provided by the guest system.
     * <p>
     * Samples are signed 16 bit, little endian, single channel, regardless of the format the
     * guest selected. The buffer is reused between calls, so implementations must copy anything
     * they wish to retain.
     * <p>
     * Called at the rate the samples are consumed at, so implementations may use it as the
     * playback clock. A guest that stops feeding the device simply stops producing calls.
     * <p>
     * <b>This may be called from a worker thread.</b>
     *
     * @param samples    the samples played back.
     * @param sampleRate the rate the samples are played back at, in Hz.
     */
    void write(ByteBuffer samples, int sampleRate);
}
