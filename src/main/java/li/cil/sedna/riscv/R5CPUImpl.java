/*
 * This file is GENERATED - do not edit it by hand; any changes will be overwritten.
 * Regenerate with `./gradlew generateDecoder`, which runs li.cil.sedna.riscv.R5CPUImplGenerator.
 */

package li.cil.sedna.riscv;

import it.unimi.dsi.fastutil.longs.LongSet;
import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.device.rtc.RealTimeCounter;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.riscv.exception.R5IllegalInstructionException;
import li.cil.sedna.riscv.exception.R5MemoryAccessException;
import li.cil.sedna.utils.BitUtils;

import javax.annotation.Nullable;

final class R5CPUImpl extends R5CPUBase {
    R5CPUImpl(final MemoryMap physicalMemory, @Nullable final RealTimeCounter rtc) {
        super(physicalMemory, rtc);
    }

    @Override
    protected void interpretTrace32(final MemoryMappedDevice device, final long hostBase, int inst, long pc, int instOffset, final int instEnd, final LongSet breakpoints) {
        try { // Catch any exceptions to patch PC field.
            for (; ; ) { // End of page check at the bottom since we enter with a valid inst.
                if (breakpoints != null && breakpoints.contains(pc)) {
                    this.pc = pc;
                    debugInterface.handleBreakpoint(pc);
                    return;
                }
                mcycle++;
                minstret++;

                decode: {
                    switch (inst & 0x3) {
                        case 0: {
                            interpretTrace32$instructionGroup0(inst, pc);
                            pc += 2;
                            instOffset += 2;
                            break decode;
                        }
                        case 1: {
                            switch (interpretTrace32$instructionGroup2(inst, pc)) {
                                case 0 -> {
                                    pc += 2;
                                    instOffset += 2;
                                    break decode;
                                }
                                case 1 -> {
                                    pc += 2;
                                    instOffset += 2;
                                    this.pc = pc;
                                    return;
                                }
                                case 2 -> {
                                    return;
                                }
                                case 3 -> {
                                    final long jumpTarget = this.pc;
                                    if (Long.compareUnsigned(pc, jumpTarget) >= 0) {
                                        return;
                                    }
                                    final long jumpDelta = jumpTarget - pc;
                                    pc = jumpTarget;
                                    if ((long) (int) jumpDelta != jumpDelta) {
                                        return;
                                    }
                                    instOffset += (int) jumpDelta;
                                    break decode;
                                }
                                case 4 -> {
                                    final long jumpTarget = this.pc;
                                    if (Long.compareUnsigned(pc, jumpTarget) >= 0) {
                                        if (mcycle >= cycleLimit || ((jumpTarget ^ pc) & ~(long) R5.PAGE_ADDRESS_MASK) != 0) {
                                            return;
                                        }
                                    }
                                    final long jumpDelta = jumpTarget - pc;
                                    pc = jumpTarget;
                                    if ((long) (int) jumpDelta != jumpDelta) {
                                        return;
                                    }
                                    instOffset += (int) jumpDelta;
                                    break decode;
                                }
                                default -> throw illegalInstruction();
                            }
                        }
                        case 2: {
                            switch (interpretTrace32$instructionGroup9(inst, pc)) {
                                case 0 -> {
                                    pc += 2;
                                    instOffset += 2;
                                    break decode;
                                }
                                case 1 -> {
                                    pc += 2;
                                    instOffset += 2;
                                    this.pc = pc;
                                    return;
                                }
                                case 2 -> {
                                    return;
                                }
                                case 3 -> {
                                    final long jumpTarget = this.pc;
                                    if (Long.compareUnsigned(pc, jumpTarget) >= 0) {
                                        return;
                                    }
                                    final long jumpDelta = jumpTarget - pc;
                                    pc = jumpTarget;
                                    if ((long) (int) jumpDelta != jumpDelta) {
                                        return;
                                    }
                                    instOffset += (int) jumpDelta;
                                    break decode;
                                }
                                case 4 -> {
                                    final long jumpTarget = this.pc;
                                    if (Long.compareUnsigned(pc, jumpTarget) >= 0) {
                                        if (mcycle >= cycleLimit || ((jumpTarget ^ pc) & ~(long) R5.PAGE_ADDRESS_MASK) != 0) {
                                            return;
                                        }
                                    }
                                    final long jumpDelta = jumpTarget - pc;
                                    pc = jumpTarget;
                                    if ((long) (int) jumpDelta != jumpDelta) {
                                        return;
                                    }
                                    instOffset += (int) jumpDelta;
                                    break decode;
                                }
                                default -> throw illegalInstruction();
                            }
                        }
                        case 3: {
                            switch (interpretTrace32$instructionGroup15(inst, pc)) {
                                case 0 -> {
                                    pc += 4;
                                    instOffset += 4;
                                    break decode;
                                }
                                case 1 -> {
                                    pc += 4;
                                    instOffset += 4;
                                    this.pc = pc;
                                    return;
                                }
                                case 2 -> {
                                    return;
                                }
                                case 3 -> {
                                    final long jumpTarget = this.pc;
                                    if (Long.compareUnsigned(pc, jumpTarget) >= 0) {
                                        return;
                                    }
                                    final long jumpDelta = jumpTarget - pc;
                                    pc = jumpTarget;
                                    if ((long) (int) jumpDelta != jumpDelta) {
                                        return;
                                    }
                                    instOffset += (int) jumpDelta;
                                    break decode;
                                }
                                case 4 -> {
                                    final long jumpTarget = this.pc;
                                    if (Long.compareUnsigned(pc, jumpTarget) >= 0) {
                                        if (mcycle >= cycleLimit || ((jumpTarget ^ pc) & ~(long) R5.PAGE_ADDRESS_MASK) != 0) {
                                            return;
                                        }
                                    }
                                    final long jumpDelta = jumpTarget - pc;
                                    pc = jumpTarget;
                                    if ((long) (int) jumpDelta != jumpDelta) {
                                        return;
                                    }
                                    instOffset += (int) jumpDelta;
                                    break decode;
                                }
                                default -> throw illegalInstruction();
                            }
                        }
                        default:
                            throw illegalInstruction();
                    }
                }

                if (Integer.compareUnsigned(instOffset, instEnd) < 0) { // Likely case: we're still fully in the page.
                    inst = hostBase != 0 ? UNSAFE.getInt(hostBase + instOffset) : (int) device.load(instOffset, Sizes.SIZE_32_LOG2);
                } else { // Unlikely case: we reached the end of the page. Leave to do interrupts and cycle check.
                    this.pc = pc;
                    return;
                }
            }
        } catch (final MemoryAccessException e) {
            this.pc = pc;
            raiseException(R5.EXCEPTION_FAULT_FETCH, pc);
        } catch (final R5IllegalInstructionException e) {
            this.pc = pc;
            raiseException(R5.EXCEPTION_ILLEGAL_INSTRUCTION, inst);
        } catch (final R5MemoryAccessException e) {
            this.pc = pc;
            raiseException(e.getType(), e.getAddress());
        }
    }

    private void interpretTrace32$instructionGroup0(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException, li.cil.sedna.riscv.exception.R5MemoryAccessException {
        switch (((inst & 0xe000) >>> 13)) {
            case 0: {
                interpretTrace32$instructionGroup1(inst, pc);
                return;
            }
            case 1: {
                fld((((inst >>> 2) & 0x7)) + 8, (((inst >>> 7) & 0x7)) + 8, ((inst << 1) & 0xc0) | ((inst >>> 7) & 0x38));
                return;
            }
            case 2: {
                lw((((inst >>> 2) & 0x7)) + 8, (((inst >>> 7) & 0x7)) + 8, ((inst << 1) & 0x40) | ((inst >>> 4) & 0x4) | ((inst >>> 7) & 0x38));
                return;
            }
            case 3: {
                flw((((inst >>> 2) & 0x7)) + 8, (((inst >>> 7) & 0x7)) + 8, ((inst << 1) & 0x40) | ((inst >>> 4) & 0x4) | ((inst >>> 7) & 0x38));
                return;
            }
            case 5: {
                fsd((((inst >>> 7) & 0x7)) + 8, (((inst >>> 2) & 0x7)) + 8, ((inst << 1) & 0xc0) | ((inst >>> 7) & 0x38));
                return;
            }
            case 6: {
                sw((((inst >>> 7) & 0x7)) + 8, (((inst >>> 2) & 0x7)) + 8, ((inst << 1) & 0x40) | ((inst >>> 4) & 0x4) | ((inst >>> 7) & 0x38));
                return;
            }
            case 7: {
                fsw((((inst >>> 7) & 0x7)) + 8, (((inst >>> 2) & 0x7)) + 8, ((inst << 1) & 0x40) | ((inst >>> 4) & 0x4) | ((inst >>> 7) & 0x38));
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace32$instructionGroup1(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        if ((inst & 0x1ffc) == 0x0) {
            throw illegalInstruction();
        }
        if ((inst & 0x1fe0) == 0x0) {
            throw illegalInstruction();
        }
        addiw((((inst >>> 2) & 0x7)) + 8, 2, ((inst >>> 2) & 0x8) | ((inst >>> 4) & 0x4) | ((inst >>> 1) & 0x3c0) | ((inst >>> 7) & 0x30));
        return;
    }

    private int interpretTrace32$instructionGroup2(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        switch (((inst & 0xe000) >>> 13)) {
            case 0: {
                interpretTrace32$instructionGroup3(inst, pc);
                return 0;
            }
            case 1: {
                jalw(1, ((inst << 3) & 0x20) | ((inst >>> 2) & 0xe) | ((inst << 1) & 0x80) | ((inst >>> 1) & 0x40) | ((inst << 2) & 0x400) | ((inst >>> 1) & 0x300) | ((inst >>> 7) & 0x10) | BitUtils.extendSign(((inst >>> 1) & 0x800), 12), pc, 2);
                return 3;
            }
            case 2: {
                interpretTrace32$instructionGroup4(inst, pc);
                return 0;
            }
            case 3: {
                interpretTrace32$instructionGroup5(inst, pc);
                return 0;
            }
            case 4: {
                interpretTrace32$instructionGroup6(inst, pc);
                return 0;
            }
            case 5: {
                jalw(0, ((inst << 3) & 0x20) | ((inst >>> 2) & 0xe) | ((inst << 1) & 0x80) | ((inst >>> 1) & 0x40) | ((inst << 2) & 0x400) | ((inst >>> 1) & 0x300) | ((inst >>> 7) & 0x10) | BitUtils.extendSign(((inst >>> 1) & 0x800), 12), pc, 2);
                return 3;
            }
            case 6: {
                if (beq((((inst >>> 7) & 0x7)) + 8, 0, ((inst << 3) & 0x20) | ((inst >>> 2) & 0x6) | ((inst << 1) & 0xc0) | ((inst >>> 7) & 0x18) | BitUtils.extendSign(((inst >>> 4) & 0x100), 9), pc)) {
                    return 4;
                }
                return 0;
            }
            case 7: {
                if (bne((((inst >>> 7) & 0x7)) + 8, 0, ((inst << 3) & 0x20) | ((inst >>> 2) & 0x6) | ((inst << 1) & 0xc0) | ((inst >>> 7) & 0x18) | BitUtils.extendSign(((inst >>> 4) & 0x100), 9), pc)) {
                    return 4;
                }
                return 0;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace32$instructionGroup3(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        if ((inst & 0x1ffc) == 0x0) {
            return;
        }
        if ((inst & 0x107c) == 0x0) {
            return;
        }
        if ((inst & 0xf80) == 0x0) {
            return;
        }
        addiw(((inst >>> 7) & 0x1f), ((inst >>> 7) & 0x1f), ((inst >>> 2) & 0x1f) | BitUtils.extendSign(((inst >>> 7) & 0x20), 6));
        return;
    }

    private void interpretTrace32$instructionGroup4(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        if ((inst & 0xf80) == 0x0) {
            return;
        }
        addiw(((inst >>> 7) & 0x1f), 0, ((inst >>> 2) & 0x1f) | BitUtils.extendSign(((inst >>> 7) & 0x20), 6));
        return;
    }

    private void interpretTrace32$instructionGroup5(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        if ((inst & 0x1ffc) == 0x100) {
            throw illegalInstruction();
        }
        if ((inst & 0xf80) == 0x100) {
            addiw(2, 2, ((inst << 3) & 0x20) | ((inst << 4) & 0x180) | ((inst << 1) & 0x40) | ((inst >>> 2) & 0x10) | BitUtils.extendSign(((inst >>> 3) & 0x200), 10));
            return;
        }
        if ((inst & 0xf80) == 0x0) {
            return;
        }
        lui(((inst >>> 7) & 0x1f), ((inst << 10) & 0x1f000) | BitUtils.extendSign(((inst << 5) & 0x20000), 18));
        return;
    }

    private void interpretTrace32$instructionGroup6(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rd_rs1 = (((inst >>> 7) & 0x7)) + 8;
        switch (((inst & 0xc00) >>> 10)) {
            case 0: {
                interpretTrace32$instructionGroup7(inst, pc, rd_rs1);
                return;
            }
            case 1: {
                sraiw(rd_rs1, rd_rs1, ((inst >>> 2) & 0x1f) | ((inst >>> 7) & 0x20));
                return;
            }
            case 2: {
                andi(rd_rs1, rd_rs1, ((inst >>> 2) & 0x1f) | BitUtils.extendSign(((inst >>> 7) & 0x20), 6));
                return;
            }
            case 3: {
                interpretTrace32$instructionGroup8(inst, pc, rd_rs1);
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace32$instructionGroup7(final int inst, final long pc, final int arg0) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        if ((inst & 0x107c) == 0x0) {
            return;
        }
        srliw(arg0, arg0, ((inst >>> 2) & 0x1f) | ((inst >>> 7) & 0x20));
        return;
    }

    private void interpretTrace32$instructionGroup8(final int inst, final long pc, final int arg0) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rs2 = (((inst >>> 2) & 0x7)) + 8;
        switch (((inst & 0x60) >>> 5) | (((inst & 0x1000) >>> 12) << 2)) {
            case 0: {
                subw(arg0, arg0, rs2);
                return;
            }
            case 1: {
                xor(arg0, arg0, rs2);
                return;
            }
            case 2: {
                or(arg0, arg0, rs2);
                return;
            }
            case 3: {
                and(arg0, arg0, rs2);
                return;
            }
            case 4: {
                subw(arg0, arg0, rs2);
                return;
            }
            case 5: {
                addw(arg0, arg0, rs2);
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private int interpretTrace32$instructionGroup9(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException, li.cil.sedna.riscv.exception.R5MemoryAccessException {
        switch (((inst & 0xe000) >>> 13)) {
            case 0: {
                interpretTrace32$instructionGroup10(inst, pc);
                return 0;
            }
            case 1: {
                fld(((inst >>> 7) & 0x1f), 2, ((inst << 4) & 0x1c0) | ((inst >>> 2) & 0x18) | ((inst >>> 7) & 0x20));
                return 0;
            }
            case 2: {
                interpretTrace32$instructionGroup11(inst, pc);
                return 0;
            }
            case 3: {
                flw(((inst >>> 7) & 0x1f), 2, ((inst << 4) & 0xc0) | ((inst >>> 2) & 0x1c) | ((inst >>> 7) & 0x20));
                return 0;
            }
            case 4: {
                return interpretTrace32$instructionGroup12(inst, pc);
            }
            case 5: {
                fsd(2, ((inst >>> 2) & 0x1f), ((inst >>> 1) & 0x1c0) | ((inst >>> 7) & 0x38));
                return 0;
            }
            case 6: {
                sw(2, ((inst >>> 2) & 0x1f), ((inst >>> 1) & 0xc0) | ((inst >>> 7) & 0x3c));
                return 0;
            }
            case 7: {
                fsw(2, ((inst >>> 2) & 0x1f), ((inst >>> 1) & 0xc0) | ((inst >>> 7) & 0x3c));
                return 0;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace32$instructionGroup10(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        if ((inst & 0x1000) == 0x0) {
            slliw(((inst >>> 7) & 0x1f), ((inst >>> 7) & 0x1f), ((inst >>> 2) & 0x1f));
            return;
        }
        if ((inst & 0x1f80) == 0x1000) {
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace32$instructionGroup11(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException, li.cil.sedna.riscv.exception.R5MemoryAccessException {
        if ((inst & 0xf80) == 0x0) {
            throw illegalInstruction();
        }
        lw(((inst >>> 7) & 0x1f), 2, ((inst << 4) & 0xc0) | ((inst >>> 2) & 0x1c) | ((inst >>> 7) & 0x20));
        return;
    }

    private int interpretTrace32$instructionGroup12(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        if ((inst & 0x1000) == 0x0) {
            return interpretTrace32$instructionGroup13(inst, pc);
        }
        if ((inst & 0x1000) == 0x1000) {
            return interpretTrace32$instructionGroup14(inst, pc);
        }
        throw illegalInstruction();
    }

    private int interpretTrace32$instructionGroup13(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        if ((inst & 0xffc) == 0x0) {
            throw illegalInstruction();
        }
        if ((inst & 0x7c) == 0x0) {
            jalrw(0, ((inst >>> 7) & 0x1f), 0, pc, 2);
            return 3;
        }
        if ((inst & 0xf80) == 0x0) {
            return 0;
        }
        addw(((inst >>> 7) & 0x1f), 0, ((inst >>> 2) & 0x1f));
        return 0;
    }

    private int interpretTrace32$instructionGroup14(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        if ((inst & 0xffc) == 0x0) {
            ebreak(pc);
            return 3;
        }
        if ((inst & 0x7c) == 0x0) {
            jalrw(1, ((inst >>> 7) & 0x1f), 0, pc, 2);
            return 3;
        }
        if ((inst & 0xf80) == 0x0) {
            return 0;
        }
        addw(((inst >>> 7) & 0x1f), ((inst >>> 7) & 0x1f), ((inst >>> 2) & 0x1f));
        return 0;
    }

    private int interpretTrace32$instructionGroup15(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException, li.cil.sedna.riscv.exception.R5MemoryAccessException {
        switch (((inst & 0x7c) >>> 2)) {
            case 0: {
                interpretTrace32$instructionGroup16(inst, pc);
                return 0;
            }
            case 1: {
                interpretTrace32$instructionGroup17(inst, pc);
                return 0;
            }
            case 3: {
                interpretTrace32$instructionGroup18(inst, pc);
                return 0;
            }
            case 4: {
                interpretTrace32$instructionGroup19(inst, pc);
                return 0;
            }
            case 5: {
                auipcw(((inst >>> 7) & 0x1f), BitUtils.extendSign((inst & 0xfffff000), 32), pc);
                return 0;
            }
            case 8: {
                interpretTrace32$instructionGroup21(inst, pc);
                return 0;
            }
            case 9: {
                interpretTrace32$instructionGroup22(inst, pc);
                return 0;
            }
            case 11: {
                interpretTrace32$instructionGroup23(inst, pc);
                return 0;
            }
            case 12: {
                interpretTrace32$instructionGroup24(inst, pc);
                return 0;
            }
            case 13: {
                lui(((inst >>> 7) & 0x1f), BitUtils.extendSign((inst & 0xfffff000), 32));
                return 0;
            }
            case 16: {
                interpretTrace32$instructionGroup25(inst, pc);
                return 0;
            }
            case 17: {
                interpretTrace32$instructionGroup26(inst, pc);
                return 0;
            }
            case 18: {
                interpretTrace32$instructionGroup27(inst, pc);
                return 0;
            }
            case 19: {
                interpretTrace32$instructionGroup28(inst, pc);
                return 0;
            }
            case 20: {
                interpretTrace32$instructionGroup29(inst, pc);
                return 0;
            }
            case 24: {
                return interpretTrace32$instructionGroup41(inst, pc);
            }
            case 25: {
                if ((inst & 0x7000) == 0x0) {
                    jalrw(((inst >>> 7) & 0x1f), ((inst >>> 15) & 0x1f), BitUtils.extendSign(((inst >>> 20) & 0xfff), 12), pc, 4);
                    return 3;
                }
                throw illegalInstruction();
            }
            case 27: {
                jalw(((inst >>> 7) & 0x1f), (inst & 0xff000) | ((inst >>> 9) & 0x800) | ((inst >>> 20) & 0x7fe) | BitUtils.extendSign(((inst >>> 11) & 0x100000), 21), pc, 4);
                return 3;
            }
            case 28: {
                return interpretTrace32$instructionGroup42(inst, pc);
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace32$instructionGroup16(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException, li.cil.sedna.riscv.exception.R5MemoryAccessException {
        final int imm = BitUtils.extendSign(((inst >>> 20) & 0xfff), 12);
        final int rd = ((inst >>> 7) & 0x1f);
        final int rs1 = ((inst >>> 15) & 0x1f);
        switch (((inst & 0x7000) >>> 12)) {
            case 0: {
                lb(rd, rs1, imm);
                return;
            }
            case 1: {
                lh(rd, rs1, imm);
                return;
            }
            case 2: {
                lw(rd, rs1, imm);
                return;
            }
            case 4: {
                lbu(rd, rs1, imm);
                return;
            }
            case 5: {
                lhu(rd, rs1, imm);
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace32$instructionGroup17(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException, li.cil.sedna.riscv.exception.R5MemoryAccessException {
        final int imm = BitUtils.extendSign(((inst >>> 20) & 0xfff), 12);
        final int rd = ((inst >>> 7) & 0x1f);
        final int rs1 = ((inst >>> 15) & 0x1f);
        if ((inst & 0x7000) == 0x2000) {
            flw(rd, rs1, imm);
            return;
        }
        if ((inst & 0x7000) == 0x3000) {
            fld(rd, rs1, imm);
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace32$instructionGroup18(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        if ((inst & 0x7000) == 0x0) {
            fence();
            return;
        }
        if ((inst & 0x7000) == 0x1000) {
            fence_i();
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace32$instructionGroup19(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rd = ((inst >>> 7) & 0x1f);
        final int rs1 = ((inst >>> 15) & 0x1f);
        switch (((inst & 0x7000) >>> 12)) {
            case 0: {
                addiw(rd, rs1, BitUtils.extendSign(((inst >>> 20) & 0xfff), 12));
                return;
            }
            case 1: {
                if ((inst & 0xfe000000) == 0x0) {
                    slliw(rd, rs1, ((inst >>> 20) & 0x1f));
                    return;
                }
                throw illegalInstruction();
            }
            case 2: {
                slti(rd, rs1, BitUtils.extendSign(((inst >>> 20) & 0xfff), 12));
                return;
            }
            case 3: {
                sltiu(rd, rs1, BitUtils.extendSign(((inst >>> 20) & 0xfff), 12));
                return;
            }
            case 4: {
                xori(rd, rs1, BitUtils.extendSign(((inst >>> 20) & 0xfff), 12));
                return;
            }
            case 5: {
                interpretTrace32$instructionGroup20(inst, pc, rd, rs1);
                return;
            }
            case 6: {
                ori(rd, rs1, BitUtils.extendSign(((inst >>> 20) & 0xfff), 12));
                return;
            }
            case 7: {
                andi(rd, rs1, BitUtils.extendSign(((inst >>> 20) & 0xfff), 12));
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace32$instructionGroup20(final int inst, final long pc, final int arg0, final int arg1) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int shamt = ((inst >>> 20) & 0x1f);
        if ((inst & 0xfe000000) == 0x0) {
            srliw(arg0, arg1, shamt);
            return;
        }
        if ((inst & 0xfe000000) == 0x40000000) {
            sraiw(arg0, arg1, shamt);
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace32$instructionGroup21(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException, li.cil.sedna.riscv.exception.R5MemoryAccessException {
        final int imm = ((inst >>> 7) & 0x1f) | BitUtils.extendSign(((inst >>> 20) & 0xfe0), 12);
        final int rs1 = ((inst >>> 15) & 0x1f);
        final int rs2 = ((inst >>> 20) & 0x1f);
        switch (inst & 0x707f) {
            case 35: {
                sb(rs1, rs2, imm);
                return;
            }
            case 4131: {
                sh(rs1, rs2, imm);
                return;
            }
            case 8227: {
                sw(rs1, rs2, imm);
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace32$instructionGroup22(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException, li.cil.sedna.riscv.exception.R5MemoryAccessException {
        final int imm = ((inst >>> 7) & 0x1f) | BitUtils.extendSign(((inst >>> 20) & 0xfe0), 12);
        final int rs1 = ((inst >>> 15) & 0x1f);
        final int rs2 = ((inst >>> 20) & 0x1f);
        if ((inst & 0x7000) == 0x2000) {
            fsw(rs1, rs2, imm);
            return;
        }
        if ((inst & 0x7000) == 0x3000) {
            fsd(rs1, rs2, imm);
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace32$instructionGroup23(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException, li.cil.sedna.riscv.exception.R5MemoryAccessException {
        final int rd = ((inst >>> 7) & 0x1f);
        final int rs1 = ((inst >>> 15) & 0x1f);
        final int rs2 = ((inst >>> 20) & 0x1f);
        if ((inst & 0x7000) != 0x2000) {
            throw illegalInstruction();
        }
        switch (((inst & 0xf8000000) >>> 27)) {
            case 0: {
                amoadd_w(rd, rs1, rs2);
                return;
            }
            case 1: {
                amoswap_w(rd, rs1, rs2);
                return;
            }
            case 2: {
                if ((inst & 0x1f00000) == 0x0) {
                    lr_w(rd, rs1);
                    return;
                }
                throw illegalInstruction();
            }
            case 3: {
                sc_w(rd, rs1, rs2);
                return;
            }
            case 4: {
                amoxor_w(rd, rs1, rs2);
                return;
            }
            case 8: {
                amoor_w(rd, rs1, rs2);
                return;
            }
            case 12: {
                amoand_w(rd, rs1, rs2);
                return;
            }
            case 16: {
                amomin_w(rd, rs1, rs2);
                return;
            }
            case 20: {
                amomax_w(rd, rs1, rs2);
                return;
            }
            case 24: {
                amominu_w(rd, rs1, rs2);
                return;
            }
            case 28: {
                amomaxu_w(rd, rs1, rs2);
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace32$instructionGroup24(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rd = ((inst >>> 7) & 0x1f);
        final int rs1 = ((inst >>> 15) & 0x1f);
        final int rs2 = ((inst >>> 20) & 0x1f);
        switch (inst & 0xfe00707f) {
            case 51: {
                addw(rd, rs1, rs2);
                return;
            }
            case 4147: {
                sllw(rd, rs1, rs2);
                return;
            }
            case 8243: {
                slt(rd, rs1, rs2);
                return;
            }
            case 12339: {
                sltu(rd, rs1, rs2);
                return;
            }
            case 16435: {
                xor(rd, rs1, rs2);
                return;
            }
            case 20531: {
                srlw(rd, rs1, rs2);
                return;
            }
            case 24627: {
                or(rd, rs1, rs2);
                return;
            }
            case 28723: {
                and(rd, rs1, rs2);
                return;
            }
            case 33554483: {
                mulw(rd, rs1, rs2);
                return;
            }
            case 33558579: {
                mulhw(rd, rs1, rs2);
                return;
            }
            case 33562675: {
                mulhsuw(rd, rs1, rs2);
                return;
            }
            case 33566771: {
                mulhuw(rd, rs1, rs2);
                return;
            }
            case 33570867: {
                divw(rd, rs1, rs2);
                return;
            }
            case 33574963: {
                divuw(rd, rs1, rs2);
                return;
            }
            case 33579059: {
                remw(rd, rs1, rs2);
                return;
            }
            case 33583155: {
                remuw(rd, rs1, rs2);
                return;
            }
            case 1073741875: {
                subw(rd, rs1, rs2);
                return;
            }
            case 1073762355: {
                sraw(rd, rs1, rs2);
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace32$instructionGroup25(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rd = ((inst >>> 7) & 0x1f);
        final int rm = ((inst >>> 12) & 0x7);
        final int rs1 = ((inst >>> 15) & 0x1f);
        final int rs2 = ((inst >>> 20) & 0x1f);
        final int rs3 = ((inst >>> 27) & 0x1f);
        if ((inst & 0x6000000) == 0x0) {
            fmadd_s(rd, rs1, rs2, rs3, rm);
            return;
        }
        if ((inst & 0x6000000) == 0x2000000) {
            fmadd_d(rd, rs1, rs2, rs3, rm);
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace32$instructionGroup26(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rd = ((inst >>> 7) & 0x1f);
        final int rm = ((inst >>> 12) & 0x7);
        final int rs1 = ((inst >>> 15) & 0x1f);
        final int rs2 = ((inst >>> 20) & 0x1f);
        final int rs3 = ((inst >>> 27) & 0x1f);
        if ((inst & 0x6000000) == 0x0) {
            fmsub_s(rd, rs1, rs2, rs3, rm);
            return;
        }
        if ((inst & 0x6000000) == 0x2000000) {
            FMSUB_D(rd, rs1, rs2, rs3, rm);
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace32$instructionGroup27(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rd = ((inst >>> 7) & 0x1f);
        final int rm = ((inst >>> 12) & 0x7);
        final int rs1 = ((inst >>> 15) & 0x1f);
        final int rs2 = ((inst >>> 20) & 0x1f);
        final int rs3 = ((inst >>> 27) & 0x1f);
        if ((inst & 0x6000000) == 0x0) {
            fnmsub_s(rd, rs1, rs2, rs3, rm);
            return;
        }
        if ((inst & 0x6000000) == 0x2000000) {
            fnmsub_d(rd, rs1, rs2, rs3, rm);
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace32$instructionGroup28(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rd = ((inst >>> 7) & 0x1f);
        final int rm = ((inst >>> 12) & 0x7);
        final int rs1 = ((inst >>> 15) & 0x1f);
        final int rs2 = ((inst >>> 20) & 0x1f);
        final int rs3 = ((inst >>> 27) & 0x1f);
        if ((inst & 0x6000000) == 0x0) {
            fnmadd_s(rd, rs1, rs2, rs3, rm);
            return;
        }
        if ((inst & 0x6000000) == 0x2000000) {
            fnmadd_d(rd, rs1, rs2, rs3, rm);
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace32$instructionGroup29(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rd = ((inst >>> 7) & 0x1f);
        final int rs1 = ((inst >>> 15) & 0x1f);
        switch (inst & 0xfe00007f) {
            case 83: {
                fadd_s(rd, rs1, ((inst >>> 20) & 0x1f), ((inst >>> 12) & 0x7));
                return;
            }
            case 33554515: {
                fadd_d(rd, rs1, ((inst >>> 20) & 0x1f), ((inst >>> 12) & 0x7));
                return;
            }
            case 134217811: {
                fsub_s(rd, rs1, ((inst >>> 20) & 0x1f), ((inst >>> 12) & 0x7));
                return;
            }
            case 167772243: {
                fsub_d(rd, rs1, ((inst >>> 20) & 0x1f), ((inst >>> 12) & 0x7));
                return;
            }
            case 268435539: {
                fmul_s(rd, rs1, ((inst >>> 20) & 0x1f), ((inst >>> 12) & 0x7));
                return;
            }
            case 301989971: {
                fmul_d(rd, rs1, ((inst >>> 20) & 0x1f), ((inst >>> 12) & 0x7));
                return;
            }
            case 402653267: {
                fdiv_s(rd, rs1, ((inst >>> 20) & 0x1f), ((inst >>> 12) & 0x7));
                return;
            }
            case 436207699: {
                fdiv_d(rd, rs1, ((inst >>> 20) & 0x1f), ((inst >>> 12) & 0x7));
                return;
            }
            case 536870995: {
                interpretTrace32$instructionGroup30(inst, pc, rd, rs1);
                return;
            }
            case 570425427: {
                interpretTrace32$instructionGroup31(inst, pc, rd, rs1);
                return;
            }
            case 671088723: {
                interpretTrace32$instructionGroup32(inst, pc, rd, rs1);
                return;
            }
            case 704643155: {
                interpretTrace32$instructionGroup33(inst, pc, rd, rs1);
                return;
            }
            case 1073741907: {
                if ((inst & 0x1f00000) == 0x100000) {
                    fcvt_s_d(rd, rs1, ((inst >>> 12) & 0x7));
                    return;
                }
                throw illegalInstruction();
            }
            case 1107296339: {
                if ((inst & 0x1f00000) == 0x0) {
                    fcvt_d_s(rd, rs1, ((inst >>> 12) & 0x7));
                    return;
                }
                throw illegalInstruction();
            }
            case 1476395091: {
                if ((inst & 0x1f00000) == 0x0) {
                    fsqrt_s(rd, rs1, ((inst >>> 12) & 0x7));
                    return;
                }
                throw illegalInstruction();
            }
            case 1509949523: {
                if ((inst & 0x1f00000) == 0x0) {
                    fsqrt_d(rd, rs1, ((inst >>> 12) & 0x7));
                    return;
                }
                throw illegalInstruction();
            }
            case -1610612653: {
                interpretTrace32$instructionGroup34(inst, pc, rd, rs1);
                return;
            }
            case -1577058221: {
                interpretTrace32$instructionGroup35(inst, pc, rd, rs1);
                return;
            }
            case -1073741741: {
                interpretTrace32$instructionGroup36(inst, pc, rd, rs1);
                return;
            }
            case -1040187309: {
                interpretTrace32$instructionGroup37(inst, pc, rd, rs1);
                return;
            }
            case -805306285: {
                interpretTrace32$instructionGroup38(inst, pc, rd, rs1);
                return;
            }
            case -771751853: {
                interpretTrace32$instructionGroup39(inst, pc, rd, rs1);
                return;
            }
            case -536870829: {
                interpretTrace32$instructionGroup40(inst, pc, rd, rs1);
                return;
            }
            case -503316397: {
                if ((inst & 0x1f07000) == 0x1000) {
                    fclass_d(rd, rs1);
                    return;
                }
                throw illegalInstruction();
            }
            case -268435373: {
                if ((inst & 0x1f07000) == 0x0) {
                    fmv_w_x(rd, rs1);
                    return;
                }
                throw illegalInstruction();
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace32$instructionGroup30(final int inst, final long pc, final int arg0, final int arg1) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rs2 = ((inst >>> 20) & 0x1f);
        switch (inst & 0xfe00707f) {
            case 536870995: {
                fsgnj_s(arg0, arg1, rs2);
                return;
            }
            case 536875091: {
                fsgnjn_s(arg0, arg1, rs2);
                return;
            }
            case 536879187: {
                fsgnjx_s(arg0, arg1, rs2);
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace32$instructionGroup31(final int inst, final long pc, final int arg0, final int arg1) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rs2 = ((inst >>> 20) & 0x1f);
        switch (inst & 0xfe00707f) {
            case 570425427: {
                fsgnj_d(arg0, arg1, rs2);
                return;
            }
            case 570429523: {
                fsgnjn_d(arg0, arg1, rs2);
                return;
            }
            case 570433619: {
                fsgnjx_d(arg0, arg1, rs2);
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace32$instructionGroup32(final int inst, final long pc, final int arg0, final int arg1) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rs2 = ((inst >>> 20) & 0x1f);
        if ((inst & 0x7000) == 0x0) {
            fmin_s(arg0, arg1, rs2);
            return;
        }
        if ((inst & 0x7000) == 0x1000) {
            fmax_s(arg0, arg1, rs2);
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace32$instructionGroup33(final int inst, final long pc, final int arg0, final int arg1) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rs2 = ((inst >>> 20) & 0x1f);
        if ((inst & 0x7000) == 0x0) {
            fmin_d(arg0, arg1, rs2);
            return;
        }
        if ((inst & 0x7000) == 0x1000) {
            fmax_d(arg0, arg1, rs2);
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace32$instructionGroup34(final int inst, final long pc, final int arg0, final int arg1) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rs2 = ((inst >>> 20) & 0x1f);
        switch (inst & 0xfe00707f) {
            case -1610612653: {
                fle_s(arg0, arg1, rs2);
                return;
            }
            case -1610608557: {
                flt_s(arg0, arg1, rs2);
                return;
            }
            case -1610604461: {
                feq_s(arg0, arg1, rs2);
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace32$instructionGroup35(final int inst, final long pc, final int arg0, final int arg1) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rs2 = ((inst >>> 20) & 0x1f);
        switch (inst & 0xfe00707f) {
            case -1577058221: {
                fle_d(arg0, arg1, rs2);
                return;
            }
            case -1577054125: {
                flt_d(arg0, arg1, rs2);
                return;
            }
            case -1577050029: {
                feq_d(arg0, arg1, rs2);
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace32$instructionGroup36(final int inst, final long pc, final int arg0, final int arg1) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rm = ((inst >>> 12) & 0x7);
        if ((inst & 0x1f00000) == 0x0) {
            fcvt_w_s(arg0, arg1, rm);
            return;
        }
        if ((inst & 0x1f00000) == 0x100000) {
            fcvt_wu_s(arg0, arg1, rm);
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace32$instructionGroup37(final int inst, final long pc, final int arg0, final int arg1) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rm = ((inst >>> 12) & 0x7);
        if ((inst & 0x1f00000) == 0x0) {
            fcvt_w_d(arg0, arg1, rm);
            return;
        }
        if ((inst & 0x1f00000) == 0x100000) {
            fcvt_wu_d(arg0, arg1, rm);
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace32$instructionGroup38(final int inst, final long pc, final int arg0, final int arg1) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rm = ((inst >>> 12) & 0x7);
        if ((inst & 0x1f00000) == 0x0) {
            fcvt_s_w(arg0, arg1, rm);
            return;
        }
        if ((inst & 0x1f00000) == 0x100000) {
            fcvt_s_wu(arg0, arg1, rm);
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace32$instructionGroup39(final int inst, final long pc, final int arg0, final int arg1) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rm = ((inst >>> 12) & 0x7);
        if ((inst & 0x1f00000) == 0x0) {
            fcvt_d_w(arg0, arg1, rm);
            return;
        }
        if ((inst & 0x1f00000) == 0x100000) {
            fcvt_d_wu(arg0, arg1, rm);
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace32$instructionGroup40(final int inst, final long pc, final int arg0, final int arg1) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        if ((inst & 0x1f07000) == 0x0) {
            fmv_x_w(arg0, arg1);
            return;
        }
        if ((inst & 0x1f07000) == 0x1000) {
            fclass_s(arg0, arg1);
            return;
        }
        throw illegalInstruction();
    }

    private int interpretTrace32$instructionGroup41(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int imm = ((inst << 4) & 0x800) | ((inst >>> 7) & 0x1e) | ((inst >>> 20) & 0x7e0) | BitUtils.extendSign(((inst >>> 19) & 0x1000), 13);
        final int rs1 = ((inst >>> 15) & 0x1f);
        final int rs2 = ((inst >>> 20) & 0x1f);
        switch (((inst & 0x7000) >>> 12)) {
            case 0: {
                if (beq(rs1, rs2, imm, pc)) {
                    return 4;
                }
                return 0;
            }
            case 1: {
                if (bne(rs1, rs2, imm, pc)) {
                    return 4;
                }
                return 0;
            }
            case 4: {
                if (blt(rs1, rs2, imm, pc)) {
                    return 4;
                }
                return 0;
            }
            case 5: {
                if (bge(rs1, rs2, imm, pc)) {
                    return 4;
                }
                return 0;
            }
            case 6: {
                if (bltu(rs1, rs2, imm, pc)) {
                    return 4;
                }
                return 0;
            }
            case 7: {
                if (bgeu(rs1, rs2, imm, pc)) {
                    return 4;
                }
                return 0;
            }
            default:
                throw illegalInstruction();
        }
    }

    private int interpretTrace32$instructionGroup42(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        switch (((inst & 0x7000) >>> 12)) {
            case 0: {
                return interpretTrace32$instructionGroup43(inst, pc);
            }
            case 1: {
                if (csrrw(((inst >>> 7) & 0x1f), ((inst >>> 15) & 0x1f), ((inst >>> 20) & 0xfff))) {
                    return 1;
                }
                return 0;
            }
            case 2: {
                if (csrrs(((inst >>> 7) & 0x1f), ((inst >>> 15) & 0x1f), ((inst >>> 20) & 0xfff))) {
                    return 1;
                }
                return 0;
            }
            case 3: {
                if (csrrc(((inst >>> 7) & 0x1f), ((inst >>> 15) & 0x1f), ((inst >>> 20) & 0xfff))) {
                    return 1;
                }
                return 0;
            }
            case 5: {
                if (csrrwi(((inst >>> 7) & 0x1f), ((inst >>> 15) & 0x1f), ((inst >>> 20) & 0xfff))) {
                    return 1;
                }
                return 0;
            }
            case 6: {
                if (csrrsi(((inst >>> 7) & 0x1f), ((inst >>> 15) & 0x1f), ((inst >>> 20) & 0xfff))) {
                    return 1;
                }
                return 0;
            }
            case 7: {
                if (csrrci(((inst >>> 7) & 0x1f), ((inst >>> 15) & 0x1f), ((inst >>> 20) & 0xfff))) {
                    return 1;
                }
                return 0;
            }
            default:
                throw illegalInstruction();
        }
    }

    private int interpretTrace32$instructionGroup43(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        if ((inst & 0xf80) != 0x0) {
            throw illegalInstruction();
        }
        switch (inst & 0xfe007fff) {
            case 115: {
                return interpretTrace32$instructionGroup44(inst, pc);
            }
            case 268435571: {
                return interpretTrace32$instructionGroup45(inst, pc);
            }
            case 301990003: {
                if (sfence_vma(((inst >>> 15) & 0x1f), ((inst >>> 20) & 0x1f))) {
                    return 1;
                }
                return 0;
            }
            case 805306483: {
                if ((inst & 0x1ff8000) == 0x200000) {
                    if (mret()) {
                        return 2;
                    }
                    return 0;
                }
                throw illegalInstruction();
            }
            default:
                throw illegalInstruction();
        }
    }

    private int interpretTrace32$instructionGroup44(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        if ((inst & 0x1ff8000) == 0x0) {
            ecall(pc);
            return 3;
        }
        if ((inst & 0x1ff8000) == 0x100000) {
            ebreak(pc);
            return 3;
        }
        throw illegalInstruction();
    }

    private int interpretTrace32$instructionGroup45(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        if ((inst & 0x1ff8000) == 0x200000) {
            if (sret()) {
                return 2;
            }
            return 0;
        }
        if ((inst & 0x1ff8000) == 0x500000) {
            if (wfi()) {
                return 1;
            }
            return 0;
        }
        throw illegalInstruction();
    }

    @Override
    protected void interpretTrace64(final MemoryMappedDevice device, final long hostBase, int inst, long pc, int instOffset, final int instEnd, final LongSet breakpoints) {
        try { // Catch any exceptions to patch PC field.
            for (; ; ) { // End of page check at the bottom since we enter with a valid inst.
                if (breakpoints != null && breakpoints.contains(pc)) {
                    this.pc = pc;
                    debugInterface.handleBreakpoint(pc);
                    return;
                }
                mcycle++;
                minstret++;

                decode: {
                    switch (inst & 0x3) {
                        case 0: {
                            interpretTrace64$instructionGroup0(inst, pc);
                            pc += 2;
                            instOffset += 2;
                            break decode;
                        }
                        case 1: {
                            switch (interpretTrace64$instructionGroup2(inst, pc)) {
                                case 0 -> {
                                    pc += 2;
                                    instOffset += 2;
                                    break decode;
                                }
                                case 1 -> {
                                    pc += 2;
                                    instOffset += 2;
                                    this.pc = pc;
                                    return;
                                }
                                case 2 -> {
                                    return;
                                }
                                case 3 -> {
                                    final long jumpTarget = this.pc;
                                    if (Long.compareUnsigned(pc, jumpTarget) >= 0) {
                                        return;
                                    }
                                    final long jumpDelta = jumpTarget - pc;
                                    pc = jumpTarget;
                                    if ((long) (int) jumpDelta != jumpDelta) {
                                        return;
                                    }
                                    instOffset += (int) jumpDelta;
                                    break decode;
                                }
                                case 4 -> {
                                    final long jumpTarget = this.pc;
                                    if (Long.compareUnsigned(pc, jumpTarget) >= 0) {
                                        if (mcycle >= cycleLimit || ((jumpTarget ^ pc) & ~(long) R5.PAGE_ADDRESS_MASK) != 0) {
                                            return;
                                        }
                                    }
                                    final long jumpDelta = jumpTarget - pc;
                                    pc = jumpTarget;
                                    if ((long) (int) jumpDelta != jumpDelta) {
                                        return;
                                    }
                                    instOffset += (int) jumpDelta;
                                    break decode;
                                }
                                default -> throw illegalInstruction();
                            }
                        }
                        case 2: {
                            switch (interpretTrace64$instructionGroup9(inst, pc)) {
                                case 0 -> {
                                    pc += 2;
                                    instOffset += 2;
                                    break decode;
                                }
                                case 1 -> {
                                    pc += 2;
                                    instOffset += 2;
                                    this.pc = pc;
                                    return;
                                }
                                case 2 -> {
                                    return;
                                }
                                case 3 -> {
                                    final long jumpTarget = this.pc;
                                    if (Long.compareUnsigned(pc, jumpTarget) >= 0) {
                                        return;
                                    }
                                    final long jumpDelta = jumpTarget - pc;
                                    pc = jumpTarget;
                                    if ((long) (int) jumpDelta != jumpDelta) {
                                        return;
                                    }
                                    instOffset += (int) jumpDelta;
                                    break decode;
                                }
                                case 4 -> {
                                    final long jumpTarget = this.pc;
                                    if (Long.compareUnsigned(pc, jumpTarget) >= 0) {
                                        if (mcycle >= cycleLimit || ((jumpTarget ^ pc) & ~(long) R5.PAGE_ADDRESS_MASK) != 0) {
                                            return;
                                        }
                                    }
                                    final long jumpDelta = jumpTarget - pc;
                                    pc = jumpTarget;
                                    if ((long) (int) jumpDelta != jumpDelta) {
                                        return;
                                    }
                                    instOffset += (int) jumpDelta;
                                    break decode;
                                }
                                default -> throw illegalInstruction();
                            }
                        }
                        case 3: {
                            switch (interpretTrace64$instructionGroup16(inst, pc)) {
                                case 0 -> {
                                    pc += 4;
                                    instOffset += 4;
                                    break decode;
                                }
                                case 1 -> {
                                    pc += 4;
                                    instOffset += 4;
                                    this.pc = pc;
                                    return;
                                }
                                case 2 -> {
                                    return;
                                }
                                case 3 -> {
                                    final long jumpTarget = this.pc;
                                    if (Long.compareUnsigned(pc, jumpTarget) >= 0) {
                                        return;
                                    }
                                    final long jumpDelta = jumpTarget - pc;
                                    pc = jumpTarget;
                                    if ((long) (int) jumpDelta != jumpDelta) {
                                        return;
                                    }
                                    instOffset += (int) jumpDelta;
                                    break decode;
                                }
                                case 4 -> {
                                    final long jumpTarget = this.pc;
                                    if (Long.compareUnsigned(pc, jumpTarget) >= 0) {
                                        if (mcycle >= cycleLimit || ((jumpTarget ^ pc) & ~(long) R5.PAGE_ADDRESS_MASK) != 0) {
                                            return;
                                        }
                                    }
                                    final long jumpDelta = jumpTarget - pc;
                                    pc = jumpTarget;
                                    if ((long) (int) jumpDelta != jumpDelta) {
                                        return;
                                    }
                                    instOffset += (int) jumpDelta;
                                    break decode;
                                }
                                default -> throw illegalInstruction();
                            }
                        }
                        default:
                            throw illegalInstruction();
                    }
                }

                if (Integer.compareUnsigned(instOffset, instEnd) < 0) { // Likely case: we're still fully in the page.
                    inst = hostBase != 0 ? UNSAFE.getInt(hostBase + instOffset) : (int) device.load(instOffset, Sizes.SIZE_32_LOG2);
                } else { // Unlikely case: we reached the end of the page. Leave to do interrupts and cycle check.
                    this.pc = pc;
                    return;
                }
            }
        } catch (final MemoryAccessException e) {
            this.pc = pc;
            raiseException(R5.EXCEPTION_FAULT_FETCH, pc);
        } catch (final R5IllegalInstructionException e) {
            this.pc = pc;
            raiseException(R5.EXCEPTION_ILLEGAL_INSTRUCTION, inst);
        } catch (final R5MemoryAccessException e) {
            this.pc = pc;
            raiseException(e.getType(), e.getAddress());
        }
    }

    private void interpretTrace64$instructionGroup0(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException, li.cil.sedna.riscv.exception.R5MemoryAccessException {
        switch (((inst & 0xe000) >>> 13)) {
            case 0: {
                interpretTrace64$instructionGroup1(inst, pc);
                return;
            }
            case 1: {
                fld((((inst >>> 2) & 0x7)) + 8, (((inst >>> 7) & 0x7)) + 8, ((inst << 1) & 0xc0) | ((inst >>> 7) & 0x38));
                return;
            }
            case 2: {
                lw((((inst >>> 2) & 0x7)) + 8, (((inst >>> 7) & 0x7)) + 8, ((inst << 1) & 0x40) | ((inst >>> 4) & 0x4) | ((inst >>> 7) & 0x38));
                return;
            }
            case 3: {
                ld((((inst >>> 2) & 0x7)) + 8, (((inst >>> 7) & 0x7)) + 8, ((inst << 1) & 0xc0) | ((inst >>> 7) & 0x38));
                return;
            }
            case 5: {
                fsd((((inst >>> 7) & 0x7)) + 8, (((inst >>> 2) & 0x7)) + 8, ((inst << 1) & 0xc0) | ((inst >>> 7) & 0x38));
                return;
            }
            case 6: {
                sw((((inst >>> 7) & 0x7)) + 8, (((inst >>> 2) & 0x7)) + 8, ((inst << 1) & 0x40) | ((inst >>> 4) & 0x4) | ((inst >>> 7) & 0x38));
                return;
            }
            case 7: {
                sd((((inst >>> 7) & 0x7)) + 8, (((inst >>> 2) & 0x7)) + 8, ((inst << 1) & 0xc0) | ((inst >>> 7) & 0x38));
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace64$instructionGroup1(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        if ((inst & 0x1ffc) == 0x0) {
            throw illegalInstruction();
        }
        if ((inst & 0x1fe0) == 0x0) {
            throw illegalInstruction();
        }
        addi((((inst >>> 2) & 0x7)) + 8, 2, ((inst >>> 2) & 0x8) | ((inst >>> 4) & 0x4) | ((inst >>> 1) & 0x3c0) | ((inst >>> 7) & 0x30));
        return;
    }

    private int interpretTrace64$instructionGroup2(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        switch (((inst & 0xe000) >>> 13)) {
            case 0: {
                interpretTrace64$instructionGroup3(inst, pc);
                return 0;
            }
            case 1: {
                addiw(((inst >>> 7) & 0x1f), ((inst >>> 7) & 0x1f), ((inst >>> 2) & 0x1f) | BitUtils.extendSign(((inst >>> 7) & 0x20), 6));
                return 0;
            }
            case 2: {
                interpretTrace64$instructionGroup4(inst, pc);
                return 0;
            }
            case 3: {
                interpretTrace64$instructionGroup5(inst, pc);
                return 0;
            }
            case 4: {
                interpretTrace64$instructionGroup6(inst, pc);
                return 0;
            }
            case 5: {
                jal(0, ((inst << 3) & 0x20) | ((inst >>> 2) & 0xe) | ((inst << 1) & 0x80) | ((inst >>> 1) & 0x40) | ((inst << 2) & 0x400) | ((inst >>> 1) & 0x300) | ((inst >>> 7) & 0x10) | BitUtils.extendSign(((inst >>> 1) & 0x800), 12), pc, 2);
                return 4;
            }
            case 6: {
                if (beq((((inst >>> 7) & 0x7)) + 8, 0, ((inst << 3) & 0x20) | ((inst >>> 2) & 0x6) | ((inst << 1) & 0xc0) | ((inst >>> 7) & 0x18) | BitUtils.extendSign(((inst >>> 4) & 0x100), 9), pc)) {
                    return 4;
                }
                return 0;
            }
            case 7: {
                if (bne((((inst >>> 7) & 0x7)) + 8, 0, ((inst << 3) & 0x20) | ((inst >>> 2) & 0x6) | ((inst << 1) & 0xc0) | ((inst >>> 7) & 0x18) | BitUtils.extendSign(((inst >>> 4) & 0x100), 9), pc)) {
                    return 4;
                }
                return 0;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace64$instructionGroup3(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        if ((inst & 0x1ffc) == 0x0) {
            return;
        }
        if ((inst & 0x107c) == 0x0) {
            return;
        }
        if ((inst & 0xf80) == 0x0) {
            return;
        }
        addi(((inst >>> 7) & 0x1f), ((inst >>> 7) & 0x1f), ((inst >>> 2) & 0x1f) | BitUtils.extendSign(((inst >>> 7) & 0x20), 6));
        return;
    }

    private void interpretTrace64$instructionGroup4(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        if ((inst & 0xf80) == 0x0) {
            return;
        }
        addi(((inst >>> 7) & 0x1f), 0, ((inst >>> 2) & 0x1f) | BitUtils.extendSign(((inst >>> 7) & 0x20), 6));
        return;
    }

    private void interpretTrace64$instructionGroup5(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        if ((inst & 0x1ffc) == 0x100) {
            throw illegalInstruction();
        }
        if ((inst & 0xf80) == 0x100) {
            addi(2, 2, ((inst << 3) & 0x20) | ((inst << 4) & 0x180) | ((inst << 1) & 0x40) | ((inst >>> 2) & 0x10) | BitUtils.extendSign(((inst >>> 3) & 0x200), 10));
            return;
        }
        if ((inst & 0xf80) == 0x0) {
            return;
        }
        lui(((inst >>> 7) & 0x1f), ((inst << 10) & 0x1f000) | BitUtils.extendSign(((inst << 5) & 0x20000), 18));
        return;
    }

    private void interpretTrace64$instructionGroup6(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rd_rs1 = (((inst >>> 7) & 0x7)) + 8;
        switch (((inst & 0xc00) >>> 10)) {
            case 0: {
                interpretTrace64$instructionGroup7(inst, pc, rd_rs1);
                return;
            }
            case 1: {
                srai(rd_rs1, rd_rs1, ((inst >>> 2) & 0x1f) | ((inst >>> 7) & 0x20));
                return;
            }
            case 2: {
                andi(rd_rs1, rd_rs1, ((inst >>> 2) & 0x1f) | BitUtils.extendSign(((inst >>> 7) & 0x20), 6));
                return;
            }
            case 3: {
                interpretTrace64$instructionGroup8(inst, pc, rd_rs1);
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace64$instructionGroup7(final int inst, final long pc, final int arg0) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        if ((inst & 0x107c) == 0x0) {
            return;
        }
        srli(arg0, arg0, ((inst >>> 2) & 0x1f) | ((inst >>> 7) & 0x20));
        return;
    }

    private void interpretTrace64$instructionGroup8(final int inst, final long pc, final int arg0) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rs2 = (((inst >>> 2) & 0x7)) + 8;
        switch (((inst & 0x60) >>> 5) | (((inst & 0x1000) >>> 12) << 2)) {
            case 0: {
                sub(arg0, arg0, rs2);
                return;
            }
            case 1: {
                xor(arg0, arg0, rs2);
                return;
            }
            case 2: {
                or(arg0, arg0, rs2);
                return;
            }
            case 3: {
                and(arg0, arg0, rs2);
                return;
            }
            case 4: {
                subw(arg0, arg0, rs2);
                return;
            }
            case 5: {
                addw(arg0, arg0, rs2);
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private int interpretTrace64$instructionGroup9(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException, li.cil.sedna.riscv.exception.R5MemoryAccessException {
        switch (((inst & 0xe000) >>> 13)) {
            case 0: {
                interpretTrace64$instructionGroup10(inst, pc);
                return 0;
            }
            case 1: {
                fld(((inst >>> 7) & 0x1f), 2, ((inst << 4) & 0x1c0) | ((inst >>> 2) & 0x18) | ((inst >>> 7) & 0x20));
                return 0;
            }
            case 2: {
                interpretTrace64$instructionGroup11(inst, pc);
                return 0;
            }
            case 3: {
                interpretTrace64$instructionGroup12(inst, pc);
                return 0;
            }
            case 4: {
                return interpretTrace64$instructionGroup13(inst, pc);
            }
            case 5: {
                fsd(2, ((inst >>> 2) & 0x1f), ((inst >>> 1) & 0x1c0) | ((inst >>> 7) & 0x38));
                return 0;
            }
            case 6: {
                sw(2, ((inst >>> 2) & 0x1f), ((inst >>> 1) & 0xc0) | ((inst >>> 7) & 0x3c));
                return 0;
            }
            case 7: {
                sd(2, ((inst >>> 2) & 0x1f), ((inst >>> 1) & 0x1c0) | ((inst >>> 7) & 0x38));
                return 0;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace64$instructionGroup10(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        if ((inst & 0xf80) == 0x0) {
            return;
        }
        slli(((inst >>> 7) & 0x1f), ((inst >>> 7) & 0x1f), ((inst >>> 2) & 0x1f) | ((inst >>> 7) & 0x20));
        return;
    }

    private void interpretTrace64$instructionGroup11(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException, li.cil.sedna.riscv.exception.R5MemoryAccessException {
        if ((inst & 0xf80) == 0x0) {
            throw illegalInstruction();
        }
        lw(((inst >>> 7) & 0x1f), 2, ((inst << 4) & 0xc0) | ((inst >>> 2) & 0x1c) | ((inst >>> 7) & 0x20));
        return;
    }

    private void interpretTrace64$instructionGroup12(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException, li.cil.sedna.riscv.exception.R5MemoryAccessException {
        if ((inst & 0xf80) == 0x0) {
            throw illegalInstruction();
        }
        ld(((inst >>> 7) & 0x1f), 2, ((inst << 4) & 0x1c0) | ((inst >>> 2) & 0x18) | ((inst >>> 7) & 0x20));
        return;
    }

    private int interpretTrace64$instructionGroup13(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        if ((inst & 0x1000) == 0x0) {
            return interpretTrace64$instructionGroup14(inst, pc);
        }
        if ((inst & 0x1000) == 0x1000) {
            return interpretTrace64$instructionGroup15(inst, pc);
        }
        throw illegalInstruction();
    }

    private int interpretTrace64$instructionGroup14(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        if ((inst & 0xffc) == 0x0) {
            throw illegalInstruction();
        }
        if ((inst & 0x7c) == 0x0) {
            jalr(0, ((inst >>> 7) & 0x1f), 0, pc, 2);
            return 4;
        }
        if ((inst & 0xf80) == 0x0) {
            return 0;
        }
        add(((inst >>> 7) & 0x1f), 0, ((inst >>> 2) & 0x1f));
        return 0;
    }

    private int interpretTrace64$instructionGroup15(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        if ((inst & 0xffc) == 0x0) {
            ebreak(pc);
            return 3;
        }
        if ((inst & 0x7c) == 0x0) {
            jalr(1, ((inst >>> 7) & 0x1f), 0, pc, 2);
            return 4;
        }
        if ((inst & 0xf80) == 0x0) {
            return 0;
        }
        add(((inst >>> 7) & 0x1f), ((inst >>> 7) & 0x1f), ((inst >>> 2) & 0x1f));
        return 0;
    }

    private int interpretTrace64$instructionGroup16(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException, li.cil.sedna.riscv.exception.R5MemoryAccessException {
        switch (((inst & 0x7c) >>> 2)) {
            case 0: {
                interpretTrace64$instructionGroup17(inst, pc);
                return 0;
            }
            case 1: {
                interpretTrace64$instructionGroup18(inst, pc);
                return 0;
            }
            case 3: {
                interpretTrace64$instructionGroup19(inst, pc);
                return 0;
            }
            case 4: {
                interpretTrace64$instructionGroup20(inst, pc);
                return 0;
            }
            case 5: {
                auipc(((inst >>> 7) & 0x1f), BitUtils.extendSign((inst & 0xfffff000), 32), pc);
                return 0;
            }
            case 6: {
                interpretTrace64$instructionGroup22(inst, pc);
                return 0;
            }
            case 8: {
                interpretTrace64$instructionGroup24(inst, pc);
                return 0;
            }
            case 9: {
                interpretTrace64$instructionGroup25(inst, pc);
                return 0;
            }
            case 11: {
                interpretTrace64$instructionGroup26(inst, pc);
                return 0;
            }
            case 12: {
                interpretTrace64$instructionGroup27(inst, pc);
                return 0;
            }
            case 13: {
                lui(((inst >>> 7) & 0x1f), BitUtils.extendSign((inst & 0xfffff000), 32));
                return 0;
            }
            case 14: {
                interpretTrace64$instructionGroup28(inst, pc);
                return 0;
            }
            case 16: {
                interpretTrace64$instructionGroup29(inst, pc);
                return 0;
            }
            case 17: {
                interpretTrace64$instructionGroup30(inst, pc);
                return 0;
            }
            case 18: {
                interpretTrace64$instructionGroup31(inst, pc);
                return 0;
            }
            case 19: {
                interpretTrace64$instructionGroup32(inst, pc);
                return 0;
            }
            case 20: {
                interpretTrace64$instructionGroup33(inst, pc);
                return 0;
            }
            case 24: {
                return interpretTrace64$instructionGroup46(inst, pc);
            }
            case 25: {
                if ((inst & 0x7000) == 0x0) {
                    jalr(((inst >>> 7) & 0x1f), ((inst >>> 15) & 0x1f), BitUtils.extendSign(((inst >>> 20) & 0xfff), 12), pc, 4);
                    return 4;
                }
                throw illegalInstruction();
            }
            case 27: {
                jal(((inst >>> 7) & 0x1f), (inst & 0xff000) | ((inst >>> 9) & 0x800) | ((inst >>> 20) & 0x7fe) | BitUtils.extendSign(((inst >>> 11) & 0x100000), 21), pc, 4);
                return 4;
            }
            case 28: {
                return interpretTrace64$instructionGroup47(inst, pc);
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace64$instructionGroup17(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException, li.cil.sedna.riscv.exception.R5MemoryAccessException {
        final int imm = BitUtils.extendSign(((inst >>> 20) & 0xfff), 12);
        final int rd = ((inst >>> 7) & 0x1f);
        final int rs1 = ((inst >>> 15) & 0x1f);
        switch (((inst & 0x7000) >>> 12)) {
            case 0: {
                lb(rd, rs1, imm);
                return;
            }
            case 1: {
                lh(rd, rs1, imm);
                return;
            }
            case 2: {
                lw(rd, rs1, imm);
                return;
            }
            case 3: {
                ld(rd, rs1, imm);
                return;
            }
            case 4: {
                lbu(rd, rs1, imm);
                return;
            }
            case 5: {
                lhu(rd, rs1, imm);
                return;
            }
            case 6: {
                lwu(rd, rs1, imm);
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace64$instructionGroup18(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException, li.cil.sedna.riscv.exception.R5MemoryAccessException {
        final int imm = BitUtils.extendSign(((inst >>> 20) & 0xfff), 12);
        final int rd = ((inst >>> 7) & 0x1f);
        final int rs1 = ((inst >>> 15) & 0x1f);
        if ((inst & 0x7000) == 0x2000) {
            flw(rd, rs1, imm);
            return;
        }
        if ((inst & 0x7000) == 0x3000) {
            fld(rd, rs1, imm);
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace64$instructionGroup19(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        if ((inst & 0x7000) == 0x0) {
            fence();
            return;
        }
        if ((inst & 0x7000) == 0x1000) {
            fence_i();
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace64$instructionGroup20(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rd = ((inst >>> 7) & 0x1f);
        final int rs1 = ((inst >>> 15) & 0x1f);
        switch (((inst & 0x7000) >>> 12)) {
            case 0: {
                addi(rd, rs1, BitUtils.extendSign(((inst >>> 20) & 0xfff), 12));
                return;
            }
            case 1: {
                if ((inst & 0xfc000000) == 0x0) {
                    slli(rd, rs1, ((inst >>> 20) & 0x3f));
                    return;
                }
                throw illegalInstruction();
            }
            case 2: {
                slti(rd, rs1, BitUtils.extendSign(((inst >>> 20) & 0xfff), 12));
                return;
            }
            case 3: {
                sltiu(rd, rs1, BitUtils.extendSign(((inst >>> 20) & 0xfff), 12));
                return;
            }
            case 4: {
                xori(rd, rs1, BitUtils.extendSign(((inst >>> 20) & 0xfff), 12));
                return;
            }
            case 5: {
                interpretTrace64$instructionGroup21(inst, pc, rd, rs1);
                return;
            }
            case 6: {
                ori(rd, rs1, BitUtils.extendSign(((inst >>> 20) & 0xfff), 12));
                return;
            }
            case 7: {
                andi(rd, rs1, BitUtils.extendSign(((inst >>> 20) & 0xfff), 12));
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace64$instructionGroup21(final int inst, final long pc, final int arg0, final int arg1) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int shamt = ((inst >>> 20) & 0x3f);
        if ((inst & 0xfc000000) == 0x0) {
            srli(arg0, arg1, shamt);
            return;
        }
        if ((inst & 0xfc000000) == 0x40000000) {
            srai(arg0, arg1, shamt);
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace64$instructionGroup22(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rd = ((inst >>> 7) & 0x1f);
        final int rs1 = ((inst >>> 15) & 0x1f);
        final int shamt = ((inst >>> 20) & 0x1f);
        switch (inst & 0x707f) {
            case 27: {
                addiw(rd, rs1, BitUtils.extendSign(((inst >>> 20) & 0xfff), 12));
                return;
            }
            case 4123: {
                if ((inst & 0xfe000000) == 0x0) {
                    slliw(rd, rs1, shamt);
                    return;
                }
                throw illegalInstruction();
            }
            case 20507: {
                interpretTrace64$instructionGroup23(inst, pc, rd, rs1, shamt);
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace64$instructionGroup23(final int inst, final long pc, final int arg0, final int arg1, final int arg2) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        if ((inst & 0xfe000000) == 0x0) {
            srliw(arg0, arg1, arg2);
            return;
        }
        if ((inst & 0xfe000000) == 0x40000000) {
            sraiw(arg0, arg1, arg2);
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace64$instructionGroup24(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException, li.cil.sedna.riscv.exception.R5MemoryAccessException {
        final int imm = ((inst >>> 7) & 0x1f) | BitUtils.extendSign(((inst >>> 20) & 0xfe0), 12);
        final int rs1 = ((inst >>> 15) & 0x1f);
        final int rs2 = ((inst >>> 20) & 0x1f);
        switch (((inst & 0x7000) >>> 12)) {
            case 0: {
                sb(rs1, rs2, imm);
                return;
            }
            case 1: {
                sh(rs1, rs2, imm);
                return;
            }
            case 2: {
                sw(rs1, rs2, imm);
                return;
            }
            case 3: {
                sd(rs1, rs2, imm);
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace64$instructionGroup25(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException, li.cil.sedna.riscv.exception.R5MemoryAccessException {
        final int imm = ((inst >>> 7) & 0x1f) | BitUtils.extendSign(((inst >>> 20) & 0xfe0), 12);
        final int rs1 = ((inst >>> 15) & 0x1f);
        final int rs2 = ((inst >>> 20) & 0x1f);
        if ((inst & 0x7000) == 0x2000) {
            fsw(rs1, rs2, imm);
            return;
        }
        if ((inst & 0x7000) == 0x3000) {
            fsd(rs1, rs2, imm);
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace64$instructionGroup26(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException, li.cil.sedna.riscv.exception.R5MemoryAccessException {
        final int rd = ((inst >>> 7) & 0x1f);
        final int rs1 = ((inst >>> 15) & 0x1f);
        switch (inst & 0xf800707f) {
            case 8239: {
                amoadd_w(rd, rs1, ((inst >>> 20) & 0x1f));
                return;
            }
            case 12335: {
                amoadd_d(rd, rs1, ((inst >>> 20) & 0x1f));
                return;
            }
            case 134225967: {
                amoswap_w(rd, rs1, ((inst >>> 20) & 0x1f));
                return;
            }
            case 134230063: {
                amoswap_d(rd, rs1, ((inst >>> 20) & 0x1f));
                return;
            }
            case 268443695: {
                if ((inst & 0x1f00000) == 0x0) {
                    lr_w(rd, rs1);
                    return;
                }
                throw illegalInstruction();
            }
            case 268447791: {
                if ((inst & 0x1f00000) == 0x0) {
                    lr_d(rd, rs1);
                    return;
                }
                throw illegalInstruction();
            }
            case 402661423: {
                sc_w(rd, rs1, ((inst >>> 20) & 0x1f));
                return;
            }
            case 402665519: {
                sc_d(rd, rs1, ((inst >>> 20) & 0x1f));
                return;
            }
            case 536879151: {
                amoxor_w(rd, rs1, ((inst >>> 20) & 0x1f));
                return;
            }
            case 536883247: {
                amoxor_d(rd, rs1, ((inst >>> 20) & 0x1f));
                return;
            }
            case 1073750063: {
                amoor_w(rd, rs1, ((inst >>> 20) & 0x1f));
                return;
            }
            case 1073754159: {
                amoor_d(rd, rs1, ((inst >>> 20) & 0x1f));
                return;
            }
            case 1610620975: {
                amoand_w(rd, rs1, ((inst >>> 20) & 0x1f));
                return;
            }
            case 1610625071: {
                amoand_d(rd, rs1, ((inst >>> 20) & 0x1f));
                return;
            }
            case -2147475409: {
                amomin_w(rd, rs1, ((inst >>> 20) & 0x1f));
                return;
            }
            case -2147471313: {
                amomin_d(rd, rs1, ((inst >>> 20) & 0x1f));
                return;
            }
            case -1610604497: {
                amomax_w(rd, rs1, ((inst >>> 20) & 0x1f));
                return;
            }
            case -1610600401: {
                amomax_d(rd, rs1, ((inst >>> 20) & 0x1f));
                return;
            }
            case -1073733585: {
                amominu_w(rd, rs1, ((inst >>> 20) & 0x1f));
                return;
            }
            case -1073729489: {
                amominu_d(rd, rs1, ((inst >>> 20) & 0x1f));
                return;
            }
            case -536862673: {
                amomaxu_w(rd, rs1, ((inst >>> 20) & 0x1f));
                return;
            }
            case -536858577: {
                amomaxu_d(rd, rs1, ((inst >>> 20) & 0x1f));
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace64$instructionGroup27(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rd = ((inst >>> 7) & 0x1f);
        final int rs1 = ((inst >>> 15) & 0x1f);
        final int rs2 = ((inst >>> 20) & 0x1f);
        switch (inst & 0xfe00707f) {
            case 51: {
                add(rd, rs1, rs2);
                return;
            }
            case 4147: {
                sll(rd, rs1, rs2);
                return;
            }
            case 8243: {
                slt(rd, rs1, rs2);
                return;
            }
            case 12339: {
                sltu(rd, rs1, rs2);
                return;
            }
            case 16435: {
                xor(rd, rs1, rs2);
                return;
            }
            case 20531: {
                srl(rd, rs1, rs2);
                return;
            }
            case 24627: {
                or(rd, rs1, rs2);
                return;
            }
            case 28723: {
                and(rd, rs1, rs2);
                return;
            }
            case 33554483: {
                mul(rd, rs1, rs2);
                return;
            }
            case 33558579: {
                mulh(rd, rs1, rs2);
                return;
            }
            case 33562675: {
                mulhsu(rd, rs1, rs2);
                return;
            }
            case 33566771: {
                mulhu(rd, rs1, rs2);
                return;
            }
            case 33570867: {
                div(rd, rs1, rs2);
                return;
            }
            case 33574963: {
                divu(rd, rs1, rs2);
                return;
            }
            case 33579059: {
                rem(rd, rs1, rs2);
                return;
            }
            case 33583155: {
                remu(rd, rs1, rs2);
                return;
            }
            case 1073741875: {
                sub(rd, rs1, rs2);
                return;
            }
            case 1073762355: {
                sra(rd, rs1, rs2);
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace64$instructionGroup28(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rd = ((inst >>> 7) & 0x1f);
        final int rs1 = ((inst >>> 15) & 0x1f);
        final int rs2 = ((inst >>> 20) & 0x1f);
        switch (inst & 0xfe00707f) {
            case 59: {
                addw(rd, rs1, rs2);
                return;
            }
            case 4155: {
                sllw(rd, rs1, rs2);
                return;
            }
            case 20539: {
                srlw(rd, rs1, rs2);
                return;
            }
            case 33554491: {
                mulw(rd, rs1, rs2);
                return;
            }
            case 33570875: {
                divw(rd, rs1, rs2);
                return;
            }
            case 33574971: {
                divuw(rd, rs1, rs2);
                return;
            }
            case 33579067: {
                remw(rd, rs1, rs2);
                return;
            }
            case 33583163: {
                remuw(rd, rs1, rs2);
                return;
            }
            case 1073741883: {
                subw(rd, rs1, rs2);
                return;
            }
            case 1073762363: {
                sraw(rd, rs1, rs2);
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace64$instructionGroup29(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rd = ((inst >>> 7) & 0x1f);
        final int rm = ((inst >>> 12) & 0x7);
        final int rs1 = ((inst >>> 15) & 0x1f);
        final int rs2 = ((inst >>> 20) & 0x1f);
        final int rs3 = ((inst >>> 27) & 0x1f);
        if ((inst & 0x6000000) == 0x0) {
            fmadd_s(rd, rs1, rs2, rs3, rm);
            return;
        }
        if ((inst & 0x6000000) == 0x2000000) {
            fmadd_d(rd, rs1, rs2, rs3, rm);
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace64$instructionGroup30(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rd = ((inst >>> 7) & 0x1f);
        final int rm = ((inst >>> 12) & 0x7);
        final int rs1 = ((inst >>> 15) & 0x1f);
        final int rs2 = ((inst >>> 20) & 0x1f);
        final int rs3 = ((inst >>> 27) & 0x1f);
        if ((inst & 0x6000000) == 0x0) {
            fmsub_s(rd, rs1, rs2, rs3, rm);
            return;
        }
        if ((inst & 0x6000000) == 0x2000000) {
            FMSUB_D(rd, rs1, rs2, rs3, rm);
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace64$instructionGroup31(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rd = ((inst >>> 7) & 0x1f);
        final int rm = ((inst >>> 12) & 0x7);
        final int rs1 = ((inst >>> 15) & 0x1f);
        final int rs2 = ((inst >>> 20) & 0x1f);
        final int rs3 = ((inst >>> 27) & 0x1f);
        if ((inst & 0x6000000) == 0x0) {
            fnmsub_s(rd, rs1, rs2, rs3, rm);
            return;
        }
        if ((inst & 0x6000000) == 0x2000000) {
            fnmsub_d(rd, rs1, rs2, rs3, rm);
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace64$instructionGroup32(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rd = ((inst >>> 7) & 0x1f);
        final int rm = ((inst >>> 12) & 0x7);
        final int rs1 = ((inst >>> 15) & 0x1f);
        final int rs2 = ((inst >>> 20) & 0x1f);
        final int rs3 = ((inst >>> 27) & 0x1f);
        if ((inst & 0x6000000) == 0x0) {
            fnmadd_s(rd, rs1, rs2, rs3, rm);
            return;
        }
        if ((inst & 0x6000000) == 0x2000000) {
            fnmadd_d(rd, rs1, rs2, rs3, rm);
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace64$instructionGroup33(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rd = ((inst >>> 7) & 0x1f);
        final int rs1 = ((inst >>> 15) & 0x1f);
        switch (inst & 0xfe00007f) {
            case 83: {
                fadd_s(rd, rs1, ((inst >>> 20) & 0x1f), ((inst >>> 12) & 0x7));
                return;
            }
            case 33554515: {
                fadd_d(rd, rs1, ((inst >>> 20) & 0x1f), ((inst >>> 12) & 0x7));
                return;
            }
            case 134217811: {
                fsub_s(rd, rs1, ((inst >>> 20) & 0x1f), ((inst >>> 12) & 0x7));
                return;
            }
            case 167772243: {
                fsub_d(rd, rs1, ((inst >>> 20) & 0x1f), ((inst >>> 12) & 0x7));
                return;
            }
            case 268435539: {
                fmul_s(rd, rs1, ((inst >>> 20) & 0x1f), ((inst >>> 12) & 0x7));
                return;
            }
            case 301989971: {
                fmul_d(rd, rs1, ((inst >>> 20) & 0x1f), ((inst >>> 12) & 0x7));
                return;
            }
            case 402653267: {
                fdiv_s(rd, rs1, ((inst >>> 20) & 0x1f), ((inst >>> 12) & 0x7));
                return;
            }
            case 436207699: {
                fdiv_d(rd, rs1, ((inst >>> 20) & 0x1f), ((inst >>> 12) & 0x7));
                return;
            }
            case 536870995: {
                interpretTrace64$instructionGroup34(inst, pc, rd, rs1);
                return;
            }
            case 570425427: {
                interpretTrace64$instructionGroup35(inst, pc, rd, rs1);
                return;
            }
            case 671088723: {
                interpretTrace64$instructionGroup36(inst, pc, rd, rs1);
                return;
            }
            case 704643155: {
                interpretTrace64$instructionGroup37(inst, pc, rd, rs1);
                return;
            }
            case 1073741907: {
                if ((inst & 0x1f00000) == 0x100000) {
                    fcvt_s_d(rd, rs1, ((inst >>> 12) & 0x7));
                    return;
                }
                throw illegalInstruction();
            }
            case 1107296339: {
                if ((inst & 0x1f00000) == 0x0) {
                    fcvt_d_s(rd, rs1, ((inst >>> 12) & 0x7));
                    return;
                }
                throw illegalInstruction();
            }
            case 1476395091: {
                if ((inst & 0x1f00000) == 0x0) {
                    fsqrt_s(rd, rs1, ((inst >>> 12) & 0x7));
                    return;
                }
                throw illegalInstruction();
            }
            case 1509949523: {
                if ((inst & 0x1f00000) == 0x0) {
                    fsqrt_d(rd, rs1, ((inst >>> 12) & 0x7));
                    return;
                }
                throw illegalInstruction();
            }
            case -1610612653: {
                interpretTrace64$instructionGroup38(inst, pc, rd, rs1);
                return;
            }
            case -1577058221: {
                interpretTrace64$instructionGroup39(inst, pc, rd, rs1);
                return;
            }
            case -1073741741: {
                interpretTrace64$instructionGroup40(inst, pc, rd, rs1);
                return;
            }
            case -1040187309: {
                interpretTrace64$instructionGroup41(inst, pc, rd, rs1);
                return;
            }
            case -805306285: {
                interpretTrace64$instructionGroup42(inst, pc, rd, rs1);
                return;
            }
            case -771751853: {
                interpretTrace64$instructionGroup43(inst, pc, rd, rs1);
                return;
            }
            case -536870829: {
                interpretTrace64$instructionGroup44(inst, pc, rd, rs1);
                return;
            }
            case -503316397: {
                interpretTrace64$instructionGroup45(inst, pc, rd, rs1);
                return;
            }
            case -268435373: {
                if ((inst & 0x1f07000) == 0x0) {
                    fmv_w_x(rd, rs1);
                    return;
                }
                throw illegalInstruction();
            }
            case -234880941: {
                if ((inst & 0x1f07000) == 0x0) {
                    fmv_d_x(rd, rs1);
                    return;
                }
                throw illegalInstruction();
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace64$instructionGroup34(final int inst, final long pc, final int arg0, final int arg1) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rs2 = ((inst >>> 20) & 0x1f);
        switch (inst & 0xfe00707f) {
            case 536870995: {
                fsgnj_s(arg0, arg1, rs2);
                return;
            }
            case 536875091: {
                fsgnjn_s(arg0, arg1, rs2);
                return;
            }
            case 536879187: {
                fsgnjx_s(arg0, arg1, rs2);
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace64$instructionGroup35(final int inst, final long pc, final int arg0, final int arg1) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rs2 = ((inst >>> 20) & 0x1f);
        switch (inst & 0xfe00707f) {
            case 570425427: {
                fsgnj_d(arg0, arg1, rs2);
                return;
            }
            case 570429523: {
                fsgnjn_d(arg0, arg1, rs2);
                return;
            }
            case 570433619: {
                fsgnjx_d(arg0, arg1, rs2);
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace64$instructionGroup36(final int inst, final long pc, final int arg0, final int arg1) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rs2 = ((inst >>> 20) & 0x1f);
        if ((inst & 0x7000) == 0x0) {
            fmin_s(arg0, arg1, rs2);
            return;
        }
        if ((inst & 0x7000) == 0x1000) {
            fmax_s(arg0, arg1, rs2);
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace64$instructionGroup37(final int inst, final long pc, final int arg0, final int arg1) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rs2 = ((inst >>> 20) & 0x1f);
        if ((inst & 0x7000) == 0x0) {
            fmin_d(arg0, arg1, rs2);
            return;
        }
        if ((inst & 0x7000) == 0x1000) {
            fmax_d(arg0, arg1, rs2);
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace64$instructionGroup38(final int inst, final long pc, final int arg0, final int arg1) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rs2 = ((inst >>> 20) & 0x1f);
        switch (inst & 0xfe00707f) {
            case -1610612653: {
                fle_s(arg0, arg1, rs2);
                return;
            }
            case -1610608557: {
                flt_s(arg0, arg1, rs2);
                return;
            }
            case -1610604461: {
                feq_s(arg0, arg1, rs2);
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace64$instructionGroup39(final int inst, final long pc, final int arg0, final int arg1) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rs2 = ((inst >>> 20) & 0x1f);
        switch (inst & 0xfe00707f) {
            case -1577058221: {
                fle_d(arg0, arg1, rs2);
                return;
            }
            case -1577054125: {
                flt_d(arg0, arg1, rs2);
                return;
            }
            case -1577050029: {
                feq_d(arg0, arg1, rs2);
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace64$instructionGroup40(final int inst, final long pc, final int arg0, final int arg1) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rm = ((inst >>> 12) & 0x7);
        switch (((inst & 0x1f00000) >>> 20)) {
            case 0: {
                fcvt_w_s(arg0, arg1, rm);
                return;
            }
            case 1: {
                fcvt_wu_s(arg0, arg1, rm);
                return;
            }
            case 2: {
                fcvt_l_s(arg0, arg1, rm);
                return;
            }
            case 3: {
                fcvt_lu_s(arg0, arg1, rm);
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace64$instructionGroup41(final int inst, final long pc, final int arg0, final int arg1) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rm = ((inst >>> 12) & 0x7);
        switch (((inst & 0x1f00000) >>> 20)) {
            case 0: {
                fcvt_w_d(arg0, arg1, rm);
                return;
            }
            case 1: {
                fcvt_wu_d(arg0, arg1, rm);
                return;
            }
            case 2: {
                fcvt_l_d(arg0, arg1, rm);
                return;
            }
            case 3: {
                fcvt_lu_d(arg0, arg1, rm);
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace64$instructionGroup42(final int inst, final long pc, final int arg0, final int arg1) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rm = ((inst >>> 12) & 0x7);
        switch (((inst & 0x1f00000) >>> 20)) {
            case 0: {
                fcvt_s_w(arg0, arg1, rm);
                return;
            }
            case 1: {
                fcvt_s_wu(arg0, arg1, rm);
                return;
            }
            case 2: {
                fcvt_s_l(arg0, arg1, rm);
                return;
            }
            case 3: {
                fcvt_s_lu(arg0, arg1, rm);
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace64$instructionGroup43(final int inst, final long pc, final int arg0, final int arg1) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int rm = ((inst >>> 12) & 0x7);
        switch (((inst & 0x1f00000) >>> 20)) {
            case 0: {
                fcvt_d_w(arg0, arg1, rm);
                return;
            }
            case 1: {
                fcvt_d_wu(arg0, arg1, rm);
                return;
            }
            case 2: {
                fcvt_d_l(arg0, arg1, rm);
                return;
            }
            case 3: {
                fcvt_d_lu(arg0, arg1, rm);
                return;
            }
            default:
                throw illegalInstruction();
        }
    }

    private void interpretTrace64$instructionGroup44(final int inst, final long pc, final int arg0, final int arg1) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        if ((inst & 0x1f07000) == 0x0) {
            fmv_x_w(arg0, arg1);
            return;
        }
        if ((inst & 0x1f07000) == 0x1000) {
            fclass_s(arg0, arg1);
            return;
        }
        throw illegalInstruction();
    }

    private void interpretTrace64$instructionGroup45(final int inst, final long pc, final int arg0, final int arg1) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        if ((inst & 0x1f07000) == 0x0) {
            fmv_x_d(arg0, arg1);
            return;
        }
        if ((inst & 0x1f07000) == 0x1000) {
            fclass_d(arg0, arg1);
            return;
        }
        throw illegalInstruction();
    }

    private int interpretTrace64$instructionGroup46(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        final int imm = ((inst << 4) & 0x800) | ((inst >>> 7) & 0x1e) | ((inst >>> 20) & 0x7e0) | BitUtils.extendSign(((inst >>> 19) & 0x1000), 13);
        final int rs1 = ((inst >>> 15) & 0x1f);
        final int rs2 = ((inst >>> 20) & 0x1f);
        switch (((inst & 0x7000) >>> 12)) {
            case 0: {
                if (beq(rs1, rs2, imm, pc)) {
                    return 4;
                }
                return 0;
            }
            case 1: {
                if (bne(rs1, rs2, imm, pc)) {
                    return 4;
                }
                return 0;
            }
            case 4: {
                if (blt(rs1, rs2, imm, pc)) {
                    return 4;
                }
                return 0;
            }
            case 5: {
                if (bge(rs1, rs2, imm, pc)) {
                    return 4;
                }
                return 0;
            }
            case 6: {
                if (bltu(rs1, rs2, imm, pc)) {
                    return 4;
                }
                return 0;
            }
            case 7: {
                if (bgeu(rs1, rs2, imm, pc)) {
                    return 4;
                }
                return 0;
            }
            default:
                throw illegalInstruction();
        }
    }

    private int interpretTrace64$instructionGroup47(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        switch (((inst & 0x7000) >>> 12)) {
            case 0: {
                return interpretTrace64$instructionGroup48(inst, pc);
            }
            case 1: {
                if (csrrw(((inst >>> 7) & 0x1f), ((inst >>> 15) & 0x1f), ((inst >>> 20) & 0xfff))) {
                    return 1;
                }
                return 0;
            }
            case 2: {
                if (csrrs(((inst >>> 7) & 0x1f), ((inst >>> 15) & 0x1f), ((inst >>> 20) & 0xfff))) {
                    return 1;
                }
                return 0;
            }
            case 3: {
                if (csrrc(((inst >>> 7) & 0x1f), ((inst >>> 15) & 0x1f), ((inst >>> 20) & 0xfff))) {
                    return 1;
                }
                return 0;
            }
            case 5: {
                if (csrrwi(((inst >>> 7) & 0x1f), ((inst >>> 15) & 0x1f), ((inst >>> 20) & 0xfff))) {
                    return 1;
                }
                return 0;
            }
            case 6: {
                if (csrrsi(((inst >>> 7) & 0x1f), ((inst >>> 15) & 0x1f), ((inst >>> 20) & 0xfff))) {
                    return 1;
                }
                return 0;
            }
            case 7: {
                if (csrrci(((inst >>> 7) & 0x1f), ((inst >>> 15) & 0x1f), ((inst >>> 20) & 0xfff))) {
                    return 1;
                }
                return 0;
            }
            default:
                throw illegalInstruction();
        }
    }

    private int interpretTrace64$instructionGroup48(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        if ((inst & 0xf80) != 0x0) {
            throw illegalInstruction();
        }
        switch (inst & 0xfe007fff) {
            case 115: {
                return interpretTrace64$instructionGroup49(inst, pc);
            }
            case 268435571: {
                return interpretTrace64$instructionGroup50(inst, pc);
            }
            case 301990003: {
                if (sfence_vma(((inst >>> 15) & 0x1f), ((inst >>> 20) & 0x1f))) {
                    return 1;
                }
                return 0;
            }
            case 805306483: {
                if ((inst & 0x1ff8000) == 0x200000) {
                    if (mret()) {
                        return 2;
                    }
                    return 0;
                }
                throw illegalInstruction();
            }
            default:
                throw illegalInstruction();
        }
    }

    private int interpretTrace64$instructionGroup49(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        if ((inst & 0x1ff8000) == 0x0) {
            ecall(pc);
            return 3;
        }
        if ((inst & 0x1ff8000) == 0x100000) {
            ebreak(pc);
            return 3;
        }
        throw illegalInstruction();
    }

    private int interpretTrace64$instructionGroup50(final int inst, final long pc) throws li.cil.sedna.riscv.exception.R5IllegalInstructionException {
        if ((inst & 0x1ff8000) == 0x200000) {
            if (sret()) {
                return 2;
            }
            return 0;
        }
        if ((inst & 0x1ff8000) == 0x500000) {
            if (wfi()) {
                return 1;
            }
            return 0;
        }
        throw illegalInstruction();
    }
}
