package li.cil.sedna.device.virtio.gpu;

public class VirtIOGPUResource {

	// Is there a class for this already?
	static class Page {
		private long address;
		private int length;

		public Page(long address, int length) {
			this.address = address;
			this.length = length;
		}

		public long getAddress() {
			return address;
		}

		public int getLength() {
			return length;
		}
	}

    private int format;
    private int width;
    private int height;
    private Page[] pages;

    private VirtIOScanout scanout;
    private VirtIOGPURect scanoutRect;

	public VirtIOGPUResource(int format, int width, int height) {
		this.format = format;
		this.width = width;
		this.height = height;
	}

	public void flush() {
		if (pages.length > 0 && scanout != null) {
			Page page = pages[0];
			scanout.flush(page.getAddress(), page.getLength());
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

	public void setScanout(final VirtIOScanout scanout, final VirtIOGPURect scanoutRect) {
		this.scanout = scanout;
		this.scanoutRect = scanoutRect;
	}

    public void attachBacking(Page[] pages) {
		this.pages = pages;
    }

	public Page[] getPages() {
		return pages;
	}
}
