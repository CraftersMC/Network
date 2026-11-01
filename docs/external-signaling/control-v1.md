# Optional NXS WebSocket transport

WebSocket can carry the signed NXS operations and opt-in assisted joins.
Registration, completion and recovery always use HTTPS. Both carriers share
identity, sequencing and recovery, so a client can fall back to HTTPS for
ordinary operations.

## Discovery and authentication

The noncritical `dev.opencollab.nxs.websocket` discovery extension, `version: 1`,
advertises the endpoint. Its `data` is empty unless the provider supports
[authenticating in the first frame](#authenticating-in-the-first-frame). It is `/v1/nxs/control` on the
provider origin with scheme `wss`, and subprotocol `nethernet-external-signaling-v1`.
Loopback development may use WS. Apply the
[canonical origin](wire-reference.md#canonical-origins) rules and normal TLS
verification; credentials must not follow redirects.

Authenticate the upgrade with the
[NXS request headers and signature](wire-reference.md#sign-operational-requests)
for `GET /v1/nxs/control`, an empty body, and the HTTPS provider origin. The
upgrade carries the last sequence the host used and does not consume one. A host
whose profile declares `assistedFamilies` also sends `nxs-assisted: 1`, a routing
hint for the provider that grants nothing by itself.

### Authenticating in the first frame

Some hosts, such as web browsers, cannot set headers on a WebSocket upgrade. A
provider that accepts them sets `frameAuthentication: true` in the extension's
`data`. Only then may a host authenticate this way:

1. Open `/v1/nxs/control?instance=<instanceId>` with the subprotocol and no NXS
   headers. The query is exactly that single parameter. The instance ID is not
   secret; it lets the provider route the socket before it is authenticated.
2. Send one text frame `{"kind":"authenticate","headers":{...}}`. `headers` holds
   the NXS request headers the upgrade would carry, signed for `GET`, that exact
   path and query, and an empty body. `nxs-instance-id` matches the query. This
   path needs no `nxs-assisted` hint.
3. The provider answers with a `response` frame whose `id` is the frame's
   idempotency key. Status 200 means the socket is authenticated, exactly like a
   header-authenticated upgrade. Any other status carries the operation error body,
   and the provider closes the socket with 1008.

Before that response the host sends only `ping`. The provider closes the socket
with 1008 on any other frame, on an invalid authenticate frame, or when no
authenticate frame succeeds within 10 seconds. A failed attempt does not affect a
socket already authenticated for the instance. Providers should limit these
upgrades per source address, since they are unauthenticated until the first frame.

## Frames

Every JSON frame is an object with a `kind`. The host sends `operation` and
`assisted-join-result`, and `authenticate` when it
[authenticates in the first frame](#authenticating-in-the-first-frame); the
provider sends `response` and `assisted-join`. A frame
without `kind`, or with a known `kind` and an invalid shape, closes the socket with
code 1008. Receivers ignore frames of an unknown `kind` and members they do not
recognize. The [schema](nxs-v1.schema.json) defines each kind under `$defs`. The
literal text frames `ping` and `pong` are the only other messages.

## Operation envelopes

`heartbeat`, `outcomes`, `rotate`, `retire` and `deregister` use this text envelope:

```json
{"kind":"operation","operation":"heartbeat","headers":{"nxs-instance-id":"...","nxs-key-id":"...","nxs-timestamp":"...","nxs-signature-version":"nxs-es384-v1","nxs-generation":"...","nxs-sequence":"...","idempotency-key":"...","nxs-signature":"..."},"body":"original JSON body"}
```

Sign the original body for the same POST target, generation, sequence and
idempotency key used by HTTPS. The frame cannot choose another URL or method.
`operator-action` and operations advertised by extensions use HTTPS. The response is
`{kind:"response", id, status, headers, body}`: the original idempotency key, HTTP
status, response headers and unmodified response body string. Preserve operation errors, retry
hints and replay handling across fallback. An ambiguous operation is recovered
before sending fresh state.

Response `headers` maps HTTP field names to string values. Names are case-insensitive
ASCII HTTP tokens, at most 128 characters; values are at most 1,024 characters
without control characters. Accept up to 32 distinct names, reject duplicates
ignoring case, and ignore fields the client does not use.

Clients limit complete UTF-8 messages to 524,288 bytes and response bodies to
65,536 bytes. The [NXS operation limits](wire-reference.md#authorization-and-limits)
also apply. For socket liveness the host sends literal `ping` about every 20
seconds and drops the socket when one is still unanswered at the next; the
provider answers `pong`. These strings do not acknowledge operations or renew a
heartbeat lease.

## Assisted joins

Assistance requires explicit host configuration, host transport support and a
live authenticated WebSocket. The host declares eligible `assistedFamilies` in its
[host profile](wire-reference.md#publish-the-host-profile).
A provider sends an `assisted-join` object with `version: 1` and these fields:

| Fields | Meaning |
| --- | --- |
| `id`, `expiresAt` | Attempt ID (32 lowercase hex digits) and fixed epoch millisecond deadline |
| `instanceId`, `generation`, `incarnation` | Current registered host context: instance ID, integer generation and profile incarnation |
| `keyId`, `hostFingerprint` | Installed admission key ID and the profile's `dtlsFingerprint` |
| `networkId`, `cpk` | Player network ID as an unsigned 64-bit decimal string, and its [canonical identity key](wire-reference.md#bind-signaling-identity-to-the-game-login) |
| `localUfrag`, `localPassword`, `offer` | Host admission ufrag (an `NXS1` token) and password, and the client's SDP with the candidates the provider selected |

The offer has one `application` section with `UDP/DTLS/SCTP webrtc-datachannel`,
`a=setup:actpass`, SCTP port 5000, maximum message size 262144, single ICE
credentials and fingerprint, no `a=ice-lite`, and 1 to 32 numeric UDP candidates
for component 1.

The provider keeps only the client's public candidates. Current Minecraft clients
cannot use STUN and offer only private addresses; for them the provider may add
predicted `srflx` candidates, pairing the public address it saw on the client's
signaling request with each port the client offered. These work only when the
client's NAT keeps the source port. Hosts treat every candidate in the offer
alike and learn the client's actual address from its authenticated checks. A
provider should skip assistance for a client that shares the host's public
address, since it reaches the host directly.

The frame also contains `kind: "assisted-join"`. Player attempts expire within
30 seconds. The host validates the context, credentials and offer before creating
a peer on its gameplay socket. It returns
`{kind:"assisted-join-result",id,accepted:true,answer}` or
`{kind:"assisted-join-result",id,accepted:false}`. Assisted
[diagnostics](diagnostic-v1.md#direct-and-assisted-transport) use the same exchange.

Results require the original socket and unexpired host/profile/key authorization;
superseded or late results are discarded. Successful WebSocket heartbeats refresh
local assisted authorization for at most five minutes. Reconnect and ping/pong do
not refresh it. Publication and assistance policy stay with the host; see
[serving control](wire-reference.md#serving-control).
