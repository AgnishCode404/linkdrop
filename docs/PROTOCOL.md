# LinkDrop protocol, version 1

This document is the contract between the desktop and Android apps. Slice A implements
`hello`, `helloAck` and `error`. Every other message here is fixed now, so that file
transfer (Slice B) adds code but no protocol changes.

Code: `shared/src/commonMain/kotlin/com/agnish/linkdrop/protocol/`.

## Transport

- One WebSocket connection per session, at path `/v1/session`, default port `47800`.
- Development builds use `ws://`. Release builds use only `wss://` (TLS with a pinned
  certificate, set up by pairing). There is no plaintext fallback. Moving to TLS changes
  nothing below this section.
- **Text frames** carry control messages (JSON, UTF-8).
- **Binary frames** carry file bytes (from Slice B).
- The desktop listens and the phone connects. After the handshake **either side may send
  files**, so who connects does not decide the direction of a transfer.

## Envelope

Every text frame is exactly one JSON object:

```json
{"v": 1, "type": "hello", "id": "5f0c…", "body": { … }}
```

| Field  | Type   | Meaning |
|--------|--------|---------|
| `v`    | int    | Envelope format version. Always `1` in this document. |
| `type` | string | Message type, one of those below. |
| `id`   | string | Unique per message (a random UUID). Errors point back at it with `ref`. |
| `body` | object | The message's fields. Defaults to `{}`. |

### Compatibility rules

1. **Unknown fields** in the envelope or a body are ignored.
2. **Unknown `type`**: reply `error{code:"unsupported_type", ref:<id>}` and keep the
   session open.
3. **Other `v`**: reply `error{code:"version_mismatch"}` and close.
4. **Malformed frame** (bad JSON, missing or wrongly typed required field): reply
   `error{code:"malformed"}`. A peer may close after repeated malformed frames.
5. New optional fields and new message types may be added within version 1 only if the
   new behavior is advertised in `features` (see `hello`) and older peers stay correct
   when they ignore it.

## Handshake

The connecting side sends `hello`. The listening side answers `helloAck` or `error`. No
other message may be sent before the handshake completes.

### `hello` / `helloAck`

Both have the same body:

| Field             | Type     | Meaning |
|-------------------|----------|---------|
| `deviceId`        | string   | Stable random ID of the device, 1–64 chars. Later bound to the pairing certificate. |
| `deviceName`      | string   | Human-readable name, 1–64 chars, no control characters. Untrusted: only displayed. |
| `protocolVersion` | int      | `1`. |
| `features`        | string[] | Optional capabilities. Slice A: `[]`. Slice B adds `"transfer"`. A feature is used only if **both** sides list it. |
| `maxChunkBytes`   | int      | Largest binary payload this side accepts. Default 65536. A sender uses the smaller of the two values. |

The listening side rejects with:
- `version_mismatch` if `protocolVersion` differs, then closes.
- `bad_request` if `deviceId` or `deviceName` break the rules above, then closes.

### `error`

| Field     | Type    | Meaning |
|-----------|---------|---------|
| `code`    | string  | `unsupported_type`, `version_mismatch`, `malformed`, `bad_request`, or a transfer code below. |
| `message` | string? | Human-readable detail, for logs only. |
| `ref`     | string? | `id` of the message the error is about. |

## File transfer (Slice B; requires feature `"transfer"` on both sides)

### Queue model

- A **transfer** is an ordered list of files offered together. Sending several files in
  a queue means offering a list.
- To add more files while a transfer is running, send **another `transferOffer`**.
  Offers are handled in the order they were sent.
- **At most one file is open per session at a time.** The next file starts only after
  the receiver has answered the previous one with `fileResult`. Because of this, binary
  frames never interleave and need no offset.

### Messages, in order

```
sender                                   receiver
  transferOffer {transferId, files}  ──▶
                                     ◀──  transferResponse {transferId, files}   (after the user accepts)
  for each accepted file, in list order:
  fileBegin {transferId, fileId, offset} ─▶
  binary frames …                    ──▶
  fileEnd {transferId, fileId, sha256} ──▶
                                     ◀──  fileResult {transferId, fileId, ok, error?}
  transferDone {transferId}          ──▶
```

| Type               | Body |
|--------------------|------|
| `transferOffer`    | `transferId: string` (UUID). `files: [{fileId: int, name: string, size: long, mimeType?: string}]`. `fileId` values are unique within the transfer. |
| `transferResponse` | `transferId`. `files: [{fileId, accept: bool, resumeOffset: long}]`, one entry per offered file. `resumeOffset` is `0` unless resuming a partial file. Rejecting everything is all `accept:false`. |
| `fileBegin`        | `transferId`, `fileId`, `offset: long` (equals that file's `resumeOffset`). |
| `fileEnd`          | `transferId`, `fileId`, `sha256: string` (lowercase hex of the **whole** file, computed while sending). |
| `fileResult`       | `transferId`, `fileId`, `ok: bool`, `error?: string` (one of the codes below). |
| `cancel`           | `transferId`, `fileId?`. Either side, at any time. Without `fileId` it cancels the rest of the transfer. |
| `transferDone`     | `transferId`. The sender has nothing more for this transfer. |

Transfer error codes: `rejected`, `cancelled`, `checksum_mismatch`, `size_mismatch`,
`too_large`, `io_error`, `unknown_transfer`.

### Binary frames

```
+------------------+------------------------------+
| fileId: u32 (BE) | payload: 1..maxChunkBytes    |
+------------------+------------------------------+
```

- Allowed only between `fileBegin` and `fileEnd` for that `fileId`.
- Bytes are appended in order. The position in the file is implied.
- A frame for any other `fileId`, or a frame larger than the agreed `maxChunkBytes`, is a
  protocol error. The receiver sends `fileResult{ok:false, error:"io_error"}` and drops
  the partial file.

### Receiver rules (security)

- `name` is untrusted. Keep only the final path component. Reject `.`, `..`, empty names,
  path separators and control characters. Never overwrite an existing file; pick a new
  name instead.
- Files are written only to the receiver's chosen download location (desktop: one folder
  the user picked; Android: Storage Access Framework or MediaStore).
- Enforce a size limit before accepting. A file whose received byte count differs from
  `size`, or whose SHA-256 differs from `fileEnd.sha256`, fails and is deleted.
- Nothing is accepted without the user agreeing first (before `transferResponse`).
