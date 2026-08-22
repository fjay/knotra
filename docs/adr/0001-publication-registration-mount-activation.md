# ADR 0001: Stable Publication, Generational Registration, and Mount Vocabulary


## Status

Accepted for the pre-1.0 Core API.

## Context

A capability slot has two different lifetimes. Users normally want a long-lived handle they can keep updating, while the runtime must preserve the exact identity of each committed value so bindings, drains, and retries remain unambiguous. Similarly, a mounted logical component can have many activation attempts; exposing an activation-shaped handle makes stable restart and reconfiguration semantics harder to explain.

The API is still pre-1.0 and compatibility is explicitly not required, so retaining misleading names would freeze the wrong model.

## Decision

- `Publication<T>` names the stable logical slot in one Context.
- `Registration<T>` names one committed generation of a capability value and belongs strictly to the Advanced API.
- Each publication operation returns its own `PublicationChange<T>` (implementing `Settlement`) providing the operation kind, publication, generation, and settlement observer.
- Simple API (`Publication`, `PublicationChange`) never leaks raw `Registration<T>` instances.
- Unpublish is terminal and idempotent; an externally removed current registration displaces the publication rather than silently making a later update succeed.
- `StagedRegistration<T>` is typed only while recording a transaction. After commit it remains an opaque registration handle suitable for revoke, but it never becomes a typed `Registration<T>` and cannot replace or await settlement.
- `MountHandle` names a stable logical mount. `ConfiguredMountHandle<C>` is the narrower handle that exposes configuration changes. The simple runtime's no-configuration mount methods accept `MountFactory`, keeping `NoConfig` at the advanced/SPI boundary.
- Runtime mount implementations are split into plain and configured handle classes; a plain mount is never represented as a configured mount.
- `Activation` remains the word for one start attempt and its captured bindings; it is not the public name for the stable attach point.
- Runtime snapshots report `mounts`, not `components`, while mount state still describes the current activation outcome.
- A transaction/publication settlement waits the ownership closure created by that operation's activations. `MountHandle.whenSettled()` settles only that mount's own transition, so an owner can be visibly ACTIVE while its newly committed owned children are still starting. This lets children consume committed parent output without waiting on the parent transition and deadlocking.
- Settlement reports are operation-scoped. `hasFailedMounts()`, `hasAffectedMounts()`, and `allAffectedActive()` are explicit predicates; an empty affected set has no failed mounts and no affected mounts, and returns `false` for `allAffectedActive()`.

## Consequences

Calling code that wants a durable handle can hold a `Publication` without tracking replacement identities. Advanced code can inspect or revoke a `Registration` when it needs a specific generation. Each change has an independent settlement object, so concurrent updates cannot make one caller wait for an ambiguous later generation.

The mount vocabulary gives restart, retry, disposal, and configuration change one stable identity, while failed or superseded activations remain historical effects visible through operation reports and diagnostics. Removed handles default to DISPOSED and the kernel keeps no global terminal ID history; an operation captures only the removed mount identities it needs for its own report. This costs two handle types for plain and configured mounts, which is preferable to encoding configuration type into every mount handle.
