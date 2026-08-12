package li.cil.sedna.riscv;

import li.cil.ceres.BinarySerialization;
import li.cil.sedna.Sedna;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static li.cil.sedna.riscv.R5Assembler.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

public final class TLBSerializationTests {
    private static final int RAM_SIZE = 8 * 1024 * 1024;

    private static final long PROGRAM = Vm.RAM_START;

    private static final long ROOT_TABLE_A = Vm.RAM_START + 0x1000;
    private static final long LEVEL1_TABLE_A = Vm.RAM_START + 0x2000;
    private static final long ROOT_TABLE_B = Vm.RAM_START + 0x3000;
    private static final long LEVEL1_TABLE_B = Vm.RAM_START + 0x4000;

    private static final long TARGET_A = Vm.RAM_START + 0x200000;
    private static final long TARGET_B = Vm.RAM_START + 0x400000;

    private static final long VIRTUAL_ADDRESS = 0x1000;

    private static final long MARKER_A = 0x1111222233334444L;
    private static final long MARKER_B = 0x5555666677778888L;

    private static final long MSTATUS_TRANSLATE_DATA_AS_SUPERVISOR =
            R5.STATUS_MPRV_MASK | ((long) R5.PRIVILEGE_S << R5.STATUS_MPP_SHIFT);

    private Vm vm;

    @BeforeAll
    public static void setupSedna() {
        Sedna.initialize();
    }

    @BeforeEach
    public void setUp() {
        vm = Vm.create(RAM_SIZE);

        // csrrw x0, satp, x1     ; switch address space to the one described by x1
        // csrrs x0, mstatus, x2  ; translate data accesses as supervisor
        // ld    x4, 0(x3)        ; x4 = *x3, translated
        vm.write(PROGRAM,
                csrrw(0, R5CSR.SATP, 1),
                csrrs(0, R5CSR.MSTATUS, 2),
                ld(4, 3, 0));

        mapMegapage(ROOT_TABLE_A, LEVEL1_TABLE_A, TARGET_A);
        mapMegapage(ROOT_TABLE_B, LEVEL1_TABLE_B, TARGET_B);

        vm.store64(TARGET_A + VIRTUAL_ADDRESS, MARKER_A);
        vm.store64(TARGET_B + VIRTUAL_ADDRESS, MARKER_B);
    }

    @Test
    public void theTwoAddressSpacesResolveDifferently() {
        assertEquals(MARKER_A, loadThroughAddressSpace(newCPU(), ROOT_TABLE_A));
        assertEquals(MARKER_B, loadThroughAddressSpace(newCPU(), ROOT_TABLE_B));
    }

    @Test
    public void deserializationDiscardsStaleTLBEntries() {
        // Run a CPU in address space A, leaving a TLB entry for VIRTUAL_ADDRESS behind.
        final R5CPU cpu = newCPU();
        assertEquals(MARKER_A, loadThroughAddressSpace(cpu, ROOT_TABLE_A));

        // Capture the state of an otherwise identical CPU running in address space B.
        final R5CPU source = newCPU();
        assertEquals(MARKER_B, loadThroughAddressSpace(source, ROOT_TABLE_B));
        final ByteBuffer serialized = BinarySerialization.serialize(source, R5CPU.class);

        // Restore that state into the CPU holding the address space A entry.
        BinarySerialization.deserialize(serialized, R5CPU.class, cpu);

        // The restored state selects address space B, so the load must see TARGET_B.
        assertEquals(MARKER_B, repeatLoad(cpu));
    }

    private R5CPU newCPU() {
        return Vm.createCPU(vm.memoryMap(), PROGRAM);
    }

    private static long loadThroughAddressSpace(final R5CPU cpu, final long rootTable) {
        final long[] registers = cpu.getDebugInterface().getGeneralRegisters();
        registers[1] = Vm.satpSv39(rootTable);
        registers[2] = MSTATUS_TRANSLATE_DATA_AS_SUPERVISOR;
        registers[3] = VIRTUAL_ADDRESS;
        registers[4] = 0;

        cpu.getDebugInterface().setProgramCounter(PROGRAM);
        cpu.getDebugInterface().step();
        cpu.getDebugInterface().step();
        cpu.getDebugInterface().step();

        return registers[4];
    }

    private static long repeatLoad(final R5CPU cpu) {
        final long[] registers = cpu.getDebugInterface().getGeneralRegisters();
        registers[3] = VIRTUAL_ADDRESS;
        registers[4] = 0;

        cpu.getDebugInterface().setProgramCounter(PROGRAM + 8);
        cpu.getDebugInterface().step();

        return registers[4];
    }

    private void mapMegapage(final long rootTable, final long level1Table, final long target) {
        vm.store64(rootTable, Vm.pointerPTE(level1Table));
        vm.store64(level1Table, Vm.leafPTE(target, Vm.PTE_DATA));
    }
}
