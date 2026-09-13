# Security

VitalBreathe is an unofficial community beta maintained on a best-effort basis.
Please report security and privacy problems privately so users can be protected
before details become public.

## Supported versions

Security fixes are made for the latest published beta and the current default
branch. Older APKs and development builds are not supported. Reproduce a
problem on the newest release when it is safe to do so.

## Report a vulnerability privately

Use GitHub's
[private vulnerability reporting](https://github.com/misb74/vitalbreathe-karoo/security/advisories/new)
form. Do not open a public issue containing exploit details, Bluetooth
identifiers, FIT files, health data, precise locations, signing material, or
unredacted logs.

If the private form is unavailable, open a public issue saying only that a
private security contact is needed. Do not include the sensitive details. The
maintainer will arrange a private channel.

A useful report includes:

- the VitalBreathe version and build line;
- the Karoo model and Karoo software version;
- a clear description of the impact and the conditions needed to trigger it;
- the smallest safe reproduction steps;
- whether the issue affects the released APK, the source build, or the release
  process.

Share only the minimum diagnostic material needed. Redact sensor IDs,
Bluetooth addresses, ride locations, account details, and physiological data
unless a specific value is essential to the report.

## What to expect

The maintainer aims to acknowledge a report within seven days and provide an
initial assessment within fourteen days. Complex issues or dependencies on a
vendor may take longer. The reporter will be kept informed before coordinated
disclosure where practical.

There is currently no paid bug-bounty programme. Good-faith research that
avoids privacy violations, physical risk, service disruption, and access to
data that is not the researcher's own is welcome.

## Scope

Reports about the VitalBreathe app, build scripts, published APKs, or release
integrity are in scope. Problems in Tymewear products, Hammerhead products or
services, Android, or a connected service should also be reported to the
appropriate vendor. Do not test against another person's device or account and
do not perform testing that could distract a rider or create a safety risk.

The project will never ask a reporter for a release-key password, private key,
account password, or full unredacted ride history.

## Verify a release

Install APKs only from this repository's GitHub Releases page. Verify the
published SHA-256 checksum before installation and compare the signing
certificate fingerprint with the value published alongside the release. Treat
an unexpected certificate change as a security incident and report it before
installing the APK.
