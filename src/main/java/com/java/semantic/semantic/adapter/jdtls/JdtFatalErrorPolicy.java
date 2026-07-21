package com.java.semantic.semantic.adapter.jdtls;

/** 集中定義生命週期不可降級的致命錯誤 */
@SuppressWarnings("removal")
final class JdtFatalErrorPolicy {

    private JdtFatalErrorPolicy() {
    }

    static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    static void rethrowIfFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (failure instanceof ThreadDeath fatal) {
            throw fatal;
        }
    }
}
