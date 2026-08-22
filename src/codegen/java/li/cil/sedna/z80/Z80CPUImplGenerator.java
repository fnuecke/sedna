package li.cil.sedna.z80;

import li.cil.sedna.instruction.InstructionDeclaration;
import li.cil.sedna.instruction.InstructionDefinition;
import li.cil.sedna.instruction.InstructionDefinitionLoader;
import li.cil.sedna.instruction.decoder.DecoderSourceGenerator;
import li.cil.sedna.instruction.decoder.SourceBuilder;
import li.cil.sedna.z80.exception.Z80IllegalInstructionException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class Z80CPUImplGenerator {
    private Z80CPUImplGenerator() {
    }

    public static String generateSource() throws IOException {
        final Map<InstructionDeclaration, InstructionDefinition> definitions =
                InstructionDefinitionLoader.load(Z80CPUBase.class, Z80Instructions.getDeclarations());

        // The decode section content sits at depth 5: class body (1), method body (2), for loop (3),
        // decode block (4), content (5).
        final SourceBuilder decode = new SourceBuilder(5);
        final DecoderSourceGenerator generator = new DecoderSourceGenerator(
                Z80Instructions.getDecoderTree(), definitions::get, Z80IllegalInstructionException.class,
                "interpretTrace", Z80CPUImplGenerator::emitJumpHandler, decode);
        generator.generate();

        final SourceBuilder src = new SourceBuilder();

        src.line("/*");
        src.line(" * This file is GENERATED - do not edit it by hand; any changes will be overwritten.");
        src.line(" * Regenerate with `./gradlew generateZ80Decoder`, which runs li.cil.sedna.z80.Z80CPUImplGenerator.");
        src.line(" */");
        src.blank();
        src.line("package li.cil.sedna.z80;");
        src.blank();
        src.line("import li.cil.sedna.api.memory.MemoryMap;");
        src.line("import li.cil.sedna.utils.BitUtils;");
        src.line("import li.cil.sedna.z80.exception.Z80IllegalInstructionException;");
        src.blank();
        src.line("final class Z80CPUImpl extends Z80CPUBase {");
        src.push();
        src.line("Z80CPUImpl(final MemoryMap memoryMap, final MemoryMap ioMap) {");
        src.indent(() -> src.line("super(memoryMap, ioMap);"));
        src.line("}");

        src.blank();
        src.line("@Override");
        src.line("protected void interpretTrace(final boolean singleStep) {");
        src.indent(() -> {
            src.line("long pc = this.pc & 0xFFFF;");
            src.line("int instOffset = (int) pc;");
            src.line("try {");
            src.indent(() -> {
                src.line("for (; ; ) {");
                src.indent(() -> {
                    src.line("final int inst = fetch32((int) pc);");
                    src.line("bumpR(inst);");
                    src.blank();
                    src.line("decode: {");
                    src.raw(decode.toString());
                    src.line("}");
                    src.blank();
                    src.line("pc &= 0xFFFF;");
                    src.line("instOffset = (int) pc;");
                    src.line("if (singleStep || cycles >= cycleLimit || interruptsPending()) {");
                    src.indent(() -> {
                        src.line("this.pc = pc;");
                        src.line("return;");
                    });
                    src.line("}");
                });
                src.line("}");
            });
            src.line("} catch (final Z80IllegalInstructionException e) {");
            src.indent(() -> {
                src.line("this.pc = pc & 0xFFFF;");
                src.line("throw new IllegalStateException(\"Z80 decoder is not total.\", e);");
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

        src.pop();
        src.line("}");
        return src.toString();
    }

    private static void emitJumpHandler(final SourceBuilder out, final boolean mayContinue) {
        out.line("pc = this.pc & 0xFFFF;");
        out.line("instOffset = (int) pc;");
        out.line("if (singleStep || cycles >= cycleLimit || interruptsPending()) {");
        out.indent(() -> out.line("return;"));
        out.line("}");
        out.line("break decode;");
    }

    public static void main(final String[] args) throws IOException {
        final Path path = Path.of(args.length > 0 ? args[0] : "src/main/java/li/cil/sedna/z80/Z80CPUImpl.java");
        final String source = generateSource();
        Files.writeString(path, source);
        System.out.println("Wrote " + path + " (" + source.length() + " chars).");
    }
}
