# Login Refactor Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Refactor `/login` so the business layer persists a QR temp file, OneBot11 adapters convert that file to base64 for transport, QQ Official keeps text fallback, and the login QR rendering path no longer depends on ZXing `BufferedImage`.

**Architecture:** Keep login orchestration in `LoginService`, keep transport encoding in adapters, and isolate the login QR rendering path so it uses pure Skia drawing. Preserve the original Bilibili login API usage envelope and avoid adding new success-path requests unless separately approved.

**Tech Stack:** Kotlin, coroutines, ZXing `BitMatrix`, Skia/Skiko, connector adapters, Gradle/Kotlin test

## Execution Status (2026-03-28)

- Task 1 completed: added the login local-file delivery, OneBot11 local-file image, and pure-Skia login QR regression tests, then verified they failed against the pre-refactor implementation.
- Task 2 completed: isolated the login-only QR renderer into `LoginQrCodeRenderer.kt` and switched it to direct Skia matrix painting while keeping the PNG feature test green.
- Task 3 completed: refactored `LoginService` to persist temp files, updated the login delivery regressions, and added callback parsing coverage for `SESSDATA`, `bili_jct`, and optional `DedeUserID`.
- Task 4 completed: moved OneBot11 local-file base64 encoding into `OneBot11Adapter`, expanded adapter coverage for local/binary/remote images, and kept NapCat base64 payload expectations locked.
- Task 5 completed: extended QQ Official fallback and layering regressions so `/login` remains transport-agnostic and QQ Official keeps explicit local/binary fallback behavior.
- Task 6 completed: re-audited the Dockerfile comment, retained the current graphics packages, and removed the outdated claim that `/login` still depends on the old BufferedImage path.
- Task 7 verification completed through the consolidated login-focused Gradle sweep, final diff review, and commit workflow captured below.

---

### Task 1: Lock The Refactor Constraints With Failing Tests

**Files:**
- Create: `src/test/kotlin/top/bilibili/service/LoginServiceLocalFileDeliveryRegressionTest.kt`
- Create: `src/test/kotlin/top/bilibili/connector/onebot11/OneBot11LocalFileImageRegressionTest.kt`
- Create: `src/test/kotlin/top/bilibili/draw/LoginQrCodePureSkiaSourceRegressionTest.kt`

**Step 1: Write the failing login-service source regression test**

Add a source regression test that reads `src/main/kotlin/top/bilibili/service/LoginService.kt` and asserts:

- `/login` sends `ImageSource.LocalFile`
- `/login` no longer sends `ImageSource.Binary`
- `/login` does not add a new `client.userInfo()` call in the success path

**Step 2: Write the failing OneBot11 adapter regression test**

Add a source or focused behavior test that asserts the OneBot11 image path turns a local file into `base64://...` instead of a `file://...` URL.

**Step 3: Write the failing pure-Skia login QR regression test**

Add a source regression test for the login QR rendering path that asserts the login-specific renderer no longer depends on `MatrixToImageWriter.toBufferedImage(...)`.

**Step 4: Run the targeted tests to verify red state**

Run:

```bash
.\gradlew.bat test --tests top.bilibili.service.LoginServiceLocalFileDeliveryRegressionTest --tests top.bilibili.connector.onebot11.OneBot11LocalFileImageRegressionTest --tests top.bilibili.draw.LoginQrCodePureSkiaSourceRegressionTest
```

Expected: at least one new test fails against the current implementation.

### Task 2: Introduce A Login-Specific Pure-Skia QR Renderer

**Files:**
- Modify: `src/main/kotlin/top/bilibili/draw/QrCodeDraw.kt`
- Optional Create: `src/main/kotlin/top/bilibili/draw/LoginQrCodeRenderer.kt`
- Modify: `src/test/kotlin/top/bilibili/draw/LoginQrCodeBytesFeatureTest.kt`

**Step 1: Split or isolate the login QR renderer**

Refactor the login QR drawing path so the login-specific implementation is clearly separated from the generic QR helpers. If keeping everything in `QrCodeDraw.kt` would blur the source regression boundary, create `LoginQrCodeRenderer.kt` and delegate login rendering there.

**Step 2: Implement matrix-to-canvas drawing**

Implement the login QR renderer so it:

- builds the existing QR `BitMatrix`
- iterates the matrix and paints black/white cells directly on a Skia canvas
- preserves the current center circles and logo overlay
- encodes the final surface as PNG bytes

**Step 3: Update the login QR feature test if needed**

Keep the existing PNG payload assertions and extend them only if needed to cover the refactored renderer entrypoint.

**Step 4: Run the login QR tests**

Run:

```bash
.\gradlew.bat test --tests top.bilibili.draw.LoginQrCodeBytesFeatureTest --tests top.bilibili.draw.LoginQrCodePureSkiaSourceRegressionTest
```

Expected: both tests pass.

### Task 3: Refactor LoginService To Persist Then Send

**Files:**
- Modify: `src/main/kotlin/top/bilibili/service/LoginService.kt`
- Modify: `src/test/kotlin/top/bilibili/service/LoginServiceQrBinaryDeliveryRegressionTest.kt`
- Modify: `src/test/kotlin/top/bilibili/service/LoginServiceQrImageUseRegressionTest.kt`
- Create: `src/test/kotlin/top/bilibili/service/LoginCallbackParsingRegressionTest.kt`

**Step 1: Update the login temp-file workflow**

Change `LoginService` so it:

- generates login QR PNG bytes through the drawing layer
- writes the bytes into a temp file under `temp/`
- sends `OutgoingPart.image(ImageSource.LocalFile(...))`
- keeps temp-file cleanup in one place

**Step 2: Replace outdated binary-delivery regressions**

Update the existing source regression tests so they now assert the persisted local-file delivery path rather than `ImageSource.Binary`.

**Step 3: Add callback parsing regression coverage**

Add a focused test for the login callback parsing helper so it locks:

- `SESSDATA` extraction
- `bili_jct` extraction
- optional extraction of `DedeUserID` from the existing callback URL payload if present

This must use the current callback URL payload shape and must not add any new Bilibili API call.

**Step 4: Keep the success path within the original API envelope**

When adapting the success path, do not add a fresh `userInfo()` request. If runtime identity refresh is implemented, derive it from the existing login callback payload only.

**Step 5: Run the login-service tests**

Run:

```bash
.\gradlew.bat test --tests top.bilibili.service.LoginServiceLocalFileDeliveryRegressionTest --tests top.bilibili.service.LoginCallbackParsingRegressionTest --tests top.bilibili.service.LoginServiceQrImageUseRegressionTest
```

Expected: all targeted login-service tests pass.

### Task 4: Move OneBot11 Local File Encoding Into The Adapter

**Files:**
- Modify: `src/main/kotlin/top/bilibili/connector/onebot11/OneBot11Adapter.kt`
- Modify: `src/test/kotlin/top/bilibili/connector/onebot11/OneBot11AdapterTest.kt`
- Modify: `src/test/kotlin/top/bilibili/napcat/NapCatClientRegressionTest.kt`

**Step 1: Update local-file resolution in the adapter**

Change the OneBot11 adapter so local files are read and converted to base64 transport payloads rather than being forwarded as file URLs.

**Step 2: Keep transport ownership in the adapter**

Do not move file reading or base64 conversion into `LoginService` or any other service class.

**Step 3: Add or update adapter tests**

Add coverage that proves:

- a local file becomes `base64://...`
- binary and remote URL inputs still behave correctly
- existing NapCat log-sanitization or payload expectations still hold

**Step 4: Run the adapter tests**

Run:

```bash
.\gradlew.bat test --tests top.bilibili.connector.onebot11.OneBot11AdapterTest --tests top.bilibili.connector.onebot11.OneBot11LocalFileImageRegressionTest --tests top.bilibili.napcat.NapCatClientRegressionTest
```

Expected: adapter and NapCat tests pass with the new local-file behavior.

### Task 5: Verify QQ Official Fallback And Layer Boundaries

**Files:**
- Modify: `src/test/kotlin/top/bilibili/service/PlatformMessageSupportSourceRegressionTest.kt`
- Modify: `src/test/kotlin/top/bilibili/connector/qqofficial/QQOfficialAdapterTest.kt`

**Step 1: Lock the fallback contract**

Add or update tests so QQ Official still rejects local-file image direct-send support and the business layer continues to fall back to text.

**Step 2: Lock the layering contract**

Add source regression coverage that `LoginService` does not:

- base64-encode image files
- call transport-specific client APIs directly
- special-case NapCat or llbot protocol details

**Step 3: Run the platform fallback tests**

Run:

```bash
.\gradlew.bat test --tests top.bilibili.service.PlatformMessageSupportSourceRegressionTest --tests top.bilibili.connector.qqofficial.QQOfficialAdapterTest
```

Expected: fallback and layering tests pass.

### Task 6: Re-Audit Docker Requirements After The Login Refactor

**Files:**
- Modify: `Dockerfile`
- Modify: `src/test/kotlin/top/bilibili/DockerfileLoginDependencyRegressionTest.kt`
- Optional Modify: `README.md`

**Step 1: Re-run the login dependency audit**

Check whether the Dockerfile still claims `/login` requires AWT/X11 runtime libraries because of `BufferedImage`.

**Step 2: Remove only evidence-backed login-specific claims**

If the login path no longer depends on the old AWT route, update the Dockerfile comment and regression test so they no longer attribute those packages to `/login`.

Do not remove packages blindly. If other rendering paths or the current Skiko runtime still need them, keep them and document why.

**Step 3: Run the Docker/login regression test**

Run:

```bash
.\gradlew.bat test --tests top.bilibili.DockerfileLoginDependencyRegressionTest
```

Expected: the test reflects the post-refactor evidence, not the pre-refactor assumption.

### Task 7: Run The Consolidated Verification Sweep

**Files:**
- Modify: `docs/plans/2026-03-28-login-refactor-design.md`
- Modify: `docs/plans/2026-03-28-login-refactor-implementation.md`

**Step 1: Run the full login-focused test sweep**

Run:

```bash
.\gradlew.bat test --tests top.bilibili.service.LoginServiceLocalFileDeliveryRegressionTest --tests top.bilibili.service.LoginCallbackParsingRegressionTest --tests top.bilibili.draw.LoginQrCodeBytesFeatureTest --tests top.bilibili.draw.LoginQrCodePureSkiaSourceRegressionTest --tests top.bilibili.connector.onebot11.OneBot11AdapterTest --tests top.bilibili.connector.onebot11.OneBot11LocalFileImageRegressionTest --tests top.bilibili.connector.qqofficial.QQOfficialAdapterTest --tests top.bilibili.napcat.NapCatClientRegressionTest --tests top.bilibili.service.PlatformMessageSupportSourceRegressionTest --tests top.bilibili.DockerfileLoginDependencyRegressionTest
```

Expected: all targeted login-related tests pass.

**Step 2: Review the final diff**

Run:

```bash
git diff -- src/main/kotlin/top/bilibili/service/LoginService.kt src/main/kotlin/top/bilibili/draw/QrCodeDraw.kt src/main/kotlin/top/bilibili/connector/onebot11/OneBot11Adapter.kt src/main/kotlin/top/bilibili/connector/qqofficial/QQOfficialAdapter.kt src/test/kotlin/top/bilibili docs/plans/2026-03-28-login-refactor-design.md docs/plans/2026-03-28-login-refactor-implementation.md Dockerfile README.md
```

Expected: the diff only shows the planned login refactor, adapter transport changes, regression tests, and documentation updates.

**Step 3: Commit**

```bash
git add docs/plans/2026-03-28-login-refactor-design.md docs/plans/2026-03-28-login-refactor-implementation.md src/main/kotlin/top/bilibili/service/LoginService.kt src/main/kotlin/top/bilibili/draw/QrCodeDraw.kt src/main/kotlin/top/bilibili/connector/onebot11/OneBot11Adapter.kt src/test/kotlin/top/bilibili Dockerfile README.md
git commit -m "refactor: restore login file-send flow"
```
