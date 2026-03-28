# Login Refactor Design

## Goal

Restore the original `/login` business semantics in the current connector-based architecture: generate the Bilibili QR image locally, persist it to a temp file, send that file through the platform adapter, and keep the platform-specific transport details out of the business layer.

## Hard Constraints

- Keep business-layer and ingress-layer responsibilities explicit and separate.
- Do not introduce Bilibili API usage scenarios that the original project did not use for login.
- Preserve the original login API scope: QR generation, QR polling, and the original post-login follow-group initialization flow.
- For OneBot11-family platforms (`napcat`, `llbot`, generic `onebot11`), send the persisted local QR file through the adapter and let the adapter convert it to `base64://...`.
- For QQ Official and other platforms without local-file image support, keep the existing text fallback behavior.
- Remove the login QR path's dependency on ZXing `BufferedImage` and AWT-specific rendering code by using pure Skia drawing for the login QR image.

## Non-Goals

- Do not reintroduce Mirai-only upload APIs such as `ExternalResource` into the current project.
- Do not add new Bilibili login-side capability beyond what the original project already had.
- Do not promise that the entire Docker image can immediately drop every graphics-related runtime package; only remove login-specific constraints when evidence shows they are no longer required by the rest of the rendering stack.

## Original Project Intent Versus Current Project Boundary

The original Mirai plugin kept the login flow conceptually simple:

1. request a QR URL from Bilibili
2. render a QR image locally
3. hand the image to the messaging runtime for delivery
4. poll the QR login result and persist the cookie

The current project already split runtime messaging into connector adapters. That means the original intent should be preserved, but the Mirai-specific API surface should not be copied. The correct adaptation is:

- `LoginService` owns login orchestration and temp-file lifecycle
- drawing code owns QR image generation
- OneBot11 adapters own conversion from local file to transport-specific image payload
- capability guard and fallback logic stay in the platform messaging layer

This preserves the original flow while keeping the current architecture coherent.

## Chosen Approach

### 1. Business Layer: Persist Before Send

`LoginService` should stop sending `ImageSource.Binary` for `/login`.

Instead, it should:

- request the QR URL
- ask the drawing layer for PNG bytes or a rendered image
- persist the result into `temp/` with a deterministic login-specific filename
- send `OutgoingPart.image(ImageSource.LocalFile(path))`
- delete the temp file when the login flow finishes

This brings the service behavior back to the original "materialize then send" shape without letting the service decide how OneBot11 or QQ Official encode images.

### 2. Drawing Layer: Pure Skia Login QR Rendering

The login QR rendering path should stop using `MatrixToImageWriter.toBufferedImage(...)`.

Instead, the drawing layer should:

- build the QR `BitMatrix` with ZXing
- render matrix cells directly onto a Skia canvas
- render the white/blue center circles and Bilibili logo with Skia
- encode the final image as PNG bytes

This change is limited to the login QR path. It should not force unrelated QR features to change unless they share the same dependency problem and can be migrated safely.

### 3. Adapter Layer: OneBot11 Local File To Base64

The OneBot11-family adapter path should treat a local image file as adapter-owned transport input.

The adapter should:

- read the local file bytes
- encode them as base64
- emit `base64://...` to NapCat, llbot, or generic OneBot11 transport

The business layer should never perform this base64 conversion itself.

This keeps the layering clean:

- business layer decides "send this file"
- adapter decides "this platform needs base64"

### 4. API Usage Constraint

No new Bilibili API call should be added to the `/login` success path by default.

In particular:

- do not add a fresh `userInfo()` call after login success as part of the first-pass fusion
- do not add any new verification endpoint call that the original login flow did not require

If runtime account state needs to be refreshed immediately after successful login, the first choice should be to derive it from the existing login callback URL payload when possible, because that does not expand the API surface. If that proves impossible and a new request becomes necessary, the trade-off must be documented before implementation because it changes the original behavior envelope.

## Expected Behavioral Changes

### Business Logic

- `/login` will send a persisted temp file instead of an in-memory binary payload.
- Login temp files become deliberate runtime artifacts owned by `LoginService`, not incidental diagnostics.
- OneBot11 transport formatting moves fully into the adapter path.

### User-Visible Behavior

- `/login` command syntax and login interaction remain the same.
- On OneBot11-family platforms, the QR delivery path becomes closer to the original project's operational model.
- On QQ Official, users continue to receive text fallback instead of a broken image send attempt.

### Docker And Runtime Impact

- The login QR path should no longer require the ZXing `BufferedImage` route.
- After that change, Docker runtime dependencies should be re-audited.
- If AWT/X11-related packages are still needed, they must be justified by non-login rendering paths or by the current Skiko runtime, not by `/login`.

## Risks And Mitigations

- Risk: changing `LocalFile` handling in OneBot11 may affect other local-image sends.
  Mitigation: lock adapter behavior with regression tests and verify existing local-image paths still pass.

- Risk: pure Skia QR rendering introduces visual or scanability regressions.
  Mitigation: keep the QR matrix, error-correction level, margin, and center-logo geometry consistent with the current output and verify PNG generation with tests.

- Risk: removing login-specific Docker constraints may be over-attributed to `/login`.
  Mitigation: treat Docker package removal as an evidence-based follow-up step, not an assumption.

- Risk: login runtime state may still rely on data previously refreshed by startup-only initialization.
  Mitigation: first try to derive runtime values from the existing login callback payload rather than adding new Bilibili API calls.

## Verification

- Confirm `/login` uses `ImageSource.LocalFile`, not `ImageSource.Binary`.
- Confirm the OneBot11 adapter converts local files to `base64://...`.
- Confirm QQ Official still degrades to text fallback.
- Confirm the login QR rendering path no longer references `MatrixToImageWriter.toBufferedImage(...)` or other AWT-only image materialization steps.
- Re-run login-related regression tests and Docker/login dependency audits after the code change.

## Implementation Outcome (2026-03-28)

- `LoginService` now renders login QR PNG bytes, persists them under `temp/`, sends `ImageSource.LocalFile`, and keeps temp-file cleanup centralized.
- Login callback parsing now extracts `SESSDATA`, `bili_jct`, and optional `DedeUserID` from the existing callback URL payload, so the success path still avoids a new `userInfo()` request.
- The login-specific renderer now lives in `LoginQrCodeRenderer.kt` and paints the ZXing `BitMatrix` directly onto a Skia canvas before applying the center circles and logo overlay.
- `OneBot11Adapter` now owns local-file-to-base64 transport conversion, while QQ Official still rejects local/binary direct-send and falls back through the existing capability-aware text path.
- Docker runtime packages were retained, but the Dockerfile no longer attributes them to `/login`; the remaining justification is the current generic QR helper and shared graphics runtime requirements.
