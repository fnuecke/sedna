package li.cil.sedna.riscv;

import li.cil.sedna.riscv.dbt.TranslatorJob;

import java.util.concurrent.ConcurrentLinkedQueue;

final class R5CPUTranslatorDataExchange {
    public final ConcurrentLinkedQueue<TranslatorJob> translatorRequests = new ConcurrentLinkedQueue<>();
    public final ConcurrentLinkedQueue<TranslatorJob> translationResponses = new ConcurrentLinkedQueue<>();
}
