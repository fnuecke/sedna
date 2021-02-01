package li.cil.sedna.riscv;

import li.cil.ceres.api.Serialized;
import li.cil.sedna.api.Board;
import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.*;
import li.cil.sedna.api.device.rtc.RealTimeCounter;
import li.cil.sedna.api.devicetree.DeviceNames;
import li.cil.sedna.api.devicetree.DevicePropertyNames;
import li.cil.sedna.api.devicetree.DeviceTree;
import li.cil.sedna.api.memory.MappedMemoryRange;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.api.memory.MemoryRangeAllocationStrategy;
import li.cil.sedna.device.flash.FlashMemoryDevice;
import li.cil.sedna.devicetree.DeviceTreeRegistry;
import li.cil.sedna.devicetree.FlattenedDeviceTree;
import li.cil.sedna.memory.SimpleMemoryMap;
import li.cil.sedna.riscv.device.R5CoreLocalInterrupter;
import li.cil.sedna.riscv.device.R5PlatformLevelInterruptController;
import li.cil.sedna.riscv.device.R5SystemController;
import li.cil.sedna.riscv.exception.R5SystemPowerOffException;
import li.cil.sedna.riscv.exception.R5SystemResetException;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;

public final class R5Board implements Board {
    private static final long SYSCON_ADDRESS = 0x01000000L;
    private static final long CLINT_ADDRESS = 0x02000000L;
    private static final long PLIC_ADDRESS = 0x0C000000L;

    private static final long FLASH_ADDRESS = 0x1000L; // R5CPU starts executing at 0x1000.
    private static final int FLASH_SIZE = 0x100; // Just needs to fit "jump to firmware".

    private final MemoryRangeAllocationStrategy allocationStrategy = new R5MemoryRangeAllocationStrategy();

    private final MemoryMap memoryMap;
    private final RealTimeCounter rtc;
    private final FlashMemoryDevice flash;
    private final List<MemoryMappedDevice> devices = new ArrayList<>();
    private final List<Steppable> steppableDevices = new ArrayList<>();
    private MemoryMappedDevice standardOutputDevice;

    @Serialized private final R5CPU cpu;
    @Serialized private final R5CoreLocalInterrupter clint;
    @Serialized private final R5PlatformLevelInterruptController plic;
    @Serialized private String bootargs;
    @Serialized private boolean isRunning;

    public R5Board() {
        memoryMap = new SimpleMemoryMap();
        rtc = cpu = R5CPU.create(memoryMap);

        flash = new FlashMemoryDevice(FLASH_SIZE);
        clint = new R5CoreLocalInterrupter(rtc);
        plic = new R5PlatformLevelInterruptController();

        steppableDevices.add(cpu);

        // Wire up interrupts.
        clint.putHart(0, cpu);
        plic.setHart(cpu);

        // Map devices to memory.
        addDevice(SYSCON_ADDRESS, new R5SystemController());
        addDevice(CLINT_ADDRESS, clint);
        addDevice(PLIC_ADDRESS, plic);
        addDevice(FLASH_ADDRESS, flash);
    }

    public boolean isRunning() {
        return isRunning;
    }

    public void setRunning(final boolean value) {
        isRunning = value;
    }

    public R5CPU getCpu() {
        return cpu;
    }

    @Override
    public MemoryMap getMemoryMap() {
        return memoryMap;
    }

    @Override
    public InterruptController getInterruptController() {
        return plic;
    }

    @Override
    public MemoryRangeAllocationStrategy getAllocationStrategy() {
        return allocationStrategy;
    }

    @Override
    public boolean addDevice(final long address, final MemoryMappedDevice device) {
        if (device.getLength() == 0) {
            return false;
        }

        if (devices.contains(device)) {
            // This prevents adding the same device at different addresses. However, that
            // could be circumvented by using a wrapper device, so we save ourselves the
            // additional bookkeeping needed for this here.
            return false;
        }

        if (!memoryMap.addDevice(address, device)) {
            return false;
        }

        final int index = Collections.binarySearch(devices, device, (o1, o2) -> {
            final MappedMemoryRange range1 = memoryMap.getMemoryRange(o1).orElseThrow(AssertionError::new);
            final MappedMemoryRange range2 = memoryMap.getMemoryRange(o2).orElseThrow(AssertionError::new);
            return Long.compareUnsigned(range1.address(), range2.address());
        });
        assert index < 0;

        devices.add(~index, device);

        if (device instanceof Steppable) {
            steppableDevices.add((Steppable) device);
        }

        return true;
    }

    @Override
    public OptionalLong addDevice(final MemoryMappedDevice device) {
        final OptionalLong address = allocationStrategy.findMemoryRange(device, MemoryRangeAllocationStrategy.getMemoryMapIntersectionProvider(memoryMap));
        if (address.isPresent() && addDevice(address.getAsLong(), device)) {
            return address;
        }

        return OptionalLong.empty();
    }

    @Override
    public void removeDevice(final MemoryMappedDevice device) {
        memoryMap.removeDevice(device);
        devices.remove(device);

        if (device instanceof Steppable) {
            steppableDevices.remove(device);
        }

        if (standardOutputDevice == device) {
            standardOutputDevice = null;
        }
    }

    @Override
    public int getInterruptCount() {
        return R5PlatformLevelInterruptController.INTERRUPT_COUNT;
    }

    @Override
    public long getDefaultProgramStart() {
        return R5MemoryRangeAllocationStrategy.PHYSICAL_MEMORY_FIRST;
    }

    @Override
    public void setBootArguments(final String value) {
        if (value != null && value.length() > 64) {
            throw new IllegalArgumentException();
        }
        this.bootargs = value;
    }

    @Override
    public void setStandardOutputDevice(@Nullable final MemoryMappedDevice device) {
        if (device != null && !devices.contains(device)) {
            throw new IllegalArgumentException();
        }
        standardOutputDevice = device;
    }

    @Override
    public void step(final int cycles) {
        if (!isRunning) {
            return;
        }

        try {
            for (final Steppable device : steppableDevices) {
                device.step(cycles);
            }
        } catch (final R5SystemResetException e) {
            cpu.reset(false, getDefaultProgramStart());
        } catch (final R5SystemPowerOffException e) {
            reset();
            isRunning = false;
        }
    }

    @Override
    public void reset() {
        cpu.reset();

        for (final MemoryMappedDevice device : devices) {
            if (device instanceof Resettable) {
                ((Resettable) device).reset();
            }
        }
    }

    public void initialize() throws IllegalStateException, MemoryAccessException {
        initialize(getDefaultProgramStart());
    }

    public void initialize(final long programStart) throws IllegalStateException, MemoryAccessException {
        final FlattenedDeviceTree fdt = buildDeviceTree().flatten();
        final byte[] dtb = fdt.toDTB();

        OptionalLong fdtAddress = OptionalLong.empty();
        for (final MemoryMappedDevice device : devices) {
            if (device instanceof PhysicalMemory) {
                if (device.getLength() >= dtb.length) {
                    final MappedMemoryRange memoryRange = memoryMap.getMemoryRange(device).orElseThrow(AssertionError::new);

                    final long address = (memoryRange.start + memoryRange.size() - dtb.length) & ~0b111L; // align(8)
                    if (Long.compareUnsigned(address, memoryRange.start) < 0) {
                        continue;
                    }

                    if (!fdtAddress.isPresent() || Long.compareUnsigned(address, fdtAddress.getAsLong()) > 0) {
                        fdtAddress = OptionalLong.of(address);
                    }
                }
            }
        }

        if (!fdtAddress.isPresent()) {
            throw new IllegalStateException("No memory device present that can fit device tree.");
        }

        for (int i = 0; i < dtb.length; i++) {
            memoryMap.store(fdtAddress.getAsLong() + i, dtb[i], Sizes.SIZE_8_LOG2);
        }

        final ByteBuffer data = flash.getData();
        data.clear();

        final int auipc = 0b0010111;
        final int ld = 0b011_00000_0000011;
        final int jalr = 0b1100111;

        final int rd_t0 = 5 << 7;
        final int rd_a1 = 11 << 7;
        final int rs1_t0 = 5 << 15;

        final int imm_fdtAddressOffset = 0x10 << 20;
        final int imm_programStartOffset = 0x18 << 20;

        // 0x0000  auipc t0, 0 ; x5 = pc
        data.putInt(auipc | rd_t0);

        // 0x0004  ld a1, 8(t0) ; a1 = *(t0 + 8) = fdtAddress
        data.putInt(ld | rd_a1 | rs1_t0 | imm_fdtAddressOffset);

        // 0x0008  ld t0, 12(t0) ; t0 = *(t0 + 12) = programStart
        data.putInt(ld | rd_t0 | rs1_t0 | imm_programStartOffset);

        // 0x000C  jalr t0 ; jump to firmware
        data.putInt(jalr | rs1_t0);

        // 0x0010  fdtAddress
        data.putLong(fdtAddress.getAsLong());
        // 0x0018  programStart
        data.putLong(programStart);
    }

    private DeviceTree buildDeviceTree() {
        final DeviceTree root = DeviceTreeRegistry.create(memoryMap);
        root
                .addProp(DevicePropertyNames.NUM_ADDRESS_CELLS, 2)
                .addProp(DevicePropertyNames.NUM_SIZE_CELLS, 2)
                .addProp(DevicePropertyNames.COMPATIBLE, "riscv-sedna", "riscv-virtio")
                .addProp(DevicePropertyNames.MODEL, "riscv-virtio,sedna");

        root.putChild(DeviceNames.CPUS, cpus -> cpus
                .addProp(DevicePropertyNames.NUM_ADDRESS_CELLS, 1)
                .addProp(DevicePropertyNames.NUM_SIZE_CELLS, 0)
                .addProp(DevicePropertyNames.TIMEBASE_FREQUENCY, rtc.getFrequency())

                .putChild("cpu-map", cpuMap -> cpuMap
                        .putChild("cluster0", cluster -> cluster
                                .addProp("core0", root.getPHandle(cpu))))

                .putChild(DeviceNames.CPU, 0, cpuNode -> cpuNode
                        .addProp(DevicePropertyNames.DEVICE_TYPE, DeviceNames.CPU)
                        .addProp(DevicePropertyNames.REG, 0)
                        .addProp(DevicePropertyNames.STATUS, "okay")
                        .addProp(DevicePropertyNames.COMPATIBLE, "riscv")
                        .addProp("riscv,isa", getISAString(cpu))

                        .addProp(DevicePropertyNames.MMU_TYPE, "riscv,sv48")
                        .addProp(DevicePropertyNames.CLOCK_FREQUENCY, cpu.getFrequency())

                        .putChild(DeviceNames.INTERRUPT_CONTROLLER, ic -> ic
                                .addProp(DevicePropertyNames.NUM_INTERRUPT_CELLS, 1)
                                .addProp(DevicePropertyNames.INTERRUPT_CONTROLLER)
                                .addProp(DevicePropertyNames.COMPATIBLE, "riscv,cpu-intc")
                                .addProp(DevicePropertyNames.PHANDLE, ic.getPHandle(cpu)))));

        root.putChild("soc", soc -> soc
                .addProp(DevicePropertyNames.NUM_ADDRESS_CELLS, 2)
                .addProp(DevicePropertyNames.NUM_SIZE_CELLS, 2)
                .addProp(DevicePropertyNames.COMPATIBLE, "simple-bus")
                .addProp(DevicePropertyNames.RANGES));

        for (final MemoryMappedDevice device : devices) {
            final DeviceTree node = DeviceTreeRegistry.visit(root, memoryMap, device);
            if (node != null && device == standardOutputDevice) {
                root.putChild("chosen", chosen -> chosen
                        .addProp("stdout-path", node.getPath()));
            }
        }

        if (bootargs != null) {
            root.putChild("chosen", chosen -> chosen
                    .addProp("bootargs", bootargs));
        }

        return root;
    }

    private static String getISAString(final R5CPU cpu) {
        final StringBuilder isa = new StringBuilder("rv64");
        for (final char i : R5.CANONICAL_ISA_ORDER.toCharArray()) {
            if ((cpu.getISA() & (1 << (Character.toLowerCase(i) - 'a'))) != 0) {
                isa.append(Character.toLowerCase(i));
            }
        }
        return isa.toString();
    }
}
