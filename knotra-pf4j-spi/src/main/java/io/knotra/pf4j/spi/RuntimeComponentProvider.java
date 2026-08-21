package io.knotra.pf4j.spi;

import java.util.Collection;

import org.pf4j.ExtensionPoint;

/**
 * Entry point published by a PF4J artifact.
 *
 * <p>The provider exposes controlled factories with explicit configuration tokens.
 * A host never receives an executable artifact component from this interface.</p>
 */
public interface RuntimeComponentProvider extends ExtensionPoint {

    Collection<ExportedComponentFactory<?>> factories();
}
