package li.cil.sedna.device.virtio.gpu;

import li.cil.sedna.api.memory.MemoryAccessException;

import java.nio.ByteBuffer;

public class VirtIOGPUResource {
    private final int format;
    private final int width;
    private final int height;

    private ByteBuffer backing;

    private BackingPages pages;

	public VirtIOGPUResource(int format, int width, int height) {
		this.format = format;
		this.width = width;
		this.height = height;

		// TODO(Matrix89): bbp(4) should be hard coded, extract it from the format
		this.backing = ByteBuffer.allocate(width * height * 4);
	}

	public void transferToHost(VirtIOGPURect rect, long offset) throws MemoryAccessException {
		// depending on whether we want to copy the entirety of the backing,
		// we run a expensive loop or do a memcpy
		// https://github.com/qemu/qemu/blob/469e72ab7dbbd7ff4ee601e5ea7c29545d46593b/hw/display/virtio-gpu.c#L432
		if (offset != 0 ||
				rect.getX() != 0 ||
				rect.getY() != 0 ||
				rect.getWidth() != width
		) {
			for (int h = 0; h < rect.getHeight(); h++) {
				// TODO(Matrix89): in we should use image stride
				long srcOffset = offset + (long) 3200 * h;
				// TODO(Matrix89): bbp(4) should be hard coded, extract it from the format
				int dstOffset = (rect.getY() + h) * 3200 + (rect.getX() * 4);
				backing.position(dstOffset);
				backing.put(pages.getAsByteBuffer((int) srcOffset, rect.getWidth() * 4));
			}
			backing.rewind();
		} else {
			// is this even safe?
			backing = pages.getAsByteBuffer().duplicate();
		}
	}

	public int getFormat() {
		return format;
	}

	public int getWidth() {
		return width;
	}

	public int getHeight() {
		return height;
	}

	public ByteBuffer getBacking() {
		return backing;
	}

    public void attachBacking(BackingPages pages) {
		this.pages = pages;
    }

    public void detachBacking() {
		this.pages = null;
    }

    public BackingPages getPages() {
		return pages;
    }
}
