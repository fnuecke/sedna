package li.cil.sedna.riscv;

import li.cil.sedna.api.device.rtc.RealTimeCounter;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.instruction.decoder.DecoderGenerator;
import li.cil.sedna.riscv.exception.R5IllegalInstructionException;
import org.apache.logging.log4j.core.util.Throwables;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.SimpleRemapper;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

public final class R5CPUGenerator {
    public static final Class<R5CPUTemplate> TEMPLATE_CLASS = R5CPUTemplate.class;
    public static final String GENERATED_SUFFIX = "$Generated";

    @SuppressWarnings("unchecked")
    private static final Class<R5CPU> GENERATED_CLASS = (Class<R5CPU>) generateClass();
    private static final Constructor<R5CPU> GENERATED_CLASS_CTOR;

    public static Class<R5CPU> getGeneratedClass() {
        return GENERATED_CLASS;
    }

    public static R5CPU create(final MemoryMap physicalMemory, @Nullable final RealTimeCounter rtc) {
        try {
            return GENERATED_CLASS_CTOR.newInstance(physicalMemory, rtc);
        } catch (final InvocationTargetException e) {
            Throwables.rethrow(e.getCause());
            throw new AssertionError();
        } catch (final InstantiationException | IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    static {
        try {
            GENERATED_CLASS_CTOR = GENERATED_CLASS.getConstructor(MemoryMap.class, RealTimeCounter.class);
            GENERATED_CLASS_CTOR.setAccessible(true);
        } catch (final NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private static class RemappedTypeCollector extends Remapper {
        private final Set<String> remappedTypeNames = new HashSet<>();
        private final String hostClassName;

        public RemappedTypeCollector(final Class<?> hostClass) {
            this.hostClassName = Type.getInternalName(hostClass);
        }

        public String getHostClassName() {
            return hostClassName;
        }

        public ClassVisitor getVisitor() {
            return new ClassRemapper(null, this);
        }

        public Set<String> getRemappedTypeNames() {
            return remappedTypeNames;
        }

        @Override
        public String map(final String internalName) {
            if (internalName.startsWith(hostClassName)) {
                remappedTypeNames.add(internalName);
            }
            return super.map(internalName);
        }
    }

    private static final class RemappedTypeClassWriter extends ClassWriter {
        private final Map<String, String> toNewTypeName = new HashMap<>();
        private final Map<String, String> toOldTypeName = new HashMap<>();

        public RemappedTypeClassWriter(final Set<String> remappedTypeNames) {
            super(COMPUTE_FRAMES);
            for (final String remappedTypeName : remappedTypeNames) {
                toNewTypeName.put(remappedTypeName, remappedTypeName + GENERATED_SUFFIX);
                toOldTypeName.put(remappedTypeName + GENERATED_SUFFIX, remappedTypeName);
            }
        }

        @Override
        protected String getCommonSuperClass(final String type1, final String type2) {
            final String commonSuperClass;

            if (toOldTypeName.containsKey(type1) && toOldTypeName.containsKey(type2)) {
                commonSuperClass = super.getCommonSuperClass(toOldTypeName.get(type1), toOldTypeName.get(type2));
            } else if (toOldTypeName.containsKey(type1)) {
                commonSuperClass = super.getCommonSuperClass(toOldTypeName.get(type1), type2);
            } else if (toOldTypeName.containsKey(type2)) {
                commonSuperClass = super.getCommonSuperClass(type1, toOldTypeName.get(type2));
            } else {
                return super.getCommonSuperClass(type1, type2);
            }

            if (toNewTypeName.containsKey(commonSuperClass)) {
                return toNewTypeName.get(commonSuperClass);
            }

            return commonSuperClass;
        }

        @Override
        protected ClassLoader getClassLoader() {
            return TEMPLATE_CLASS.getClassLoader();
        }
    }

    private static final class ListRemapper extends SimpleRemapper {
        public ListRemapper(final Set<String> remappedTypeNames) {
            super(toMapping(remappedTypeNames));
        }

        private static Map<String, String> toMapping(final Set<String> remappedTypeNames) {
            final Map<String, String> map = new HashMap<>();
            for (final String remappedTypeName : remappedTypeNames) {
                map.put(remappedTypeName, remappedTypeName + GENERATED_SUFFIX);
            }
            return map;
        }
    }

    private static Class<?> generateClass() {
        try {
            final ClassLoader classLoader = TEMPLATE_CLASS.getClassLoader();

            try (final InputStream stream = classLoader.getResourceAsStream(TEMPLATE_CLASS.getName().replace('.', '/') + ".class")) {
                if (stream == null) {
                    throw new IOException("Could not load class file for class [" + TEMPLATE_CLASS + "].");
                }

                final ClassReader reader = new ClassReader(stream);

                final RemappedTypeCollector typeCollector = new RemappedTypeCollector(TEMPLATE_CLASS);
                reader.accept(typeCollector.getVisitor(), ClassReader.EXPAND_FRAMES);
                final Set<String> remappedTypeNames = typeCollector.getRemappedTypeNames();
                final ListRemapper remapper = new ListRemapper(remappedTypeNames);

                for (final String remappedTypeName : remappedTypeNames) {
                    if (Objects.equals(remappedTypeName, typeCollector.getHostClassName())) {
                        continue;
                    }

                    try (final InputStream nestedTypeStream = classLoader.getResourceAsStream(remappedTypeName.replace('.', '/') + ".class")) {
                        if (nestedTypeStream == null) {
                            throw new IOException("Could not load class file for class [" + remappedTypeName + "].");
                        }

                        final ClassReader nestedTypeReader = new ClassReader(nestedTypeStream);
                        final RemappedTypeClassWriter nestedTypeWriter = new RemappedTypeClassWriter(remappedTypeNames);
                        nestedTypeReader.accept(new ClassRemapper(nestedTypeWriter, remapper), ClassReader.EXPAND_FRAMES);

                        CPUClassLoader.defineClass(nestedTypeWriter.toByteArray());
                    } catch (final Throwable e) {
                        throw new AssertionError(e);
                    }
                }

                final RemappedTypeClassWriter writer = new RemappedTypeClassWriter(remappedTypeNames);
                final DecoderGenerator generator64 = new DecoderGenerator(
                    new ClassRemapper(writer, remapper),
                    R5Instructions.RV64.getDecoderTree(),
                    R5Instructions.RV64::getDefinition,
                    R5IllegalInstructionException.class,
                    "interpretTrace64",
                    "decode");
                final DecoderGenerator generator32 = new DecoderGenerator(
                    generator64,
                    R5Instructions.RV32.getDecoderTree(),
                    R5Instructions.RV32::getDefinition,
                    R5IllegalInstructionException.class,
                    "interpretTrace32",
                    "decode");

                reader.accept(generator32, ClassReader.EXPAND_FRAMES);

                final byte[] bytes = writer.toByteArray();

                return CPUClassLoader.defineClass(bytes);
            }
        } catch (final Throwable e) {
            throw new AssertionError(e);
        }
    }

    private static class CPUClassLoader extends ClassLoader {
        private static final CPUClassLoader INSTANCE = new CPUClassLoader();

        public static Class<?> defineClass(final byte[] bytecode) {
            return INSTANCE.defineClass(null, bytecode, 0, bytecode.length);
        }
    }
}
