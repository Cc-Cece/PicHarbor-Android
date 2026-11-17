# PicHarbor Android diagnostic bundle v1

This bundle is intended for developers and AI. It is exported locally by the user, not sent automatically.

- `summary.md`: coverage overview and interpretation limits.
- `readable.log`: console-style rendering of exactly the same selected events as `events.jsonl`.
- `events.jsonl`: UTF-8, one JSON object per line. `event.schema.json` describes the envelope.
- `environment.json`: current environment at export time, not historical environment.
- `coverage.json`: dropped/corrupt records, recorder health and unavailable evidence.
- `manifest.json`: requested date range, fixed timezone, actual coverage, cutoff and SHA256 of members.

Read events in their stored ingestion order; producer sequence numbers distinguish sources. `event_id` is unique within a producer session. Async interleaving is expected. Compare `mono_ms` only within the same session/boot, never across reboots. UTC time can jump when the device clock changes. Date filtering uses UTC boundaries derived from the user's inclusive local dates, not a fixed 24-hour duration.

Use `job_id` and `item_id` for logical work, `attempt_id` for one execution attempt, and `trace_id`/`parent_span_id` for causal nesting. `span_id` is one specific operation. HTTP events also expose `request_id` equal to their span. `http.started/finished`, `operation.started/finished`, `wait.started/finished` and `engine.call_started/finished` pair by span. `permit.released` is separate from finishing the wait to acquire a permit. `resource.opened/closed` pair by span when emitted by the same producer; Python and Java may each observe their own resource boundary.

Important attrs (numbers use bytes or milliseconds unless stated otherwise):

| Field | Meaning |
| --- | --- |
| operation/action/endpoint | Fixed local labels, not request URLs or bodies |
| outcome | Observed success/error/response_received/unknown; HTTP response receipt does not prove application-level success |
| state/phase/code/reason | Business state, observed phase or stable application error code |
| duration_ms/wait_ms | Monotonic elapsed duration; time may include OS suspension |
| timeout_connect_ms/timeout_read_ms | Effective request timeout settings, not a total wall-clock deadline |
| setting_timeout_ms | User preference at task execution, which can differ from the existing HTTP client's effective setting |
| source_read_bytes | Bytes consumed locally, not proof of network transmission |
| transport_submitted_bytes/remote_confirmed_bytes | Null when unsupported; never substitute source_read_bytes |
| retry_attempt/retry_limit/delay_ms | Retry index, configured budget and backoff |
| in_flight | Bounded snapshot of last-known operations, attempts, start/progress ages |
| active | Number of registered diagnostic spans, including nested spans; NOT the number of concurrent uploaded files |
| concurrency/permit_limit | Configured number of upload lanes |
| error_type/cause_types/stack/errno/category | Safe types, symbols and numeric codes; exception messages and locals are omitted |
| filename | Basename may be retained; no full media paths or contents |
| foreground/network/vpn/network_validated | Observed process and network state, not proof that an endpoint is reachable |
| observer_gap_ms | Delay between monitor observations; a large gap means continuous observation was unavailable |
| rule/certainty | Diagnostic trigger and confidence boundary, never an automatically proven root cause |

The independent Kotlin monitor observes long-running spans without acquiring the Python GIL or business locks. `NO_PROGRESS` is a scope-level suspicion, not proof of a deadlock. Lock/permit waits can be normal. The monitor omits permit waits from its long-operation trigger. Java stacks are best effort and bounded. Python/native stacks and socket-send byte progress are unavailable in this version. No claim of complete DNS/TLS phase timing is made.

Missing end events may result from the selected date range, rolling deletion, queue overflow, abrupt process exit or a genuinely unfinished operation. Check coverage first. A commit timeout leaves the remote outcome unknown: do not infer that uploading again is safe or that local cleanup is authorized. Hash lookup success is not cleanup eligibility.

Local retention is always enabled with fixed limits: up to 14 days or 64 MiB, whichever is reached first. Older data may already be overwritten. No photos, videos, account values, credentials, request bodies, raw URLs, headers or exception messages are intentionally recorded. The user can share this file with their issue description and reproduction steps.
