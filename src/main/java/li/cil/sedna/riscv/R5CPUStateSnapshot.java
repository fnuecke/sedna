package li.cil.sedna.riscv;

import java.util.Arrays;

@SuppressWarnings("SpellCheckingInspection")
public final class R5CPUStateSnapshot {
    public int pc;
    public final int[] x = new int[32];

//    public final float[] f = new float[32];
//    public byte fflags;
//    public byte frm;

    public int reservation_set = -1;

    public long mcycle;

    public int mstatus, mstatush;
    public int mtvec;
    public int medeleg, mideleg;
    public int mip, mie;
    public int mcounteren;
    public int mscratch;
    public int mepc;
    public int mcause;
    public int mtval;
    public byte fs;

    public int stvec;
    public int scounteren;
    public int sscratch;
    public int sepc;
    public int scause;
    public int stval;
    public int satp;

    public int priv;
    public boolean waitingForInterrupt;

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final R5CPUStateSnapshot that = (R5CPUStateSnapshot) o;
        return pc == that.pc &&
               reservation_set == that.reservation_set &&
               mcycle == that.mcycle &&
               mstatus == that.mstatus &&
               mstatush == that.mstatush &&
               mtvec == that.mtvec &&
               medeleg == that.medeleg &&
               mideleg == that.mideleg &&
               mip == that.mip &&
               mie == that.mie &&
               mcounteren == that.mcounteren &&
               mscratch == that.mscratch &&
               mepc == that.mepc &&
               mcause == that.mcause &&
               mtval == that.mtval &&
               fs == that.fs &&
               stvec == that.stvec &&
               scounteren == that.scounteren &&
               sscratch == that.sscratch &&
               sepc == that.sepc &&
               scause == that.scause &&
               stval == that.stval &&
               satp == that.satp &&
               priv == that.priv &&
               waitingForInterrupt == that.waitingForInterrupt &&
               Arrays.equals(x, that.x);
    }
}
