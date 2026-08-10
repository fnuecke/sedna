package li.cil.sedna.device.virtio;

import li.cil.ceres.api.Serialized;
import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.memory.MemoryMaps;

import java.nio.ByteBuffer;
import java.util.NoSuchElementException;

/**
 * Implementation of Split Virtqueues as defined in chapter 2.6 of the VirtIO spec.
 */
@Serialized
final class SplitVirtqueue extends AbstractVirtqueue {
    private static final int VIRTQ_MAX_CHAIN_LENGTH = 128; // Max chain length because we don't trust drivers.

    private static final int VIRTQ_DESC_TABLE_STRIDE = 16;
    private static final int VIRTQ_DESC_ADDR = 0;
    private static final int VIRTQ_DESC_LEN = 8;
    private static final int VIRTQ_DESC_FLAGS = 12;
    private static final int VIRTQ_DESC_NEXT = 14;

    private static final int VIRTQ_AVAIL_FLAGS = 0;
    private static final int VIRTQ_AVAIL_IDX = 2;
    private static final int VIRTQ_AVAIL_RING = 4;
    private static final int VIRTQ_AVAILABLE_RING_STRIDE = 2;

    private static final int VIRTQ_USED_FLAGS = 0;
    private static final int VIRTQ_USED_IDX = 2;
    private static final int VIRTQ_USED_RING = 4;
    private static final int VIRTQ_USED_RING_STRIDE = 8;
    private static final int VIRTQ_USED_RING_ELEM_ID = 0;
    private static final int VIRTQ_USED_RING_ELEM_LEN = 4;

    private static final int VIRTQ_DESC_F_NEXT = 1;
    private static final int VIRTQ_DESC_F_WRITE = 2;
    private static final int VIRTQ_DESC_F_INDIRECT = 4;

    private final transient MemoryMap memoryMap;
    private final transient VirtqueueContext context;

    /**
     * This is where we last stopped iterating the available descriptors ring buffer.
     */
    short lastAvailIdx;

    SplitVirtqueue(final MemoryMap memoryMap, final VirtqueueContext context) {
        this.memoryMap = memoryMap;
        this.context = context;
    }

    @Override
    void reset() {
        super.reset();
        lastAvailIdx = 0;
    }

    @Override
    public boolean hasNext() throws MemoryAccessException {
        return ready != 0 && lastAvailIdx != getAvailIdx();
    }

    @Override
    public DescriptorChain next() throws VirtIODeviceException, MemoryAccessException {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        return new DescriptorChainImpl(getAvailRing(lastAvailIdx++));
    }

    @Override
    void handleQueueNotification(final int queueIndex) throws VirtIODeviceException, MemoryAccessException {
        if (ready == 0 || !dispatchQueueNotifications) {
            return;
        }

        if (hasNext()) {
            context.dispatchQueueNotification(queueIndex);
        }
    }

    // The following methods provide access to a struct with the following layout:
    // struct virtq_desc {
    //     le64 addr;
    //     le32 len;
    //     le16 flags;
    //     le16 next;
    // };
    // virtq_desc is the structure of which we expect an array at the physical address the `desc` field points at.

    long getDescAddress(final int i) throws MemoryAccessException {
        return memoryMap.load(descIndexToAddress(i) + VIRTQ_DESC_ADDR, Sizes.SIZE_64_LOG2);
    }

    int getDescLength(final int i) throws MemoryAccessException {
        return (int) memoryMap.load(descIndexToAddress(i) + VIRTQ_DESC_LEN, Sizes.SIZE_32_LOG2);
    }

    short getDescFlags(final int i) throws MemoryAccessException {
        return (short) memoryMap.load(descIndexToAddress(i) + VIRTQ_DESC_FLAGS, Sizes.SIZE_16_LOG2);
    }

    short getDescNext(final int i) throws MemoryAccessException {
        return (short) memoryMap.load(descIndexToAddress(i) + VIRTQ_DESC_NEXT, Sizes.SIZE_16_LOG2);
    }

    long descIndexToAddress(final int i) {
        return desc + (long) i * VIRTQ_DESC_TABLE_STRIDE;
    }

    // The following methods provide access to a struct with the following layout:
    // struct virtq_avail {
    //     le16 flags;
    //     le16 idx;
    //     le16 ring[];
    //     /* Only if VIRTIO_F_EVENT_IDX: le16 used_event; */
    // };
    // virtq_avail is the structure expected at the physical address the `driver` field points at.

    short getAvailFlags() throws MemoryAccessException {
        return (short) memoryMap.load(driver + VIRTQ_AVAIL_FLAGS, Sizes.SIZE_16_LOG2);
    }

    short getAvailIdx() throws MemoryAccessException {
        return (short) memoryMap.load(driver + VIRTQ_AVAIL_IDX, Sizes.SIZE_16_LOG2);
    }

    short getAvailRing(final int i) throws MemoryAccessException {
        final long address = driver + VIRTQ_AVAIL_RING + (long) toWrappedRingIndex(i) * VIRTQ_AVAILABLE_RING_STRIDE;
        return (short) (memoryMap.load(address, Sizes.SIZE_16_LOG2) & 0xFFFFL);
    }

    short getAvailUsedEvent() throws MemoryAccessException {
        return (short) memoryMap.load(driver + VIRTQ_AVAIL_RING + (long) num * VIRTQ_AVAILABLE_RING_STRIDE, Sizes.SIZE_16_LOG2);
    }

    // The following methods provide access to a struct with the following layout:
    // struct virtq_used {
    //     le16 flags;
    //     le16 idx;
    //     struct virtq_used_elem ring[];
    //     /* Only if VIRTIO_F_EVENT_IDX: le16 avail_event; */
    // };
    // struct virtq_used_elem {
    //     le32 id;
    //     le32 len;
    // };
    // virtq_used is the structure expected at the physical address the `device` field points at.

    void setUsedFlags(final int value) throws MemoryAccessException {
        memoryMap.store(device + VIRTQ_USED_FLAGS, value, Sizes.SIZE_16_LOG2);
    }

    short getUsedIdx() throws MemoryAccessException {
        return (short) memoryMap.load(device + VIRTQ_USED_IDX, Sizes.SIZE_16_LOG2);
    }

    void setUsedIdx(final short value) throws MemoryAccessException {
        memoryMap.store(device + VIRTQ_USED_IDX, value, Sizes.SIZE_16_LOG2);
    }

    void setUsedRing(final int i, final int id, final int len) throws MemoryAccessException {
        final long address = device + VIRTQ_USED_RING + (long) toWrappedRingIndex(i) * VIRTQ_USED_RING_STRIDE;
        memoryMap.store(address + VIRTQ_USED_RING_ELEM_ID, id, Sizes.SIZE_32_LOG2);
        memoryMap.store(address + VIRTQ_USED_RING_ELEM_LEN, len, Sizes.SIZE_32_LOG2);
    }

    void setUsedAvailEvent(final int value) throws MemoryAccessException {
        memoryMap.store(device + VIRTQ_USED_RING + (long) num * VIRTQ_USED_RING_STRIDE, value, Sizes.SIZE_16_LOG2);
    }

    // Utility methods.

    int toWrappedRingIndex(final int index) {
        return index & (num - 1);
    }

    final class DescriptorChainImpl implements DescriptorChain {
        final short headDescIdx;
        final int readableByteCount;
        final int writableByteCount;
        int readByteCount;
        int writtenByteCount;
        boolean isUsed;

        short descIdx;
        long address;
        int length;
        int position;
        int chainLength = 1;

        DescriptorChainImpl(final short headDescIdx) throws VirtIODeviceException, MemoryAccessException {
            this.headDescIdx = headDescIdx;

            // Compute readable and writable byte counts.
            int readableByteCount = 0, writableByteCount = 0;
            short descIdx = headDescIdx;
            short descFlags = getDescFlags(descIdx);
            int descLength = getDescLength(descIdx);
            int chainLength = 1;

            // Readable bytes preceding writable bytes.
            boolean hasDesc = true;
            for (; ; ) {
                if ((descFlags & VIRTQ_DESC_F_WRITE) != 0) {
                    break;
                }

                readableByteCount += descLength;

                if ((descFlags & VIRTQ_DESC_F_NEXT) == 0) {
                    hasDesc = false;
                    break;
                }

                if (chainLength >= VIRTQ_MAX_CHAIN_LENGTH) {
                    // Chain too long. Possibly a loop.
                    context.error(); // Set error state immediately in case this gets caught by implementation code.
                    throw new VirtIODeviceException();
                }

                descIdx = getDescNext(descIdx);
                descFlags = getDescFlags(descIdx);
                descLength = getDescLength(descIdx);

                chainLength++;
            }

            if (hasDesc) {
                // Writable bytes, at this point we must no longer encounter any read-only descriptors.
                for (; ; ) {
                    if ((descFlags & VIRTQ_DESC_F_WRITE) == 0) {
                        // 2.7.17: read-only descriptors *must* precede write-only descriptors.
                        context.error(); // Set error state immediately in case this gets caught by implementation code.
                        throw new VirtIODeviceException();
                    }

                    writableByteCount += descLength;

                    if ((descFlags & VIRTQ_DESC_F_NEXT) == 0) {
                        break;
                    }

                    if (chainLength >= VIRTQ_MAX_CHAIN_LENGTH) {
                        // Chain too long. Possibly a loop.
                        context.error(); // Set error state immediately in case this gets caught by implementation code.
                        throw new VirtIODeviceException();
                    }

                    descIdx = getDescNext(descIdx);
                    descFlags = getDescFlags(descIdx);
                    descLength = getDescLength(descIdx);

                    chainLength++;
                }
            }

            this.readableByteCount = readableByteCount;
            this.writableByteCount = writableByteCount;

            setDescriptor(headDescIdx);
        }

        @Override
        public void use() throws MemoryAccessException {
            if (isUsed) {
                return;
            }
            isUsed = true;

            // 2.6.8.2: set len prior to updating used idx.
            short index = getUsedIdx();
            setUsedRing(index, headDescIdx, writtenByteCount);
            index++; // Overflow by design.
            setUsedIdx(index);

            // 2.6.7: Used Buffer Notification Suppression
            final boolean sendNotification;
            if ((context.getNegotiatedFeatures() & AbstractVirtIODevice.VIRTIO_F_RING_EVENT_IDX) == 0) {
                final int flags = getAvailFlags();
                sendNotification = flags == 0;
            } else {
                short usedEvent = getAvailUsedEvent();
                usedEvent++;
                sendNotification = index == usedEvent;
            }

            if (sendNotification) {
                context.raiseUsedBufferInterrupt();
            }
        }

        @Override
        public int readableBytes() {
            if (isUsed) return 0;
            assert readByteCount <= readableByteCount;
            return readableByteCount - readByteCount;
        }

        @Override
        public int writableBytes() {
            if (isUsed) return 0;
            assert writtenByteCount <= writableByteCount;
            return writableByteCount - writtenByteCount;
        }

        @Override
        public void skip(int count) throws VirtIODeviceException, MemoryAccessException {
            if (isUsed) {
                throw new IllegalStateException();
            }
            if (count > readableBytes() + writableBytes()) {
                throw new IndexOutOfBoundsException();
            }

            while (count > 0) {
                assert position < length;
                final int remaining = length - position;
                final int skip = Math.min(count, remaining);
                count -= skip;
                if (readableBytes() > 0) {
                    assert readableBytes() <= skip;
                    readByteCount += skip;
                } else {
                    assert writableBytes() <= skip;
                    writtenByteCount += skip;
                }
                position += skip;
                if (position >= length) {
                    nextDescriptor();
                }
            }
        }

        @Override
        public byte get() throws VirtIODeviceException, MemoryAccessException {
            if (isUsed) {
                throw new IllegalStateException();
            }
            if (readableBytes() <= 0) {
                throw new IndexOutOfBoundsException();
            }

            assert position < length;
            final byte value = (byte) memoryMap.load(address + position, Sizes.SIZE_8_LOG2);
            readByteCount++;
            position++;
            if (position >= length) {
                nextDescriptor();
            }

            return value;
        }

        @Override
        public void get(final ByteBuffer dst) throws VirtIODeviceException, MemoryAccessException {
            if (isUsed) {
                throw new IllegalStateException();
            }
            if (dst.remaining() > readableBytes()) {
                throw new IndexOutOfBoundsException();
            }

            final int limit = dst.limit();
            while (dst.position() < limit) {
                assert position < length;
                final int count = Math.min(length - position, limit - dst.position());
                dst.limit(dst.position() + count);
                MemoryMaps.load(memoryMap, address + position, dst);
                readByteCount += count;
                position += count;
                if (position >= length) {
                    nextDescriptor();
                }
            }

            assert dst.position() == dst.limit();
        }

        @Override
        public void put(final byte value) throws VirtIODeviceException, MemoryAccessException {
            if (isUsed) {
                throw new IllegalStateException();
            }
            if (readableBytes() > 0) {
                throw new IllegalStateException();
            }
            if (writableBytes() <= 0) {
                throw new IndexOutOfBoundsException();
            }

            assert position < length;
            memoryMap.store(address + position, value, Sizes.SIZE_8_LOG2);
            writtenByteCount++;
            position++;
            if (position >= length) {
                nextDescriptor();
            }
        }

        @Override
        public void put(final ByteBuffer src) throws VirtIODeviceException, MemoryAccessException {
            if (isUsed) {
                throw new IllegalStateException();
            }
            if (readableBytes() > 0) {
                throw new IllegalStateException();
            }
            if (src.remaining() > writableBytes()) {
                throw new IndexOutOfBoundsException();
            }

            final int limit = src.limit();
            while (src.position() < limit) {
                assert position < length;
                final int count = Math.min(length - position, limit - src.position());
                src.limit(src.position() + count);
                MemoryMaps.store(memoryMap, address + position, src);
                writtenByteCount += count;
                position += count;
                if (position >= length) {
                    nextDescriptor();
                }
            }

            assert src.limit() == limit;
            assert src.position() == src.limit();
        }

        void setDescriptor(final short descIdx) throws MemoryAccessException {
            this.descIdx = descIdx;
            address = getDescAddress(descIdx);
            length = getDescLength(descIdx);
            position = 0;
        }

        void nextDescriptor() throws VirtIODeviceException, MemoryAccessException {
            if (position < length) {
                throw new IllegalStateException("Current descriptor must be used up before advancing to the next.");
            }

            if ((getDescFlags(descIdx) & VIRTQ_DESC_F_NEXT) == 0) {
                return; // End of chain reached, nothing left to do.
            }

            // We checked this when computing the length, but we have to prepare for the worst
            // since the driver may be malicious and change our descriptors while we're iterating.
            if (chainLength >= VIRTQ_MAX_CHAIN_LENGTH) {
                context.error(); // Set error state immediately in case this gets caught by implementation code.
                throw new VirtIODeviceException();
            } else {
                setDescriptor(getDescNext(descIdx));
                chainLength++;
            }

            // Again, we checked this before, but we don't trust the driver. If we already had a
            // write-only buffer then we must not see any read-only buffers.
            if ((getDescFlags(descIdx) & VIRTQ_DESC_F_WRITE) == 0 && writtenByteCount > 0) {
                context.error(); // Set error state immediately in case this gets caught by implementation code.
                throw new VirtIODeviceException();
            }
        }
    }
}
