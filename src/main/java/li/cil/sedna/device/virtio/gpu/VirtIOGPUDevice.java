package li.cil.sedna.device.virtio.gpu;

import li.cil.sedna.api.device.Steppable;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.device.virtio.*;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;

class VirtIOGPUCTRLHeader {
	private int type = 0;
	private int flags = 0;
	private long fenceId = 0;
	private int ctxId = 0;
	private int padding = 0;

	public VirtIOGPUCTRLHeader(int type, int flags, long fenceId, int ctxId, int padding) {
		this.type = type;
		this.flags = flags;
		this.fenceId = fenceId;
		this.ctxId = ctxId;
		this.padding = padding;
	}

    public static VirtIOGPUCTRLHeader readFromChain(final DescriptorChain chain) throws MemoryAccessException, VirtIODeviceException {
		// TODO(Matrix89): Add utility methods on DescriptorChain to read primitives
	    ByteBuffer buffer = ByteBuffer.allocate(24);
	    buffer.order(ByteOrder.LITTLE_ENDIAN);
	    chain.get(buffer);
	    buffer.flip();

		return new VirtIOGPUCTRLHeader(
				buffer.getInt(),
				buffer.getInt(),
				buffer.getLong(),
				buffer.getInt(),
				buffer.getInt()
		);
    }

	public ByteBuffer asByteBuffer() {
		ByteBuffer buffer = ByteBuffer.allocate(24);
		buffer.order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(type);
		buffer.putInt(flags);
		buffer.putLong(fenceId);
		buffer.putInt(ctxId);
		buffer.putInt(padding);
		buffer.flip();
		return buffer;
    }

	public int getType() {
		return type;
	}

	public int getFlags() {
		return flags;
	}

	public long getFenceId() {
		return fenceId;
	}

	public int getCtxId() {
		return ctxId;
	}

	public int getPadding() {
		return padding;
	}

	public static int getLengthInBytes() {
		return 24;
	}
}

class VirtIOGPURect {
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

class VirtIOGPUResponseNoData extends VirtIOGPUCTRLHeader {
	private static final int VIRTIO_GPU_RESP_OK_NODATA = 0x1100;
	public VirtIOGPUResponseNoData() {
		super(VIRTIO_GPU_RESP_OK_NODATA, 0, 0, 0, 0);
	}
}

public class VirtIOGPUDevice extends AbstractVirtIODevice implements Steppable {

	private static final int MAX_SCANOUTS = 16;

	/* 2d commands */
	private static final int VIRTIO_GPU_CMD_GET_DISPLAY_INFO = 0x0100;
	private static final int VIRTIO_GPU_CMD_RESOURCE_CREATE_2D = 0x0101;
	private static final int VIRTIO_GPU_CMD_RESOURCE_UNREF = 0x0102;
	private static final int VIRTIO_GPU_CMD_SET_SCANOUT = 0x0103;
	private static final int VIRTIO_GPU_CMD_RESOURCE_FLUSH = 0x0104;
	private static final int VIRTIO_GPU_CMD_TRANSFER_TO_HOST_2D = 0x0105;
	private static final int VIRTIO_GPU_CMD_RESOURCE_ATTACH_BACKING = 0x0106;
	private static final int VIRTIO_GPU_CMD_RESOURCE_DETACH_BACKING = 0x0107;
	private static final int VIRTIO_GPU_CMD_GET_CAPSET_INFO = 0x0108;
	private static final int VIRTIO_GPU_CMD_GET_CAPSET = 0x0109;
	private static final int VIRTIO_GPU_CMD_GET_EDID = 0x010a;


	/* 3d commands */
	private static final int VIRTIO_GPU_CMD_CTX_CREATE = 0x0200;
	private static final int VIRTIO_GPU_CMD_CTX_DESTROY = 0x0201;
	private static final int VIRTIO_GPU_CMD_CTX_ATTACH_RESOURCE = 0x0202;
	private static final int VIRTIO_GPU_CMD_CTX_DETACH_RESOURCE = 0x0203;
	private static final int VIRTIO_GPU_CMD_RESOURCE_CREATE_3D = 0x0204;
	private static final int VIRTIO_GPU_CMD_TRANSFER_TO_HOST_3D = 0x0205;
	private static final int VIRTIO_GPU_CMD_TRANSFER_FROM_HOST_3D = 0x0206;
	private static final int VIRTIO_GPU_CMD_SUBMIT_3D = 0x0207;

	/* cursor commands */
	private static final int VIRTIO_GPU_CMD_UPDATE_CURSOR = 0x0300;
	private static final int VIRTIO_GPU_CMD_MOVE_CURSOR = 0x0301;

	/* success responses */
	private static final int VIRTIO_GPU_RESP_OK_NODATA = 0x1100;
	private static final int VIRTIO_GPU_RESP_OK_DISPLAY_INFO = 0x1101;
	private static final int VIRTIO_GPU_RESP_OK_CAPSET_INFO = 0x1102;
	private static final int VIRTIO_GPU_RESP_OK_CAPSET = 0x1103;
	private static final int VIRTIO_GPU_RESP_OK_EDID = 0x1104;

	/* error responses */
	private static final int VIRTIO_GPU_RESP_ERR_UNSPEC = 0x1200;
	private static final int VIRTIO_GPU_RESP_ERR_OUT_OF_MEMORY = 0x1201;
	private static final int VIRTIO_GPU_RESP_ERR_INVALID_SCANOUT_ID = 0x1202;
	private static final int VIRTIO_GPU_RESP_ERR_INVALID_RESOURCE_ID = 0x1203;
	private static final int VIRTIO_GPU_RESP_ERR_INVALID_CONTEXT_ID = 0x1204;
	private static final int VIRTIO_GPU_RESP_ERR_INVALID_PARAMETER = 0x1205;

	private static final int VIRTIO_GPU_CFG_EVENTS_READ_OFFSET = 0;
	private static final int VIRTIO_GPU_CFG_EVENTS_CLEAR_OFFSET = 4;
	private static final int VIRTIO_GPU_CFG_NUM_SCANOUTS_OFFSET = 8;

	private static final long VIRTIO_GPU_F_VIRGL = 0;
	private static final long VIRTIO_GPU_F_EDID = 1;

	private static final int CONTROL_QUEUE = 0;
	private static final int CURSOR_QUEUE = 1;

	private ArrayList<VirtIOScanout> scanouts = new ArrayList<>();
	private HashMap<Integer, VirtIOGPUResource> resources = new HashMap<>();

	private VirtIOGPUDevice(MemoryMap memoryMap) {
		super(memoryMap, VirtIODeviceSpec
				.builder(VirtIODeviceType.VIRTIO_DEVICE_ID_GPU_DEVICE)
				.features(VIRTIO_GPU_F_EDID)
				.queueCount(2)
				.configSpaceSize(24)
				.build());
	}

	private void cmdResponseNoData(final DescriptorChain chain) throws MemoryAccessException, VirtIODeviceException {
		chain.put(new VirtIOGPUCTRLHeader(VIRTIO_GPU_RESP_OK_NODATA, 0, 0, 0, 0).asByteBuffer());
	}

	private void cmdGetDisplayInfo(final DescriptorChain chain) throws MemoryAccessException, VirtIODeviceException {
		ByteBuffer response = ByteBuffer.allocate(
                VirtIOGPUCTRLHeader.getLengthInBytes() +
				// Apparently the spec expect there to be MAX_SCANOUTS number of scanouts,
				// instead of the real number of scanouts
				MAX_SCANOUTS * VirtIOScanout.getLengthInBytes()
		);
		response.order(ByteOrder.LITTLE_ENDIAN);

        response.put(new VirtIOGPUCTRLHeader(VIRTIO_GPU_RESP_OK_DISPLAY_INFO, 0, 0, 0, 0).asByteBuffer());
		scanouts.forEach(scanout -> scanout.appendToByteByteBuffer(response));

		response.flip();
		chain.put(response);
	}

	private void cmdResourceCreate2d(final DescriptorChain chain) throws MemoryAccessException, VirtIODeviceException {
		ByteBuffer requestData = ByteBuffer.allocate(chain.readableBytes());
		requestData.order(ByteOrder.LITTLE_ENDIAN);
        chain.get(requestData);
        requestData.flip();

		final int resourceId = requestData.getInt();
		final int format = requestData.getInt();
		final int width = requestData.getInt();
		final int height = requestData.getInt();
		resources.put(resourceId, new VirtIOGPUResource(format, width, height));
        cmdResponseNoData(chain);
	}

	private void cmdResourceAttachBacking(final DescriptorChain chain) throws MemoryAccessException, VirtIODeviceException {
		ByteBuffer requestData = ByteBuffer.allocate(chain.readableBytes());
		requestData.order(ByteOrder.LITTLE_ENDIAN);
		chain.get(requestData);
		requestData.flip();

		final int resourceId = requestData.getInt();
		final int entriesCount = requestData.getInt();
		VirtIOGPUResource.Page[] pages = new VirtIOGPUResource.Page[entriesCount];
		for (int i = 0; i < entriesCount; i++) {
			pages[i] = new VirtIOGPUResource.Page(requestData.getLong(), requestData.getInt());

			// skip padding
			requestData.getInt();
		}

		resources.get(resourceId).attachBacking(pages);

		cmdResponseNoData(chain);
	}

	private void cmdSetScanout(final DescriptorChain chain) throws MemoryAccessException, VirtIODeviceException {
		ByteBuffer requestData = ByteBuffer.allocate(chain.readableBytes());
		requestData.order(ByteOrder.LITTLE_ENDIAN);
		chain.get(requestData);
		requestData.flip();

		VirtIOGPURect rect = VirtIOGPURect.fromByteBuffer(requestData);
		final int scanoutId = requestData.getInt();
		final int resourceId = requestData.getInt();

		final VirtIOScanout scanout = scanouts.get(scanoutId);
		VirtIOGPUResource resource = resources.get(resourceId);
		if (resource != null) {
			// TODO: Investigate null
			resources.get(resourceId).setScanout(scanout, rect);
		}

		cmdResponseNoData(chain);
	}

	private void cmdResourceFlush(final DescriptorChain chain) throws MemoryAccessException, VirtIODeviceException {
		ByteBuffer requestData = ByteBuffer.allocate(chain.readableBytes());
		requestData.order(ByteOrder.LITTLE_ENDIAN);
		chain.get(requestData);
		requestData.flip();

		VirtIOGPURect rect = VirtIOGPURect.fromByteBuffer(requestData);
		final int resourceId = requestData.getInt();
		final int padding = requestData.getInt();

		resources.get(resourceId).flush();

		cmdResponseNoData(chain);
	}

	private void processCtrlQueue() throws MemoryAccessException, VirtIODeviceException {
		final VirtqueueIterator queue = getQueueIterator(CONTROL_QUEUE);
		if (queue == null) {
			return;
		}

		if (!queue.hasNext()) {
			return;
		}

		while (queue.hasNext()) {
			final DescriptorChain chain = queue.next();
			final VirtIOGPUCTRLHeader header = VirtIOGPUCTRLHeader.readFromChain(chain);

			switch (header.getType()) {
				case VIRTIO_GPU_CMD_GET_DISPLAY_INFO: {
					cmdGetDisplayInfo(chain);
					break;
				}
				case VIRTIO_GPU_CMD_RESOURCE_CREATE_2D: {
                    cmdResourceCreate2d(chain);
					break;
				}
				case VIRTIO_GPU_CMD_RESOURCE_UNREF:
					System.out.println("unref");
					break;
				case VIRTIO_GPU_CMD_SET_SCANOUT:
					cmdSetScanout(chain);
					break;
				case VIRTIO_GPU_CMD_RESOURCE_FLUSH: {
					cmdResourceFlush(chain);
					//ByteBuffer response = new VirtIOGPUResponseNoData().asByteBuffer();
					//response.flip();
					//chain.put(response);
					//System.out.println("flush");
					break;
				}
				case VIRTIO_GPU_CMD_TRANSFER_TO_HOST_2D: {
					//VirtIOGPURect rect = VirtIOGPURect.fromByteBuffer(buffer);
					//buffer.getLong();
					//buffer.getInt();
					//buffer.getInt();
//
					//ByteBuffer response = new VirtIOGPUResponseNoData().asByteBuffer();
					//response.flip();
					//chain.put(response);

					//System.out.printf("transfer to host 2d: %d, %d, %d, %d\n", rect.getX(), rect.getY(), rect.getWidth(), rect.getHeight());
					//System.out.println("transfer to host 2d");
					break;
				}
				case VIRTIO_GPU_CMD_CTX_CREATE:
				case VIRTIO_GPU_CMD_CTX_ATTACH_RESOURCE:
					// I think we might've found a error in the kernel,
					// since according to the spec those commands,
					// are only supported when the device returns VIRTIO_GPU_F_VIRGL flag
					break;
				case VIRTIO_GPU_CMD_RESOURCE_ATTACH_BACKING: {
					cmdResourceAttachBacking(chain);
					break;
				}
				default:
					System.err.printf("VirtIO request 0x%x on supported\n", header.getType());
					throw new NotImplementedException();
			}

			chain.use();
		}
	}

	private void processCursorQueue() {
	}

	@Override
	public void step(int steps) {

	}

	@Override
	protected void handleQueueNotification(int queueIndex) throws VirtIODeviceException, MemoryAccessException {
		if (queueIndex == CONTROL_QUEUE) {
			processCtrlQueue();
		}
	}

	@Override
	public boolean supportsFetch() {
		return false;
	}

	@Override
	public Object getIdentity() {
		return this;
	}

	@Override
	protected void initializeConfig() {
		setConfigValue(VIRTIO_GPU_CFG_NUM_SCANOUTS_OFFSET, 1);
	}

	@Override
	protected void handleFeaturesNegotiated() {
		setQueueNotifications(CONTROL_QUEUE, true);
	}

	public HashMap<Integer, VirtIOGPUResource> getResources() {
		return resources;
	}

	public void setScanouts(ArrayList<VirtIOScanout> scanouts) {
		this.scanouts = scanouts;
	}

	static class Builder {
		private ArrayList<VirtIOScanout> scanouts;
		private MemoryMap memoryMap;

        public Builder(MemoryMap memoryMap) {
        	this.memoryMap = memoryMap;
        }

		public Builder setScanouts(ArrayList<VirtIOScanout> scanouts) {
			this.scanouts = scanouts;
			return this;
		}

		public VirtIOGPUDevice build() {
            VirtIOGPUDevice device = new VirtIOGPUDevice(this.memoryMap);
            if (scanouts != null) {
	            device.setScanouts(scanouts);
            }
            return device;
		}
	}
}
