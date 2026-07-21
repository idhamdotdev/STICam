# STICam Secure Wire Protocol v2

Status: this document describes the protocol implemented by the current Android and Windows sources. v2 is not compatible with the former unauthenticated plaintext framing.

## Roles and transport

- The Windows host listens on TCP port 8765 and displays a persistent 128-bit pairing key.
- The Android app opens the outbound connection and requires that key before streaming.
- Wi-Fi uses the PC's LAN address. USB uses `adb reverse tcp:8765 tcp:8765`, then Android connects to `127.0.0.1:8765`.
- One full-duplex authenticated and encrypted TCP stream carries H.264 data and JSON commands.

The pairing key is encrypted with Android Keystore on the phone and with Windows current-user DPAPI on the PC. Treat the displayed key as a secret and rotate it if disclosed.

## Handshake

All integers are big-endian and all labels below are UTF-8/ASCII bytes. Each side derives:

1. `baseKey = PBKDF2-HMAC-SHA256(pairingKey, "STICam secure transport v2", 120000, 32)`.
2. Android writes the eight bytes `STICAM2\n`, a random 16-byte `clientRandom`, and `HMAC-SHA256(baseKey, "client" || clientRandom)`.
3. Windows validates the proof, generates `serverRandom`, and writes it followed by `HMAC-SHA256(baseKey, "server" || clientRandom || serverRandom)`.
4. Both derive `sessionKey = HMAC-SHA256(baseKey, "session" || clientRandom || serverRandom)`.
5. Android sends `HMAC-SHA256(sessionKey, "client-finish" || magic || clientRandom || serverRandom || clientProof || serverProof)`.
6. Windows validates that transcript proof before accepting the connection. Directional AES-256 keys are then `HMAC-SHA256(sessionKey, "client-to-server")` and `HMAC-SHA256(sessionKey, "server-to-client")`.

The handshake and first authenticated record each have a ten-second timeout. Authentication failure closes the connection. The client-finished proof binds the complete fresh transcript, so replaying a captured client hello cannot create an accepted connection. This pre-shared-key construction does **not** provide forward secrecy: compromise of the pairing key can expose previously captured sessions. Rotate the key after suspected compromise and do not expose port 8765 to the internet.

## Encrypted records

Each logical packet is first encoded as plaintext `[type:1][payloadLength:4][payload:N]`. It is then protected by AES-256-GCM and sent as:

| Offset | Size | Field |
| --- | ---: | --- |
| 0 | 4 bytes | Encrypted length, including the 16-byte GCM tag |
| 4 | 8 bytes | Per-direction sequence number |
| 12 | N bytes | Ciphertext followed by the GCM tag |

The 12-byte GCM nonce is four zero bytes followed by the sequence number. Additional authenticated data is `STICAM2-PACKET` followed by that same sequence number. Each direction starts at sequence zero and stops before signed 64-bit sequence exhaustion. Receivers require the exact next value, rejecting replayed, missing, or reordered records.

Encrypted records are limited to 20 MiB and must be at least 21 bytes. The inner payload length must exactly match the decrypted data and obey the packet-type limit. Commands are limited to 64 KiB. Invalid sizes, tags, sequences, or inner framing terminate the connection before allocating an attacker-controlled oversized buffer.

## Packet types

| Type | Name | Direction | Payload |
| ---: | --- | --- | --- |
| `0x00` | Frame | Android to Windows | One H.264 access-unit buffer in Annex-B form |
| `0x01` | SPS | Android to Windows | H.264 SPS NAL including its Annex-B start code |
| `0x02` | PPS | Android to Windows | H.264 PPS NAL including its Annex-B start code |
| `0x10` | Command | Both directions | UTF-8 JSON object |

Android replays cached SPS/PPS after connection and may shed non-keyframes under pressure. Windows can request an IDR to recover. Presentation timestamps are not transmitted; Windows assigns a receipt-time timestamp.

## Commands

Every command has a string `cmd` property. Unknown commands and additive properties are ignored.

- Windows to Android: `set_params` optionally carries `zoom`, `face_tracking`, `iso`, `brightness`, `focus`, `flash`, `camera_id`, `resolution`, `ar_filter`, and `lut_filter`; `request_idr` requests a keyframe.
- Android to Windows: `flip` carries `mirrorX` and `mirrorY`; `sync_params` reports camera state and available cameras/resolutions.

Example: `{"cmd":"request_idr"}`

Shutter nanoseconds, focus diopters, and white-balance Kelvin are not part of the interoperable v2 command contract.

## Implementation references

- Android secure channel: `android/app/src/main/java/com/sticam/security/SecureChannel.kt`
- Android packet/parser rules: `android/app/src/main/java/com/sticam/server/StreamProtocol.kt`
- Android stream owner: `android/app/src/main/java/com/sticam/server/StreamServer.kt`
- Windows secure channel: `windows/SticamHost/Security/SecureChannel.cs`
- Windows listener/parser: `windows/SticamHost/Stream/H264Receiver.cs`
- Windows pairing-key storage: `windows/SticamHost/Security/PairingKeyStore.cs`
