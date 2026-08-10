package li.cil.sedna.riscv;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static li.cil.sedna.riscv.R5Assembler.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

public final class InterruptPriorityTests {
    private static final int RAM_SIZE = 4 * 1024;

    private static final long TRAP_VECTOR = Vm.RAM_START + 0x800;
    private static final long STRAP_VECTOR = Vm.RAM_START + 0xC00;

    /** {@code jal x0, 0}, a one instruction infinite loop. */
    private static final int LOOP = jal(0, 0);

    private static final long INTERRUPT = R5.interrupt(R5.XLEN_64);

    private Vm vm;

    @BeforeEach
    public void setUp() {
        vm = Vm.create(RAM_SIZE);

        // Park the machine in a tight loop at both trap vectors so the cycles spent inside
        // step() after the trap of interest do not execute garbage.
        vm.write(TRAP_VECTOR, LOOP);
        vm.write(STRAP_VECTOR, LOOP);
        vm.writeCSR(R5CSR.MTVEC, TRAP_VECTOR);
        vm.writeCSR(R5CSR.STVEC, STRAP_VECTOR);
    }

    @Test
    public void maskedDelegatedInterruptDoesNotShadowDeliverableOne() {
        // SSIP is delegated to S, STIP is not. Both pending and enabled in mie. In S-mode with
        // SIE=0 the delegated SSIP is masked, while the non-delegated STIP always fires (to M).
        vm.writeCSR(R5CSR.MIDELEG, R5.SSIP_MASK);
        vm.writeCSR(R5CSR.MIE, R5.SSIP_MASK | R5.STIP_MASK);
        vm.writeCSR(R5CSR.MIP, R5.SSIP_MASK | R5.STIP_MASK);

        vm.writeCSR(R5CSR.MSTATUS, R5.STATUS_SPP_MASK); // SPP = S, SPIE = 0 -> SIE = 0 after SRET.
        vm.execute(SRET);
        vm.cpu().step(1);

        assertEquals(INTERRUPT | R5.STIP_SHIFT, vm.readCSR(R5CSR.MCAUSE), "the deliverable STIP must fire, not the masked SSIP");
        assertEquals(0, vm.readCSR(R5CSR.SCAUSE), "the delegated, masked SSIP must not have been delivered");
    }

    @Test
    public void delegatedInterruptIsNotTakenInMachineMode() {
        // In M-mode with MIE=1, the delegated SSIP belongs to S and must not be taken; the
        // non-delegated STIP must be.
        vm.writeCSR(R5CSR.MIDELEG, R5.SSIP_MASK);
        vm.writeCSR(R5CSR.MIE, R5.SSIP_MASK | R5.STIP_MASK);
        vm.writeCSR(R5CSR.MIP, R5.SSIP_MASK | R5.STIP_MASK);
        vm.writeCSR(R5CSR.MSTATUS, R5.STATUS_MIE_MASK);
        vm.cpu().step(1);

        assertEquals(INTERRUPT | R5.STIP_SHIFT, vm.readCSR(R5CSR.MCAUSE), "the non-delegated STIP must fire in M-mode");
    }

    @Test
    public void machineExternalInterruptCanBeEnabledAndFires() {
        vm.writeCSR(R5CSR.MIE, R5.MEIP_MASK | R5.STIP_MASK);
        vm.writeCSR(R5CSR.MIP, R5.STIP_MASK);
        vm.cpu().raiseInterrupts(R5.MEIP_MASK); // MEIP is read-only in mip; raised by the PLIC.
        vm.writeCSR(R5CSR.MSTATUS, R5.STATUS_MIE_MASK);
        vm.cpu().step(1);

        assertEquals(INTERRUPT | R5.MEIP_SHIFT, vm.readCSR(R5CSR.MCAUSE), "MEIP must be deliverable and outrank STIP");
    }

    @Test
    public void delegatedInterruptFiresInSupervisorMode() {
        // Sanity check: with only the delegated SSIP pending and SIE=1, it must be delivered
        // to the S handler.
        vm.writeCSR(R5CSR.MIDELEG, R5.SSIP_MASK);
        vm.writeCSR(R5CSR.MIE, R5.SSIP_MASK);
        vm.writeCSR(R5CSR.MIP, R5.SSIP_MASK);

        vm.writeCSR(R5CSR.MSTATUS, R5.STATUS_SPP_MASK | R5.STATUS_SPIE_MASK); // SIE = 1 after SRET.
        vm.execute(SRET);
        vm.cpu().step(1);

        assertEquals(INTERRUPT | R5.SSIP_SHIFT, vm.readCSR(R5CSR.SCAUSE), "the delegated SSIP must be delivered to S");
    }
}
