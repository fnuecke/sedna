package li.cil.sedna.device.virtio;

import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.device.virtio.AbstractVirtIOInputDevice;
import li.cil.sedna.evdev.EvdevEvents;

public class VirtIOMouseDevice extends AbstractVirtIOInputDevice {
	private static final String NAME = "virtio_mouse";

	private static final int REL_X = 0x00;
	private static final int REL_Y = 0x01;
	private static final int BTN_LEFT = 0x110;
	private static final int BTN_RIGHT = 0x111;

	public VirtIOMouseDevice(MemoryMap memoryMap) {
		super(memoryMap);
	}

	public void sendRelEvent(double x, double y) {
		putEvent(EvdevEvents.EV_REL, REL_X, (int) x);
		putEvent(EvdevEvents.EV_REL, REL_Y, (int) y);
		putSyn();
	}

	public void sendRightClick(boolean down) {
		putEvent(EvdevEvents.EV_KEY, BTN_RIGHT, down ? 1 : 0);
		putSyn();
	}

	public void sendLeftClick(boolean down) {
		putEvent(EvdevEvents.EV_KEY, BTN_LEFT, down ? 1 : 0);
		putSyn();
	}
}
