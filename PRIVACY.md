# Privacy

Last updated: 13 September 2026

VitalBreathe is an unofficial, open-source community beta for Hammerhead Karoo.
It is designed to work without an account, advertising, analytics, or telemetry.

## The short version

- VitalBreathe does not request Internet access and does not send data to the
  developer.
- Sensor readings and personal settings are processed on the Karoo.
- Breathing data and configured thresholds can be written into a ride's FIT
  file. Karoo may then upload that file to Hammerhead and any services the
  rider has connected.
- Diagnostic material can contain personal information. Review and redact it
  before sharing it in an issue or discussion.

## Data processed on the Karoo

VitalBreathe may process:

- nearby Bluetooth device names and addresses while finding and connecting to
  a breathing sensor;
- the VitalPro's four-character sensor ID and battery level;
- breathing rate, tidal volume, minute ventilation, and breath timing;
- heart rate supplied by Karoo when an enabled field needs it;
- ventilation thresholds and resting or maximum values entered by the rider;
- connection state, retry timing, and error information used for diagnostics.

The sensor ID and personal thresholds are stored in VitalBreathe's private app
preferences. Android backup is disabled for VitalBreathe. Uninstalling the app
or clearing its app data removes these preferences. Karoo's own sensor pairing
record is controlled separately by Karoo.

## FIT files and connected services

When Karoo records a ride, VitalBreathe can add breathing metrics, derived
values, zone information, and the configured threshold values to the FIT file.
That file can also contain location, heart rate, and other ride data collected
by Karoo outside VitalBreathe.

Karoo may upload a completed ride to the Hammerhead Dashboard and to services
the rider has connected. Those transfers are performed by Karoo, not by
VitalBreathe, and are governed by the privacy terms of Hammerhead and the
connected services. Remove or disable a connected service in Karoo if you do
not want it to receive the FIT data.

Karoo may also contact GitHub on the app's behalf to check the public update
manifest and retrieve release assets. VitalBreathe itself still has no network
permission; those system-mediated requests are governed by Hammerhead's and
GitHub's terms.

## Permissions

VitalBreathe requests Bluetooth scan and connection permissions so it can find
and communicate with the strap. On Android 11 and earlier, Android groups
Bluetooth discovery with location permission. VitalBreathe does not read GPS
location. The connected-device foreground-service permission helps keep the
sensor connection alive during a ride.

## Logs, screenshots, and support reports

Release logs replace Bluetooth addresses and four-character strap IDs with a
redacted marker while retaining connection timing and error details. Debug
builds can include the original identifiers and rejected physiological values
needed for development. A system-level capture can also contain information
from outside VitalBreathe. Screenshots and FIT files can reveal health, time,
and location information.

Do not attach an unreviewed FIT file, full log, or screenshot to a public GitHub
issue. Remove device identifiers, exact locations, account details, and health
information that is not needed to reproduce the problem. Security or privacy
reports should use the private process in [SECURITY.md](SECURITY.md).

## Collection by the maintainer

The maintainer does not operate a VitalBreathe server and does not receive app
data automatically. Information a user chooses to post on GitHub is processed
by GitHub under GitHub's own terms. If a user voluntarily supplies diagnostic
material, it is used only to understand and resolve the report; the user should
share the minimum necessary information and may ask for it to be deleted.

## Changes and questions

Material changes to this notice will be recorded in the repository. General
questions can be asked in GitHub Discussions without including personal data.
Report a concern that requires sensitive detail through GitHub's private
vulnerability-reporting link described in [SECURITY.md](SECURITY.md).
