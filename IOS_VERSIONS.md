# iOS version catalogue

This catalogue tracks Apple's released iPhone operating-system generations and ASOFT's implementation status. A version listed here is not automatically installable: only versions marked **Available** have an installation path in the Android app.

## Availability

| Generation | Version family | ASOFT status | Notes |
|---|---|---|---|
| iPhone OS 1 | 1.0–1.1.5 | Planned | Original iPhone OS generation; legacy boot and hardware profile required |
| iPhone OS 2 | 2.0–2.2.1 | Planned | Legacy generation |
| iPhone OS 3 | 3.0–3.2.2 | Planned | Legacy generation |
| iOS 4 | 4.0–4.3.5 | Planned | Legacy generation |
| iOS 5 | 5.0–5.1.1 | Planned | Legacy generation |
| iOS 6 | 6.0–6.1.6 | Planned | Legacy generation |
| iOS 7 | 7.0–7.1.2 | Planned | Legacy generation |
| iOS 8 | 8.0–8.4.1 | Planned | Legacy generation |
| iOS 9 | 9.0–9.3.6 | Planned | Legacy generation |
| iOS 10 | 10.0–10.3.4 | Planned | Legacy generation |
| iOS 11 | 11.0–11.4.1 | Planned | Legacy generation |
| iOS 12 | 12.0–12.5.7 | Planned | Legacy generation |
| iOS 13 | 13.0–13.7 | Planned | Legacy generation |
| iOS 14 | 14.0–14.8.1 | Planned | Legacy generation |
| iOS 15 | 15.0–15.8.5 | Planned | Legacy generation |
| iOS 16 | 16.7.16 | Available — validation in progress | Current Android setup path; iPhone X restore image |
| iOS 17 | 17.0–17.7.10 | Planned | Requires a compatible guest boot/install pipeline |
| iOS 18 | 18.0–18.7.2 | Planned | Requires a compatible guest boot/install pipeline |
| iOS 26 | 26.x | Available — experimental | Separate newer-generation setup path; shares cloudOS research components |

## Numbering note

Apple did not release public iOS major versions 19 through 25. The numbering moved from iOS 18 to iOS 26; those skipped numbers are intentionally not listed as operating-system releases.

## Support policy

- **Available** means ASOFT has an installation path for that generation.
- **Experimental** means the path exists but must not be presented as stable until device testing passes.
- **Planned** means the generation is catalogued for future engineering; it is not selectable or installable yet.
- A release must not be marked supported merely because its IPSW can be downloaded. The guest kernel, device tree, restore image layout, boot arguments, disk construction and first boot all need a compatible implementation.
- Legacy iPhone OS generations may require separate 32-bit/older-device emulation work. They will be evaluated independently rather than forced through the iOS 16 or iOS 26 installer.
- The performance target remains **25 minutes or less for a complete first-time setup**, measured from the user's setup action through the first usable boot. Each generation must be benchmarked separately; no timing is promised before measurement.

## Implementation order

1. Stabilise and benchmark the existing iOS 16 setup on the Galaxy S22 Ultra.
2. Validate the existing iOS 26 experimental path.
3. Investigate iOS 15–18 compatibility, starting with the closest generation to the existing iOS 16 pipeline.
4. Prototype legacy iOS 1–14 generations with the appropriate historical device and architecture profiles.
5. Add a generation to the in-app selector only after its setup and first boot pass repeatable tests.
