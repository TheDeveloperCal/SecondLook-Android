# Changelog

## 1.0.0-rc.1 (verification candidate)

- Add offline phone-number lookup against the personal blocklist and the local FTC reported-calls snapshot.
- Add manual update and network-constrained periodic refresh from the FTC's published daily CSV files.
- Validate CSV schema, number/date fields, size, malformed-row rate, and SHA-256 before transactionally replacing cached data.
- Label FTC consumer reports as unverified; report counts never automatically block calls.
- Preserve the existing blocklist and scan history with additive Room migrations.
- Improve shared-text and number-lookup disclosures and add privacy/source-data documentation.
- Release variant builds unsigned until an authorized signing identity is configured.
