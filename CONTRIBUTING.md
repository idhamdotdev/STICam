# Contributing to STICam

Thanks for your interest in improving STICam! This is a community project and
contributions are welcome — bug reports, fixes, features, docs, and testing on
devices the maintainer can't reach are all valuable.

## Ways to Help

- **Report bugs** — open an issue with your device, Android/Windows version, and
  clear steps to reproduce. Logs and screenshots help a lot.
- **Test on your hardware** — Android is fragmented; "works on my phone" reports
  (with model + Android version) are genuinely useful.
- **Submit code** — see the workflow below.

For **security issues, do not open a public issue** — follow
[SECURITY.md](SECURITY.md) instead.

## Development Workflow

1. **Fork** the repository and create a branch from `main`
   (e.g. `fix/front-camera-rotation`).
2. Build and test your change — see the **Build Instructions** in the
   [README](README.md) for both the Android client and the Windows host.
3. Keep the change focused: one logical fix or feature per pull request.
4. Open a **pull request against `main`** describing what changed and why, and
   how you tested it.

## What Gets Reviewed Closely

To keep users safe, pull requests that touch any of the following are reviewed
carefully and need a clear justification in the PR description:

- **Networking / the streaming protocol** — anything that changes what is sent
  over the wire.
- **Android permissions** — adding a permission must be justified by a feature.
- **Dependencies** — new libraries add supply-chain risk; prefer the standard
  library or an existing dependency where possible.
- **Build scripts and CI workflows.**
- **Native code.**

PRs with unexplained obfuscated code, new network calls, or new permissions may
be declined.

## Code Style

Match the style of the surrounding code — naming, formatting, and comment
density. Kotlin follows the official style; C# follows standard .NET
conventions. Don't reformat unrelated code in a feature PR.

## License

STICam is licensed under the **GNU General Public License v2.0**. By submitting
a contribution, you agree that it is licensed under the same terms.

## A Note on Response Time

STICam is maintained in spare time by one person. Reviews and replies are
best-effort and may take a while — thanks for your patience.
