import li.cil.sedna.api.device.PhysicalMemory;
import li.cil.sedna.device.block.ByteBufferBlockDevice;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.device.virtio.VirtIOBlockDevice;
import li.cil.sedna.device.virtio.VirtIOKeyboardDevice;
import li.cil.sedna.device.virtio.gpu.GPUViewer;
import li.cil.sedna.device.virtio.gpu.VirtIOGPUDevice;
import li.cil.sedna.device.virtio.gpu.VirtIOScanout;
import li.cil.sedna.riscv.R5Board;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

public class Main {

	private void run() throws IOException, URISyntaxException {
		// Load openSBI
		PhysicalMemory rom = Memory.create(1024 * 1024 * 64);
		Path p = Paths.get(this.getClass().getResource("fw_payload.bin").toURI());
		rom.store(0, ByteBuffer.wrap(Files.readAllBytes(p)));

		PhysicalMemory ram = Memory.create(1024 * 1024 * 32);

		R5Board board = new R5Board();
		board.addDevice(rom);
		board.addDevice(ram);

		File f = new File("rootfs.ext2");
		//File f = new File("rootfs-fedora.ext2");
		VirtIOBlockDevice fileSystemDevice = new VirtIOBlockDevice(board.getMemoryMap(), ByteBufferBlockDevice.createFromFile(f, f.length(), true));
		VirtIOKeyboardDevice keyboardDevice = new VirtIOKeyboardDevice(board.getMemoryMap());
		GPUViewer viewer = new GPUViewer(board.getMemoryMap(), keyboardDevice);
		VirtIOScanout scanout = new VirtIOScanout(0, 0, 800, 600, viewer);
        VirtIOGPUDevice gpuDevice = new VirtIOGPUDevice.Builder(board.getMemoryMap())
		        .setScanouts(new ArrayList<VirtIOScanout>(){{
					add(scanout);
		        }})
		        .build();

		//new _GPUViewer(board.getMemoryMap(), gpuDevice, keyboardDevice);
		//UART16550A uart = new UART16550A();
		board.addDevice(fileSystemDevice);
		board.addDevice(gpuDevice);
		board.addDevice(keyboardDevice);
		//board.addDevice(0x09000000, uart);
		//board.setStandardOutputDevice(uart);
		board.setBootArguments("root=/dev/vda ro");
		fileSystemDevice.getInterrupt().set(0x1, board.getInterruptController());
		gpuDevice.getInterrupt().set(0x2, board.getInterruptController());
		keyboardDevice.getInterrupt().set(0x3, board.getInterruptController());

		board.initialize();
        board.setRunning(true);
		System.out.println("freq: " + board.getCpu().getFrequency());

		BufferedReader inputStreamReader = new BufferedReader(new InputStreamReader(System.in));

//		new Thread(() -> {
//		    while(board.isRunning()) {
//				board.step(1);
//				//viewer.stepMainThread();
//		}).start();

		while (board.isRunning()) {
			board.step(1);
			//while (true) {
				//int chr = uart.read();
				//if (chr == -1) {
					//break;
				//}
//
				//System.out.print((char) chr);
			//}
			//System.out.flush();
			//while (inputStreamReader.ready() && uart.canPutByte()) {
			//uart.putByte((byte) inputStreamReader.read());
			//}
		}

	}

	public static void main(String[] args) throws URISyntaxException, IOException {
		new Main().run();
	}

}
