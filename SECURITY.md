# Security

This project communicates with TVs over ADB and provides an Android TV launcher, so a security vulnerability can affect living room devices. Never report security vulnerabilities through public GitHub issues, discussions, or pull requests.

## How to report

Please report all security vulnerabilities privately.

### GitHub Private Vulnerability Reporting

This reporting channel is **not yet active**. GitHub Private Vulnerability Reporting is only available on public repositories once explicitly enabled by maintainers in repository settings.

Once this repository is published publicly and the feature is enabled:

1. Navigate to the **Security** tab of this repository.
2. Select **Advisories** and click **Report a vulnerability**.
3. Provide details of the vulnerability, including affected components, steps to reproduce, and potential impact.

> **Pre-Release Checklist Item for Maintainers:**
> Private vulnerability reporting must be enabled in repository settings (**Settings** > **Code security and analysis** > **Private vulnerability reporting**) prior to or immediately upon public repository launch. Until enabled by maintainers on the public repository, external security researchers cannot submit private advisories.

Please include:
- What component is affected (the launcher, scripts in `tools/`, debloat lists, or build workflows).
- Step-by-step instructions or proof-of-concept to reproduce the issue.
- Potential impact and threat model.

## What counts

- The launcher (`com.gathin.tvhome`) doing something it shouldn't: unexpected network access, leaking data to other apps, or opening unvalidated intents.
- Helper scripts (`tools/tv.py`, `disable.sh`, `restore.sh`, `theme.sh`, `recovery.py`) executing untrusted commands, mishandling paths, or executing arbitrary code.
- Debloat lists leading to an unrecoverable TV state that cannot be restored with `tools/restore.sh`.
- Continuous integration workflows or pull request actions executing untrusted code on contributor or runner environments.

Interpersonal conflicts, harassment, or community conduct violations do not belong in security advisories; please refer to [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) for conduct reporting.

ADB itself is powerful by design. Turning on USB or network debugging lets any computer on your local network ask to connect. Remember to disable network debugging on your TV when not actively configuring it.

## What happens next

Once enabled, reports received through Private Vulnerability Reporting will be reviewed and acknowledged. Verified security fixes will be merged into `main` with proper attribution (unless anonymity is requested). Please practice coordinated disclosure and allow reasonable time for a fix to be published before publicly disclosing details.
