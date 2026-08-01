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
