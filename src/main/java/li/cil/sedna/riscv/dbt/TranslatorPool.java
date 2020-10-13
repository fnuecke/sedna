package li.cil.sedna.riscv.dbt;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TranslatorPool {
    public static final ExecutorService TRANSLATORS = Executors.newFixedThreadPool(
            Math.min(4, Runtime.getRuntime().availableProcessors()), r -> {
                final Thread thread = new Thread(r, "RISC-V Translator Thread");
                thread.setDaemon(true);
                return thread;
            });
}
