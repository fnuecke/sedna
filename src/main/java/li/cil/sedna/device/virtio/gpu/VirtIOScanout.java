package li.cil.sedna.device.virtio.gpu;

import java.nio.ByteBuffer;

public class VirtIOScanout {
	private int x;
	private int y;
	private int width;
	private int height;

	//TODO:
	// - private int enabled;
	// - private int flags;

	private GPUViewer viewer;

	public VirtIOScanout(final int x, final int y, final int width, final int height, final GPUViewer viewer) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		this.viewer = viewer;
	}

	public void appendToByteByteBuffer(ByteBuffer buffer) {
		buffer.put(getRect().asByteBuffer());
		// enabled
		buffer.putInt(1);
		// flags
		buffer.putInt(0);
	}

	public void flush(final long address, final int length) {
		viewer.view(address, length);
	}

	public int getX() {
		return x;
	}

	public void setX(int x) {
		this.x = x;
	}

	public int getY() {
		return y;
	}

	public void setY(int y) {
		this.y = y;
	}

	public int getWidth() {
		return width;
	}

	public void setWidth(int width) {
		this.width = width;
	}

	public int getHeight() {
		return height;
	}

	public void setHeight(int height) {
		this.height = height;
	}

	/**
	 * @return how many bytes are read/written to buffers
	 */
	public static int getLengthInBytes() {
		return 24;
	}

	private VirtIOGPURect getRect() {
		return new VirtIOGPURect(x, y, width, height);
	}
}
