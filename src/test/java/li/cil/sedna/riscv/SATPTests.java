package li.cil.sedna.riscv;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static li.cil.sedna.riscv.R5Assembler.csrrs;
import static li.cil.sedna.riscv.R5Assembler.csrrw;
import static org.junit.jupiter.api.Assertions.assertEquals;

public final class SATPTests {
    private static final int RAM_SIZE = 4 * 1024;

    private static final long PPN = 0x80000L;

    private Vm vm;

    @BeforeEach
    public void setUp() {
        vm = Vm.create(RAM_SIZE);
    }

    @Test
    public void writeSv39IsAccepted() {
        assertEquals(R5.SATP_MODE_SV39 | PPN, writeThenReadSatp(R5.SATP_MODE_SV39 | PPN));
    }

    @Test
    public void writeSv48IsAccepted() {
        assertEquals(R5.SATP_MODE_SV48 | PPN, writeThenReadSatp(R5.SATP_MODE_SV48 | PPN));
    }

    @Test
    public void writeBareFromSv39IsAccepted() {
        assertEquals(R5.SATP_MODE_SV39 | PPN, writeThenReadSatp(R5.SATP_MODE_SV39 | PPN));
        assertEquals(R5.SATP_MODE_NONE, writeThenReadSatp(R5.SATP_MODE_NONE));
    }

    @Test
    public void writeBareFromResetIsAccepted() {
        assertEquals(R5.SATP_MODE_NONE, writeThenReadSatp(R5.SATP_MODE_NONE));
    }

    @Test
    public void writeUnsupportedModeIsIgnored() {
        // We only do Sv39 and Sv48 for now. Spec says unsupported MODE writes leave satp
        // unchanged, so check for that.
        assertEquals(R5.SATP_MODE_SV39 | PPN, writeThenReadSatp(R5.SATP_MODE_SV39 | PPN));
        assertEquals(R5.SATP_MODE_SV39 | PPN, writeThenReadSatp(R5.SATP_MODE_SV57 | PPN));
    }

    @Test
    public void asidIsMaskedOff() {
        // ASID is not implemented and is masked off on write.
        final long withAsid = R5.SATP_MODE_SV39 | R5.SATP_ASID_MASK64 | PPN;
        assertEquals(R5.SATP_MODE_SV39 | PPN, writeThenReadSatp(withAsid));
    }

    private long writeThenReadSatp(final long value) {
        vm.write(Vm.RAM_START, csrrw(0, R5CSR.SATP, 1), csrrs(2, R5CSR.SATP, 0));

        final long[] registers = vm.registers();
        registers[1] = value;
        registers[2] = 0;

        vm.setProgramCounter(Vm.RAM_START);
        vm.stepOnce();
        vm.stepOnce();

        return registers[2];
    }
}
