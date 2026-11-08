# Android 1.0.9-beta.5 implementation

This branch starts at `main` commit `1febf56`, independently of the Live Photo/link beta branch. The design documents describe the longer-term target. This file records what this release actually implements.

Implemented:

- Always-on, application-private, asynchronous bounded recorder; 14-day / 64 MiB automatic rolling retention without user configuration.
- Structured JSONL and console-style rendering generated from stable read handles to the same selected byte ranges.
- Settings export only, all retained dates by default, optional inclusive local date range with calendar/DST-aware UTC conversion, saved picker range across Activity recreation.
- ZIP with environment, coverage, manifest/member hashes, schema, field guide and summary. No account, engine or business database access is required to export.
- Kotlin/Python contexts, mobile HTTP start/finish, protocol-operation wrappers, retry/source/hash/lock events, upload attempts and permit waits, scheduler start/stop and cleanup boundary events.
- Independent Kotlin monitor, bounded Java stacks, resource/network snapshots, main-thread heartbeat, next-launch exit summaries and a minimal uncaught-exception record.
- Allowlisted safe fields, basename support, secret/account/URL masking before persistence; no original request bodies, headers, credentials or exception messages.

Known evidence limits are also included in every exported bundle:

- Python/native stacks and actual socket-send progress are unavailable. DNS/TLS phase timing is not separately instrumented.
- In-flight diagnostic span count includes nesting and is not equivalent to active upload count.
- This release observes upload stalls; it does not change cancellation, upload recovery or unknown-commit behavior.
- The target's full persistent state revision audit, comprehensive cleanup evidence projection, automated root-cause classifier and cross-device tracing are not implemented by this beta. Main has no newer paired-transfer feature to instrument.
- Unexpected exits do not guarantee a final record. ExitInfo availability depends on Android; raw system traces are not exported. A missing clean-close marker alone cannot distinguish force-stop from crash.
- Bounded queues and storage may drop/evict records. Coverage and health fields report known gaps; absence of an event is not proof the action did not happen.

Validation results and release links should accompany the release/commit, rather than claiming all future-design acceptance scenarios have passed.
