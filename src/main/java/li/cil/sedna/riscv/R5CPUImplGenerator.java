package li.cil.sedna.riscv;

import li.cil.sedna.instruction.decoder.DecoderSourceGenerator;
import li.cil.sedna.instruction.decoder.SourceBuilder;
import li.cil.sedna.riscv.exception.R5IllegalInstructionException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class R5CPUImplGenerator {
    private R5CPUImplGenerator() {
    }

    public static String generateSource() {
        final SourceBuilder src = new SourceBuilder();

        src.line("/*");
        src.line(" * This file is GENERATED - do not edit it by hand; any changes will be overwritten.");
        src.line(" * Regenerate with `./gradlew generateDecoder`, which runs li.cil.sedna.riscv.R5CPUImplGenerator.");
        src.line(" */");
        src.blank();
        src.line("package li.cil.sedna.riscv;");
        src.blank();
        src.line("import it.unimi.dsi.fastutil.longs.LongSet;");
        src.line("import li.cil.sedna.api.Sizes;");
        src.line("import li.cil.sedna.api.device.MemoryMappedDevice;");
        src.line("import li.cil.sedna.api.device.rtc.RealTimeCounter;");
        src.line("import li.cil.sedna.api.memory.MemoryAccessException;");
        src.line("import li.cil.sedna.api.memory.MemoryMap;");
        src.line("import li.cil.sedna.riscv.exception.R5IllegalInstructionException;");
        src.line("import li.cil.sedna.riscv.exception.R5MemoryAccessException;");
        src.line("import li.cil.sedna.utils.BitUtils;");
        src.blank();
        src.line("import javax.annotation.Nullable;");
        src.blank();
        src.line("final class R5CPUImpl extends R5CPUBase {");
        src.push();
        src.line("R5CPUImpl(final MemoryMap physicalMemory, @Nullable final RealTimeCounter rtc) {");
        src.indent(() -> src.line("super(physicalMemory, rtc);"));
        src.line("}");

        emitVariant(src, "32", R5Instructions.RV32);
        emitVariant(src, "64", R5Instructions.RV64);

        src.pop();
        src.line("}");
        return src.toString();
    }

    private static void emitVariant(final SourceBuilder src, final String variant, final R5Instructions.Spec spec) {
        // The decode section content sits at depth 5: class body (1), method body (2), for loop (3),
        // decode block (4), content (5).
        final SourceBuilder decode = new SourceBuilder(5);
        final DecoderSourceGenerator generator = new DecoderSourceGenerator(
            spec.getDecoderTree(), spec::getDefinition, R5IllegalInstructionException.class,
            "interpretTrace" + variant, decode);
        generator.generate();

        src.blank();
        src.line("@Override");
        src.line("protected void interpretTrace" + variant + "(final MemoryMappedDevice device, final long hostBase, int inst, long pc, int instOffset, final int instEnd, final LongSet breakpoints) {");
        src.indent(() -> {
            src.line("try { // Catch any exceptions to patch PC field.");
            src.indent(() -> {
                src.line("for (; ; ) { // End of page check at the bottom since we enter with a valid inst.");
                src.indent(() -> {
                    src.line("if (breakpoints != null && breakpoints.contains(pc)) {");
                    src.indent(() -> {
                        src.line("this.pc = pc;");
                        src.line("debugInterface.handleBreakpoint(pc);");
                        src.line("return;");
                    });
                    src.line("}");
                    src.line("mcycle++;");
                    src.line("minstret++;");
                    src.blank();
                    src.line("decode: {");
                    src.raw(decode.toString());
                    src.line("}");
                    src.blank();
                    src.line("if (Integer.compareUnsigned(instOffset, instEnd) < 0) { // Likely case: we're still fully in the page.");
                    src.indent(() -> src.line("inst = hostBase != 0 ? UNSAFE.getInt(hostBase + instOffset) : (int) device.load(instOffset, Sizes.SIZE_32_LOG2);"));
                    src.line("} else { // Unlikely case: we reached the end of the page. Leave to do interrupts and cycle check.");
                    src.indent(() -> {
                        src.line("this.pc = pc;");
                        src.line("return;");
                    });
                    src.line("}");
                });
                src.line("}");
            });
            src.line("} catch (final MemoryAccessException e) {");
            src.indent(() -> {
                src.line("this.pc = pc;");
                src.line("raiseException(R5.EXCEPTION_FAULT_FETCH, pc);");
            });
            src.line("} catch (final R5IllegalInstructionException e) {");
            src.indent(() -> {
                src.line("this.pc = pc;");
                src.line("raiseException(R5.EXCEPTION_ILLEGAL_INSTRUCTION, inst);");
            });
            src.line("} catch (final R5MemoryAccessException e) {");
            src.indent(() -> {
                src.line("this.pc = pc;");
                src.line("raiseException(e.getType(), e.getAddress());");
            });
            src.line("}");
        });
        src.line("}");

        for (final String method : generator.getGroupMethods()) {
            src.blank();
            method.stripTrailing().lines().forEach(line -> {
                if (line.isBlank()) {
                    src.blank();
                } else {
                    src.line(line);
                }
            });
        }
    }

    public static void main(final String[] args) throws IOException {
        final Path path = Path.of(args.length > 0 ? args[0] : "src/main/java/li/cil/sedna/riscv/R5CPUImpl.java");
        final String source = generateSource();
        Files.writeString(path, source);
        System.out.println("Wrote " + path + " (" + source.length() + " chars).");
    }
}
