# SecondLook Shield privacy and source-data notice

Last updated: 2026-10-10

SecondLook does not require an account and has no advertising or analytics.

## On-device checks and storage

Message, link, and call-story analysis runs on the device. The app does not transmit analyzed content or looked-up phone numbers. URLs are inspected locally and are not opened or fetched. The personal blocklist and optional saved result summaries are stored in the app's private local database. History is off by default; saved results contain the explanation and not the original message or link. Turning history off or using Clear history deletes saved summaries.

The app does not read messages in TextNow or other apps. Sharing text is a user action that opens SecondLook with an editable prefill; analysis happens only after the user chooses Analyze. Calling and messaging actions open an Android app chooser and do not automatically place calls or send messages.

## Optional FTC report updates

When the device has internet access, SecondLook can download a public daily CSV file linked by the Federal Trade Commission's Do Not Call reported-calls data page. A background refresh is scheduled with Android WorkManager and requires a connected network; Android may defer it. The user can also request an update from the number lookup screen. Requests fetch a public source file and do not include the queried number, messages, URLs, contacts, blocklist, or call history.

The app parses the file locally and retains only normalized caller numbers, aggregate report counts, latest reported-call timestamps, the source URL/date, retrieval time, and a SHA-256 digest. It discards consumer city/state, subject text, and other CSV fields. Invalid files do not replace the last valid local snapshot. No FTC file is treated as verified evidence, and these records never automatically block a call.

The FTC says these are consumer reports and that none are verified. Caller ID can be spoofed, so a listed number does not prove that it placed a call, who was calling, or where the caller was located. An absent number is not proof of legitimacy. SecondLook does not claim FTC endorsement.

Most FTC website material is described by the FTC as a U.S. government work in the public domain; attribution is requested where feasible. FTC website policy warns that some third-party material may be included and may have separate rights. SecondLook uses the FTC's official linked CSV, gives source attribution, and redistributes only aggregated factual counts and timestamps. [FTC Do Not Call data](https://www.ftc.gov/policy-notices/open-government/data-sets/do-not-call-data) · [FTC website policy](https://www.ftc.gov/policy-notices/website-policy).

## Permissions and call screening

The app uses Internet only for the public FTC CSV update. It does not request SMS, contacts, call log, microphone, precise location, notifications, accessibility service, or default SMS role. Android call screening is opt-in through the system's Call Screening role. Only exact personal blocklist matches are blocked under the corresponding user settings; unknown callers are allowed. Private/withheld caller silencing is separately opt-in. Local data remains subject to the risks of device compromise, backup/OS behavior, and physical access.

## Contact and limitations

The app is guidance, not a guarantee. Message rules are English-focused; URL checks do not inspect live site reputation; the FTC data concerns unwanted calls and is not a list of confirmed scams; Android does not deliver every contact or VoIP call to a call-screening service. No caller's physical location is inferred from a number.
