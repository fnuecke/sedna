import li.cil.sedna.device.virtio.gpu.AbstractVirtIOGPUScanout;
import li.cil.sedna.device.virtio.gpu.VirtIOGPURect;
import li.cil.sedna.utils.ByteBufferInputStream;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

public class SwingVirtIOScanout {
	private Component component;
	private Scanout scanout;

	public JPanel getComponent() {
		return this.component;
	}

	public AbstractVirtIOGPUScanout getScanout() {
		return this.scanout;
	}

	public SwingVirtIOScanout(final int width, final int height) {
		this.component = new Component(width, height);
		this.scanout = new Scanout(0, 0, width, height);
	}

	private class Component extends JPanel {
		BufferedImage image;

		private Component(int width, int height) {
			this.image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
			setMinimumSize(new Dimension(width, height));
		}

		void flush(ByteBuffer data) throws IOException {
			data.rewind();
			int[] array = new int[data.capacity()];
			for (int i = 0; data.hasRemaining(); i++) {
				array[i] = data.get() << 16 | data.get() << 8 | data.get();
				data.get();
			}
			this.image.setRGB(0, 0, 800, 600, array, 0, 800);
			repaint();
		}

		@Override
		public void paint(Graphics g) {
			g.clearRect(0, 0, getWidth(), getHeight());
			if (image != null) {
				g.drawImage(image, 0, 0, 800, 600, null);
			}
		}
	}

	private class Scanout extends AbstractVirtIOGPUScanout {
		public Scanout(int x, int y, int width, int height) {
			super(x, y, width, height);
		}

		@Override
		public void flush(VirtIOGPURect rect) {
			try {
				component.flush(getResource().getBacking().duplicate());
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
}
