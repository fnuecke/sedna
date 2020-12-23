package li.cil.sedna.device.virtio.gpu;

import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.device.virtio.VirtIOKeyboardDevice;
import li.cil.sedna.memory.MemoryMaps;
import org.jnativehook.GlobalScreen;
import org.jnativehook.NativeHookException;
import org.jnativehook.dispatcher.SwingDispatchService;
import org.jnativehook.keyboard.NativeKeyAdapter;
import org.jnativehook.keyboard.NativeKeyEvent;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Collection;

public class _GPUViewer extends JFrame {
	private MemoryMap memory;
	private VirtIOGPUDevice gpu;
	private VirtIOGPUResource resource;
	private VirtIOKeyboardDevice keyboardDevice;

	public _GPUViewer(MemoryMap memory, VirtIOGPUDevice gpu, VirtIOKeyboardDevice keyboardDevice) {
		this.memory = memory;
		this.gpu = gpu;
		this.keyboardDevice = keyboardDevice;
		setSize(800, 600);
		setLocationRelativeTo(null);
		setVisible(true);
		JPanel mainPanel = new DrawPane();
		setContentPane(mainPanel);
		GlobalScreen.setEventDispatcher(new SwingDispatchService());
		try {
			GlobalScreen.registerNativeHook();
		} catch (NativeHookException e) {
			e.printStackTrace();
		}
		GlobalScreen.addNativeKeyListener(new NativeKeyAdapter() {
			@Override
			public void nativeKeyPressed(NativeKeyEvent e) {
				System.out.println(e.getRawCode());
				keyboardDevice.sendKeyEvent(e.getRawCode(), true);
			}

			@Override
			public void nativeKeyReleased(NativeKeyEvent e) {
				keyboardDevice.sendKeyEvent(e.getRawCode(), false);
			}
		});
        new Thread(() -> {
        	while (true) {
		        Collection<VirtIOGPUResource> resources = gpu.getResources().values();
		        if (resources.size() > 0)
			        resource = (VirtIOGPUResource) resources.toArray()[0];
		        setTitle("resources: " + resources.size());
		        /*resources.forEach(resource -> {
			        System.out.println("==== resource ====");
			        System.out.println(resource.getFormat());
			        System.out.println(resource.getWidth());
			        System.out.println(resource.getHeight());
			        System.out.println(resource.getPages().length);
			        for (VirtIOGPUResource.Page page : resource.getPages()) {
				        System.out.println("\n== page ==");
				        System.out.println("\n" + page.getAddress());
				        System.out.println("\n" + page.getLength());
			        }
			        System.out.println("==== end resource ====");
		        });*/
		        repaint();
		        try {
			        Thread.sleep(100);
		        } catch (InterruptedException e) {
			        e.printStackTrace();
		        }
	        }
        }).start();
	}

	class DrawPane extends JPanel {
		@Override
		protected void paintComponent(Graphics g) {
			if (resource == null || resource.getPages().length == 0) {
				return;
			}

			g.clearRect(0, 0, 800, 600);
			VirtIOGPUResource.Page page = resource.getPages()[0];
			ByteBuffer dst = ByteBuffer.allocate(page.getLength());
			System.out.printf("old: %d, %d, %s\n", page.getAddress(), page.getLength(), memory);
			dst.order(ByteOrder.LITTLE_ENDIAN);
			try {
				MemoryMaps.load(memory, page.getAddress(), dst);
			} catch (MemoryAccessException e) {
				e.printStackTrace();
			}
			dst.rewind();
			BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_3BYTE_BGR);
			IntBuffer intBuffer = IntBuffer.allocate(800 * 600 * 3);
			while(dst.hasRemaining()) {
				intBuffer.put((dst.get() << 16 | dst.get() << 8 | dst.get()));
				dst.get();
			}
			image.setRGB(0, 0, 800, 600, intBuffer.array(), 0, 800);
			g.drawImage(image, 0, 0, 800, 600, null);
		}
	}

	// https://stackoverflow.com/a/26688875
	final public static Integer getScancodeFromKeyEvent(final KeyEvent keyEvent) {

		Integer ret;
		Field field;

		try {
			field = KeyEvent.class.getDeclaredField("scancode");
		} catch (NoSuchFieldException nsfe) {
			System.err.println("ATTENTION! The KeyEvent object does not have a field named \"scancode\"! (Which is kinda weird.)");
			nsfe.printStackTrace();
			return null;
		}

		try {
			field.setAccessible(true);
		} catch (SecurityException se) {
			System.err.println("ATTENTION! Changing the accessibility of the KeyEvent class' field \"scancode\" caused a security exception!");
			se.printStackTrace();
			return null;
		}

		try {
			ret = (int) field.getLong(keyEvent);
		} catch (IllegalAccessException iae) {
			System.err.println("ATTENTION! It is not allowed to read the field \"scancode\" of the KeyEvent instance!");
			iae.printStackTrace();
			return null;
		}

		return ret;
	}
}
