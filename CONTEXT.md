# Knotra Domain Vocabulary

This file fixes the domain words used across Knotra. It describes meaning, not implementation.

## Capability

A typed, named contract that a Context can expose. A capability identifies what is offered, not which object currently offers it.

## Publication
A stable logical slot for offering a capability in one Context. A publication can be updated or withdrawn while keeping the same slot identity. Its current registration is optional; external displacement is terminal and never silently creates a new registration.

## Registration

A specific committed generation of a capability value in a Context. Replacing a publication creates a new registration; it does not mutate the previous registration.

## Generation

The committed identity of one complete runtime structure. Observers use a generation to talk about the same coherent view of Contexts, registrations, mounts, and bindings.

## Mount

A stable logical place where component logic is attached. Mount identity survives activations and configuration changes; it disappears only through disposal. A mount handle's own settlement covers that mount's transition, not the entire ownership subtree; operation settlement covers the owned subtree created by that operation.

## Activation

One runtime attempt to start the component logic attached to a mount. An activation captures the bindings and configuration from its starting generation.

## Settlement
The observable completion of one operation and its required propagation or cleanup. Settlement is operation-scoped: it reports only mounts affected by that change and waits owned mounts created by that operation's activations. Settlement can complete with reports of failed activation or intentional disposal; it does not imply that every affected mount is active. An empty affected set has no failures, but it is not an "all active" result.

## Context

A named node in the runtime structure tree. Contexts provide capability visibility boundaries and ownership boundaries for registrations and mounts.
