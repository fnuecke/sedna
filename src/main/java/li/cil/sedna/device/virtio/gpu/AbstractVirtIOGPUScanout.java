package li.cil.sedna.device.virtio.gpu;

import java.nio.ByteBuffer;

public abstract class AbstractVirtIOGPUScanout {
	private int x;
	private int y;
	private int width;
	private int height;

	protected VirtIOGPUResource resource;

	public AbstractVirtIOGPUScanout(final int x, final int y, final int width, final int height) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
	}

	public abstract void flush(VirtIOGPURect rect);

	public void setResource(VirtIOGPUResource resource) {
		this.resource = resource;
	}

	public VirtIOGPUResource getResource() {
		return this.resource;
	}

	public void appendToByteByteBuffer(ByteBuffer buffer) {
		buffer.put(getRect().asByteBuffer());
		// enabled
		buffer.putInt(1);
		// flags
		buffer.putInt(0);
	}

	private VirtIOGPURect getRect() {
		return new VirtIOGPURect(x, y, width, height);
	}

	/**
	 * @return how many bytes are read/written to buffers
	 */
	public static int getLengthInBytes() {
		return 24;
	}
}
