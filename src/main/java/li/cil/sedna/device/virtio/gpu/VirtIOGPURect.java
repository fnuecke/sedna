package li.cil.sedna.device.virtio.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class VirtIOGPURect {
	private int x = 0;
	private int y = 0;
	private int width = 0;
	private int height = 0;

	public VirtIOGPURect(int x, int y, int width, int height) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
	}

	public static VirtIOGPURect fromByteBuffer(ByteBuffer byteBuffer) {
		return new VirtIOGPURect(
				byteBuffer.getInt(),
				byteBuffer.getInt(),
				byteBuffer.getInt(),
				byteBuffer.getInt()
		);
	}

	public ByteBuffer asByteBuffer() {
		ByteBuffer buffer = ByteBuffer.allocate(16);
		buffer.order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(x);
		buffer.putInt(y);
		buffer.putInt(width);
		buffer.putInt(height);
		buffer.flip();
		return buffer;
	}

	public int getX() {
		return x;
	}

	public int getY() {
		return y;
	}

	public int getWidth() {
		return width;
	}

	public int getHeight() {
		return height;
	}
}
