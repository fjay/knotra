package io.knotra;

public interface ManagedHandle {
    String entryId();

    String description();

    CleanupState state();

    int attempts();

    String lastError();
}
