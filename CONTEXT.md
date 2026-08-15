# Agentic

Agentic is a native Android client for interacting with T3 environments through a portable,
provider-neutral contract.

## Language

**Environment registration**:
Adding, pairing, authenticating, naming, reconnecting to, or removing a reachable T3 environment
from Agentic.
_Avoid_: Environment provisioning, server setup

**Environment provisioning**:
Installing, starting, hosting, or exposing a T3 environment server.
_Avoid_: Environment registration, pairing

**Server accommodation requirement**:
A client-observable capability that the portable T3 contract does not currently expose and that
must be communicated to the T3 Code project without prescribing its server implementation.
_Avoid_: Server implementation task, shim implementation plan

**Capability-parity inventory entry**:
A single user-observable capability or behavioral guarantee, paired with separate source evidence
from the frozen T3 reference clients, portable protocol, and Agentic rather than represented as a
screen or feature bundle.
_Avoid_: Gap item, screen inventory, reference-client feature bundle

**Active thread workspace**:
The filesystem root selected for a thread: its resolved worktree when present, otherwise its
project workspace root. Workspace-file operations remain within this root.
_Avoid_: Environment filesystem, project directory when a thread worktree is active
