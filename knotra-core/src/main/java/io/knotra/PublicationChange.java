package io.knotra;

/** The independently observable result of exactly one publication operation. */
public interface PublicationChange<T> extends Settlement {
    PublicationOperation operation();

    Publication<T> publication();

    /** Null for UNPUBLISH. */
    Registration<T> registration();
}
