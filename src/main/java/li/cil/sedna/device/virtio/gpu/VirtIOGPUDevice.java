package li.cil.sedna.device.virtio.gpu;

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

	public static int getLengthInBytes() {
		return 24;
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
}

public final class VirtIOGPUDevice extends AbstractVirtIODevice {

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

	/* cursor commands */
	private static final int VIRTIO_GPU_CMD_UPDATE_CURSOR = 0x0300;
	private static final int VIRTIO_GPU_CMD_MOVE_CURSOR = 0x0301;

	/* success responses */
	private static final int VIRTIO_GPU_RESP_OK_NODATA = 0x1100;
	private static final int VIRTIO_GPU_RESP_OK_DISPLAY_INFO = 0x1101;

	/* error responses */
	private static final int VIRTIO_GPU_RESP_ERR_UNSPEC = 0x1200;
	private static final int VIRTIO_GPU_RESP_ERR_OUT_OF_MEMORY = 0x1201;
	private static final int VIRTIO_GPU_RESP_ERR_INVALID_SCANOUT_ID = 0x1202;
	private static final int VIRTIO_GPU_RESP_ERR_INVALID_RESOURCE_ID = 0x1203;

	private static final int VIRTIO_GPU_CFG_EVENTS_READ_OFFSET = 0;
	private static final int VIRTIO_GPU_CFG_EVENTS_CLEAR_OFFSET = 4;
	private static final int VIRTIO_GPU_CFG_NUM_SCANOUTS_OFFSET = 8;

	private static final int CONTROL_QUEUE = 0;
	private static final int CURSOR_QUEUE = 1;

	private static final int VIRTIO_GPU_FLAG_FENCE = 1;
	private final MemoryMap memoryMap;
	private ArrayList<AbstractVirtIOGPUScanout> scanouts = new ArrayList<>();
	private HashMap<Integer, VirtIOGPUResource> resources = new HashMap<>();

	private VirtIOGPUDevice(MemoryMap memoryMap) {
		super(memoryMap, VirtIODeviceSpec
				.builder(VirtIODeviceType.VIRTIO_DEVICE_ID_GPU_DEVICE)
				.queueCount(2)
				.configSpaceSize(24)
				.build());

		this.memoryMap = memoryMap;
	}

	private void cmdResponseNoData(final VirtIOGPUCTRLHeader request, final DescriptorChain chain) throws MemoryAccessException, VirtIODeviceException {
		if ((request.getFlags() & VIRTIO_GPU_FLAG_FENCE) != 0) {
			chain.put(new VirtIOGPUCTRLHeader(VIRTIO_GPU_RESP_OK_NODATA, VIRTIO_GPU_FLAG_FENCE, request.getFenceId(), 0, 0).asByteBuffer());
			return;
		}

		chain.put(new VirtIOGPUCTRLHeader(VIRTIO_GPU_RESP_OK_NODATA, 0, 0, 0, 0).asByteBuffer());
	}

	private void cmdGetDisplayInfo(final VirtIOGPUCTRLHeader request, final DescriptorChain chain) throws MemoryAccessException, VirtIODeviceException {
		ByteBuffer response = ByteBuffer.allocate(
				VirtIOGPUCTRLHeader.getLengthInBytes() +
						// Apparently the spec expect there to be MAX_SCANOUTS number of scanouts,
						// instead of the real number of scanouts
						MAX_SCANOUTS * AbstractVirtIOGPUScanout.getLengthInBytes()
		);
		response.order(ByteOrder.LITTLE_ENDIAN);

		response.put(new VirtIOGPUCTRLHeader(VIRTIO_GPU_RESP_OK_DISPLAY_INFO, 0, 0, 0, 0).asByteBuffer());
		scanouts.forEach(scanout -> scanout.appendToByteByteBuffer(response));

		response.flip();
		chain.put(response);
	}

	private void cmdResourceCreate2d(final VirtIOGPUCTRLHeader request, final DescriptorChain chain) throws MemoryAccessException, VirtIODeviceException {
		ByteBuffer requestData = ByteBuffer.allocate(chain.readableBytes());
		requestData.order(ByteOrder.LITTLE_ENDIAN);
		chain.get(requestData);
		requestData.flip();

		final int resourceId = requestData.getInt();
		final int format = requestData.getInt();
		final int width = requestData.getInt();
		final int height = requestData.getInt();
		System.out.printf("creating resource: %d(%dx%d)\n", resourceId, width, height);
		resources.put(resourceId, new VirtIOGPUResource(format, width, height));
		cmdResponseNoData(request, chain);
	}

	private void cmdResourceUnref(final VirtIOGPUCTRLHeader request, final DescriptorChain chain) throws MemoryAccessException, VirtIODeviceException {
		ByteBuffer requestData = ByteBuffer.allocate(chain.readableBytes());
		requestData.order(ByteOrder.LITTLE_ENDIAN);
		chain.get(requestData);
		requestData.flip();

		final int resourceId = requestData.getInt();
		resources.remove(resourceId);
		cmdResponseNoData(request, chain);
	}

	private void cmdResourceAttachBacking(final VirtIOGPUCTRLHeader request, final DescriptorChain chain) throws MemoryAccessException, VirtIODeviceException {
		ByteBuffer requestData = ByteBuffer.allocate(chain.readableBytes());
		requestData.order(ByteOrder.LITTLE_ENDIAN);
		chain.get(requestData);
		requestData.flip();

		final int resourceId = requestData.getInt();
		final int entriesCount = requestData.getInt();
		BackingPages.Builder backingPagesBuilder = new BackingPages.Builder(memoryMap);
		for (int i = 0; i < entriesCount; i++) {
			backingPagesBuilder.addPage(requestData.getLong(), requestData.getInt());

			// skip padding
			requestData.getInt();
		}

		resources.get(resourceId).attachBacking(backingPagesBuilder.build());

		cmdResponseNoData(request, chain);
	}

	private void cmdResourceDetachBacking(final VirtIOGPUCTRLHeader request, final DescriptorChain chain) throws MemoryAccessException, VirtIODeviceException {
		ByteBuffer requestData = ByteBuffer.allocate(chain.readableBytes());
		requestData.order(ByteOrder.LITTLE_ENDIAN);
		chain.get(requestData);
		requestData.flip();

		final int resourceId = requestData.getInt();

		// read padding
		requestData.getInt();

		resources.get(resourceId).detachBacking();

		cmdResponseNoData(request, chain);
	}

	private void cmdTransferToHost2d(final VirtIOGPUCTRLHeader request, final DescriptorChain chain) throws MemoryAccessException, VirtIODeviceException {
		ByteBuffer requestData = ByteBuffer.allocate(chain.readableBytes());
		requestData.order(ByteOrder.LITTLE_ENDIAN);
		chain.get(requestData);
		requestData.flip();

		final VirtIOGPURect rect = VirtIOGPURect.fromByteBuffer(requestData);
		final long offset = requestData.getLong();
		final int resourceId = requestData.getInt();
		// read padding
		requestData.getInt();

		resources.get(resourceId).transferToHost(rect, offset);

		cmdResponseNoData(request, chain);
	}

	private void cmdSetScanout(final VirtIOGPUCTRLHeader request, final DescriptorChain chain) throws MemoryAccessException, VirtIODeviceException {
		ByteBuffer requestData = ByteBuffer.allocate(chain.readableBytes());
		requestData.order(ByteOrder.LITTLE_ENDIAN);
		chain.get(requestData);
		requestData.flip();

		VirtIOGPURect rect = VirtIOGPURect.fromByteBuffer(requestData);
		final int scanoutId = requestData.getInt();
		final int resourceId = requestData.getInt();

		final AbstractVirtIOGPUScanout scanout = scanouts.get(scanoutId);
		VirtIOGPUResource resource = resources.get(resourceId);
		if (resource != null) {
			// TODO: Investigate null
			scanout.setResource(resource);
		}

		cmdResponseNoData(request, chain);
	}

	private void cmdResourceFlush(final VirtIOGPUCTRLHeader request, final DescriptorChain chain) throws MemoryAccessException, VirtIODeviceException {
		ByteBuffer requestData = ByteBuffer.allocate(chain.readableBytes());
		requestData.order(ByteOrder.LITTLE_ENDIAN);
		chain.get(requestData);
		requestData.flip();

		VirtIOGPURect rect = VirtIOGPURect.fromByteBuffer(requestData);
		final int resourceId = requestData.getInt();

		// read padding
		requestData.getInt();

		scanouts.forEach(scanout -> {
			if (scanout.getResource() == resources.get(resourceId)) {
				scanout.flush(rect);
			}
		});


		cmdResponseNoData(request, chain);
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
					cmdGetDisplayInfo(header, chain);
					break;
				}
				case VIRTIO_GPU_CMD_RESOURCE_CREATE_2D: {
					cmdResourceCreate2d(header, chain);
					break;
				}
				case VIRTIO_GPU_CMD_RESOURCE_UNREF:
					cmdResourceUnref(header, chain);
					break;
				case VIRTIO_GPU_CMD_SET_SCANOUT:
					cmdSetScanout(header, chain);
					break;
				case VIRTIO_GPU_CMD_RESOURCE_FLUSH: {
					cmdResourceFlush(header, chain);
					break;
				}
				case VIRTIO_GPU_CMD_TRANSFER_TO_HOST_2D: {
					cmdTransferToHost2d(header, chain);
					break;
				}
				case VIRTIO_GPU_CMD_RESOURCE_ATTACH_BACKING: {
					cmdResourceAttachBacking(header, chain);
					break;
				}
				case VIRTIO_GPU_CMD_RESOURCE_DETACH_BACKING: {
					cmdResourceDetachBacking(header, chain);
					break;
				}
				default:
					System.err.printf("VirtIO request 0x%x on supported\n", header.getType());
					throw new NotImplementedException();
			}

			chain.use();
		}
	}

	private void processCursorQueue() throws MemoryAccessException, VirtIODeviceException {
		System.out.println("processing cursor queue");
		final VirtqueueIterator queue = getQueueIterator(CURSOR_QUEUE);
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
				case VIRTIO_GPU_CMD_UPDATE_CURSOR:
					System.out.println("update cursor");
					break;
				case VIRTIO_GPU_CMD_MOVE_CURSOR:
					System.out.println("move cursor");
					break;
				default:
					System.err.printf("VirtIO request 0x%x on supported\n", header.getType());
					throw new NotImplementedException();
			}
			chain.use();
		}
	}

	@Override
	protected void handleQueueNotification(int queueIndex) throws VirtIODeviceException, MemoryAccessException {
		switch (queueIndex) {
			case CONTROL_QUEUE:
				processCtrlQueue();
				break;
			case CURSOR_QUEUE:
				processCursorQueue();
				break;
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
		setConfigValue(VIRTIO_GPU_CFG_NUM_SCANOUTS_OFFSET, scanouts.size());
	}

	@Override
	protected void handleFeaturesNegotiated() {
		setQueueNotifications(CONTROL_QUEUE, true);
		setQueueNotifications(CURSOR_QUEUE, true);
	}

	public HashMap<Integer, VirtIOGPUResource> getResources() {
		return resources;
	}

	public void setScanouts(ArrayList<AbstractVirtIOGPUScanout> scanouts) {
		this.scanouts = scanouts;
	}

	public static class Builder {
		private ArrayList<AbstractVirtIOGPUScanout> scanouts;
		private MemoryMap memoryMap;

		public Builder(MemoryMap memoryMap) {
			this.memoryMap = memoryMap;
		}

		public Builder setScanouts(ArrayList<AbstractVirtIOGPUScanout> scanouts) {
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
