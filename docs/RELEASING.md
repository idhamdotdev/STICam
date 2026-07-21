# Releasing STICam

This checklist defines the minimum evidence for a reproducible Android/Windows release. A passing local build is not, by itself, a release artifact.

## 1. Choose an immutable source revision

1. Start from a clean clone.
2. Require the CI workflow to pass.
3. Update and align the Android version code/name, Windows assembly version, and installer version.
4. Commit the release inputs and documentation.
5. Create a signed, annotated Git tag only after the final artifacts have been reproduced and verified.

Record the full Git commit and tag in the release notes. Do not build official artifacts from uncommitted source.

## 2. Pin and verify build inputs

Complete a copy of [release-inputs.example.json](release-inputs.example.json) for every externally supplied binary:

- Android Platform Tools (adb.exe, AdbWinApi.dll, and AdbWinUsbApi.dll)
- the selected FFmpeg build
- the Gradle distribution
- the JDK, Android SDK/build tools, .NET SDK, and packaging toolchain

For each input, record its exact version, authoritative source URL, SHA-256 digest, license, and retrieval date. Keep the completed manifest with the release evidence.

The wrapper pins gradle-8.13-bin.zip with distributionSha256Sum. Whenever the Gradle version changes:

1. Download the distribution and its published .sha256 file from the official Gradle distribution service.
2. Compare the published value with Get-FileHash -Algorithm SHA256 gradle-8.13-bin.zip.
3. Replace distributionSha256Sum in android/gradle/wrapper/gradle-wrapper.properties with the newly verified 64-character digest.
4. Let CI wrapper validation confirm the committed wrapper JAR.

Never guess this value or copy it from an unofficial mirror.

NuGet lock files and strict Gradle dependency locking are enabled. Update them only in reviewed dependency changes. Gradle dependency checksum verification must still be generated and reviewed before claiming byte-for-byte reproducibility.

## 3. Build Android

Prerequisites are JDK 17, the pinned Android SDK/build tools, and a production Android signing identity.

Provide the signing identity only through the protected environment variables `STICAM_ANDROID_KEYSTORE`, `STICAM_ANDROID_STORE_PASSWORD`, `STICAM_ANDROID_KEY_ALIAS`, and `STICAM_ANDROID_KEY_PASSWORD`. Release artifact tasks intentionally fail if any value is absent.

~~~powershell
cd android
.\gradlew.bat --no-daemon clean testDebugUnitTest assembleRelease
~~~

Release gate:

- The release build must not use signingConfigs.debug.
- Keep the keystore and passwords out of Git. Supply them through an approved CI secret store or a protected local signing environment.
- Preserve and back up the production signing identity; future Android updates must use the same authorized identity.
- Verify the final APK signature and certificate with the Android SDK apksigner verify --verbose --print-certs command.
- Confirm package name, version code, version name, supported ABIs, permissions, and install/upgrade behavior on supported devices.

Until production signing is configured, any assembleRelease output is a test artifact and must not be published as an official update.

## 4. Build and publish Windows

The normal dotnet build output is useful for CI but is not the documented standalone release. From the repository root, a reproducible standalone candidate can be produced with:

~~~powershell
dotnet restore windows/SticamHost/SticamHost.csproj --locked-mode

$publishArgs = @(
    'windows/SticamHost/SticamHost.csproj'
    '--configuration', 'Release'
    '--runtime', 'win-x64'
    '--self-contained', 'true'
    '--no-restore'
    '--output', 'artifacts/windows'
    '-p:PublishSingleFile=true'
    '-p:PublishTrimmed=false'
    '-p:ContinuousIntegrationBuild=true'
)
dotnet publish @publishArgs
~~~

The reviewed `packages.lock.json` files are tracked for both Windows projects, and CI restores them with `--locked-mode`. Update the locks only in a dedicated dependency-review change.

Populate windows/SticamHost/tools/ only from the verified inputs recorded in the release-input manifest. Confirm that the packaged application reports an actionable error when an optional runtime tool is absent.

The installer definition and artwork are source inputs and must be version-controlled. Do not build an official installer from an ignored or workstation-only script.

## 5. Sign Windows artifacts

Use a trusted Authenticode code-signing certificate held in a protected signing service or secret store. Do not commit a PFX, its password, or exported private-key material.

Sign the application and installer with SHA-256 and an RFC 3161 timestamp supplied by the certificate provider. A typical Windows SDK command shape is:

~~~powershell
signtool sign /fd SHA256 /td SHA256 /tr <provider-rfc3161-url> /a <artifact.exe>
~~~

The exact certificate selector and timestamp URL are environment-specific and must be reviewed by the release owner. Verify each result:

~~~powershell
Get-AuthenticodeSignature <artifact.exe> |
    Format-List Status,StatusMessage,SignerCertificate,TimeStamperCertificate
~~~

The release gate requires Status to be Valid on both the application and installer.

## 6. Generate checksums and an SBOM

Place only final, signed deliverables in a clean staging directory, then create deterministic SHA-256 entries:

~~~powershell
.\scripts\New-ReleaseChecksums.ps1 -InputDirectory .\artifacts\release
~~~

Publish SHA256SUMS.txt next to the artifacts and verify it from a fresh download before announcing the release.

Generate an SPDX or CycloneDX SBOM with a pinned, reviewed SBOM tool. Record that tool's version and checksum in the release evidence. The SBOM should cover:

- Android direct and transitive dependencies
- NuGet direct and transitive dependencies
- packaged native FFmpeg libraries
- ADB/Platform Tools
- FFmpeg executable and build configuration
- the separately installed OBS Virtual Camera version used for compatibility testing
- fonts and other bundled media with their licenses

A plain dependency listing is useful evidence but is not a complete SBOM.

## 7. Package license and source materials

Include:

- LICENSE
- complete corresponding STICam source for the released revision
- the build/packaging scripts needed to produce the artifacts
- applicable third-party license texts and notices
- required source code or written offers for redistributed copyleft components
- the completed release-input manifest, SBOM, and checksums

Have the final third-party package reviewed for the actual ADB, FFmpeg, font, and NuGet artifacts being shipped. OBS Virtual Camera is detected as an external installation and is not bundled or registered by STICam.

## 8. Final verification

- Install the signed APK on a clean supported Android device and verify an upgrade from the previous public version.
- Install and uninstall the signed Windows package on a clean supported Windows x64 system.
- Test Wi-Fi with the PC IP entered on Android.
- Test USB with a newly authorized ADB device and confirm the reverse tunnel is removed on disconnect.
- Exercise preview, controls, reconnect, recording, and OBS virtual-camera output.
- Test RTSP only with an explicitly configured RTSP server listening on 127.0.0.1:8554.
- Scan the final artifacts, SBOM, and source archives for secrets.
- Verify all published checksums and signatures from a second machine or clean environment.
