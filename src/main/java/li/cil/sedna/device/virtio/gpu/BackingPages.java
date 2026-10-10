package li.cil.sedna.device.virtio.gpu;

import javafx.util.Pair;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.memory.MemoryMaps;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public final class BackingPages {
	private final List<Pair<Long, Integer>> pages;
	private final MemoryMap memoryMap;
	/**
	 * @param pages list of address -> length mappings
	 */
	public BackingPages(MemoryMap memoryMap, List<Pair<Long, Integer>> pages) {
		this.memoryMap = memoryMap;
		this.pages = pages;
	}

	/**
	 * Joins the pages as one contiguous buffer
	 * @throws MemoryAccessException
	 */
	public ByteBuffer getAsByteBuffer() throws MemoryAccessException {
		int length = pages.stream().mapToInt(Pair::getValue).sum();
		ByteBuffer buffer = ByteBuffer.allocate(length);
		buffer.order(ByteOrder.LITTLE_ENDIAN);
		for (Pair<Long, Integer> page : pages) {
			//TODO(Matrix89): add MemoryMaps.load(mm, add, len, bb)
			byte[] tmpArray = new byte[page.getValue()];
			MemoryMaps.load(memoryMap, page.getKey(), tmpArray, 0, page.getValue());
			buffer.put(tmpArray);
		}
		buffer.flip();
		return buffer;
	}

	public ByteBuffer getAsByteBuffer(int offset, int length) throws MemoryAccessException {
		ByteBuffer buffer = ByteBuffer.allocate(length);
		int poff = 0;
		for (Pair<Long, Integer> page : pages) {
			if (offset < poff + page.getValue()) {
				int len = Math.min((poff + page.getValue()) - offset, length);
				byte[] tmpArray = new byte[len];
				MemoryMaps.load(memoryMap, page.getKey() + (offset - poff), tmpArray, 0, len);
				buffer.put(tmpArray);
				offset += len;
				length -= len;
			}
			poff += page.getValue();
		}

		buffer.flip();

		return buffer;
	}

	static class Builder {
		private final MemoryMap memoryMap;
		private final List<Pair<Long, Integer>> pages = new ArrayList<>();

		public Builder(MemoryMap memoryMap) {
			this.memoryMap = memoryMap;
		}

		public void addPage(long address, int length) {
			pages.add(new Pair<>(address, length));
		}

		public BackingPages build() {
			return new BackingPages(memoryMap, pages);
		}
	}
}
