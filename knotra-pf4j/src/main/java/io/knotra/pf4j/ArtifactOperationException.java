package io.knotra.pf4j;

/** Structured adapter failure containing stable text only. */
public final class ArtifactOperationException extends RuntimeException {

    private final String artifactId;
    private final String phase;

    public ArtifactOperationException(String artifactId, String phase, String message) {
        super(message);
        this.artifactId = artifactId == null ? "unknown" : artifactId;
        this.phase = phase == null ? "unknown" : phase;
    }

    public String artifactId() {
        return artifactId;
    }

    public String phase() {
        return phase;
    }
}
