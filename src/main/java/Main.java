import li.cil.sedna.Sedna;
import li.cil.sedna.device.serial.UART16550A;
import li.cil.sedna.device.virtio.VirtIOMouseDevice;
import li.cil.sedna.device.virtio.gpu.VirtIOGPUDevice;
import li.cil.sedna.device.virtio.gpu.AbstractVirtIOGPUScanout;
import li.cil.sedna.api.device.PhysicalMemory;
import li.cil.sedna.device.block.ByteBufferBlockDevice;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.device.virtio.VirtIOBlockDevice;
import li.cil.sedna.device.virtio.VirtIOFileSystemDevice;
import li.cil.sedna.device.virtio.VirtIOKeyboardDevice;
import li.cil.sedna.fs.HostFileSystem;
import li.cil.sedna.riscv.R5Board;

import javax.swing.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

public class Main {

	private void run() throws IOException {
		Sedna.initialize();
		// Load openSBI
		PhysicalMemory rom = Memory.create(1024 * 1024 * 64);
		Path p = Paths.get("fw_payload.bin");
		rom.store(0, ByteBuffer.wrap(Files.readAllBytes(p)));

		PhysicalMemory ram = Memory.create(1024 * 1024 * 32);

		R5Board board = new R5Board();
		board.addDevice(rom);
		board.addDevice(ram);

		File f = new File("rootfs.ext2");
		//File f = new File("rootfs-fedora.ext2");
		VirtIOBlockDevice fileSystemDevice = new VirtIOBlockDevice(board.getMemoryMap(), ByteBufferBlockDevice.createFromFile(f, f.length(), false));
		VirtIOKeyboardDevice keyboardDevice = new VirtIOKeyboardDevice(board.getMemoryMap());
		VirtIOMouseDevice mouseDevice = new VirtIOMouseDevice(board.getMemoryMap());
		VirtIOFileSystemDevice hostFS = new VirtIOFileSystemDevice(board.getMemoryMap(), "rootfs", new HostFileSystem());
		SwingVirtIOScanout scanout = new SwingVirtIOScanout(800, 600);
        VirtIOGPUDevice gpuDevice = new VirtIOGPUDevice.Builder(board.getMemoryMap())
		        .setScanouts(new ArrayList<AbstractVirtIOGPUScanout>(){{
					add(scanout.getScanout());
		        }})
		        .build();

		JFrame frame = new JFrame();
		frame.setContentPane(scanout.getComponent());
		frame.pack();
		frame.setVisible(true);

		//UART16550A uart = new UART16550A();
		board.addDevice(fileSystemDevice);
		board.addDevice(gpuDevice);
		board.addDevice(hostFS);
		board.addDevice(keyboardDevice);
		board.addDevice(mouseDevice);
		//board.addDevice(uart);
		//board.addDevice(0x09000000, uart);
		//board.setStandardOutputDevice(uart);
		board.setBootArguments("root=/dev/vda");
		fileSystemDevice.getInterrupt().set(0x1, board.getInterruptController());
		gpuDevice.getInterrupt().set(0x2, board.getInterruptController());
		keyboardDevice.getInterrupt().set(0x3, board.getInterruptController());
		hostFS.getInterrupt().set(0x4, board.getInterruptController());
		mouseDevice.getInterrupt().set(0x5, board.getInterruptController());
		//uart.getInterrupt().set(0x6, board.getInterruptController());
//
		board.initialize();
        board.setRunning(true);
		System.out.println("freq: " + board.getCpu().getFrequency());

		BufferedReader inputStreamReader = new BufferedReader(new InputStreamReader(System.in));

		while (board.isRunning()) {
			board.step(1);

			/*while (true) {
				int chr = uart.read();
				if (chr == -1) {
					break;
				}
//
				System.out.print((char) chr);
			}
			while (inputStreamReader.ready() && uart.canPutByte()) {
				uart.putByte((byte) inputStreamReader.read());
			}
			 */
		}

	}

	public static void main(String[] args) throws URISyntaxException, IOException {
		new Main().run();
	}

}
