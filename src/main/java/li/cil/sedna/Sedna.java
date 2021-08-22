package li.cil.sedna;

import it.unimi.dsi.fastutil.bytes.ByteArrayFIFOQueue;
import it.unimi.dsi.fastutil.ints.Int2LongArrayMap;
import li.cil.ceres.Ceres;
import li.cil.sedna.api.device.InterruptSource;
import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.device.PhysicalMemory;
import li.cil.sedna.device.block.SPIBlockDevice;
import li.cil.sedna.device.block.SparseBlockDevice;
import li.cil.sedna.device.flash.FlashMemoryDevice;
import li.cil.sedna.device.rtc.GoldfishRTC;
import li.cil.sedna.device.serial.UART16550A;
import li.cil.sedna.device.syscon.AbstractSystemController;
import li.cil.sedna.device.virtio.AbstractVirtIODevice;
import li.cil.sedna.device.virtio.VirtIOFileSystemDevice;
import li.cil.sedna.devicetree.DeviceTreeRegistry;
import li.cil.sedna.devicetree.provider.*;
import li.cil.sedna.riscv.R5CPU;
import li.cil.sedna.riscv.device.R5CoreLocalInterrupter;
import li.cil.sedna.riscv.device.R5PlatformLevelInterruptController;
import li.cil.sedna.riscv.devicetree.R5CoreLocalInterrupterProvider;
import li.cil.sedna.riscv.devicetree.R5PlatformLevelInterruptControllerProvider;
import li.cil.sedna.serialization.serializers.*;

import java.util.BitSet;
import java.util.concurrent.atomic.AtomicInteger;

public final class Sedna {
    private static boolean isInitialized = false;

    static {
        initialize();
    }

    public static void initialize() {
        if (isInitialized) {
            return;
        }

        isInitialized = true;

        Ceres.putSerializer(AtomicInteger.class, new AtomicIntegerSerializer());
        Ceres.putSerializer(BitSet.class, new BitSetSerializer());
        Ceres.putSerializer(ByteArrayFIFOQueue.class, new ByteArrayFIFOQueueSerializer());
        Ceres.putSerializer(VirtIOFileSystemDevice.FileSystemFileMap.class, new FileSystemFileMapSerializer());
        Ceres.putSerializer(Int2LongArrayMap.class, new Int2LongArrayMapSerializer());
        Ceres.putSerializer(R5CPU.class, new R5CPUSerializer());
        Ceres.putSerializer(SparseBlockDevice.SparseBlockMap.class, new SparseBlockMapSerializer());

        DeviceTreeRegistry.putProvider(FlashMemoryDevice.class, new FlashMemoryProvider());
        DeviceTreeRegistry.putProvider(GoldfishRTC.class, new GoldfishRTCProvider());
        DeviceTreeRegistry.putProvider(InterruptSource.class, new InterruptSourceProvider());
        DeviceTreeRegistry.putProvider(MemoryMappedDevice.class, new MemoryMappedDeviceProvider());
        DeviceTreeRegistry.putProvider(PhysicalMemory.class, new PhysicalMemoryProvider());
        DeviceTreeRegistry.putProvider(AbstractSystemController.class, new SystemControllerProvider());
        DeviceTreeRegistry.putProvider(UART16550A.class, new UART16550AProvider());
        DeviceTreeRegistry.putProvider(SPIBlockDevice.class, new SPIBlockDeviceProvider());
        DeviceTreeRegistry.putProvider(AbstractVirtIODevice.class, new VirtIOProvider());
        DeviceTreeRegistry.putProvider(R5CoreLocalInterrupter.class, new R5CoreLocalInterrupterProvider());
        DeviceTreeRegistry.putProvider(R5PlatformLevelInterruptController.class, new R5PlatformLevelInterruptControllerProvider());
    }
}
