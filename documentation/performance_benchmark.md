# Performance Benchmark

This document captures the current deployed backend response-time profile for Zest's model-backed calls and the likely causes of latency. The measurements below used synthetic ingredient text only; no human scan data, image data, OCR output, prompts, or model responses were stored.

## Tested Service

Base URL:

```text
https://ultraprocessed-ai-proxy-894254677159.us-east1.run.app
```

Working endpoints:

- `POST /analyze`
- `POST /chat`

Observed note:

- `GET /healthz` returned `404` on the deployed service during this benchmark. `/analyze` and `/chat` worked, so the service is reachable, but the deployed revision should expose a health route before production monitoring depends on it.

## Benchmark Summary

### Endpoint-Level Timings

| Endpoint | Sample Count | Observed Latency |
| --- | ---: | --- |
| `/analyze` | 3 quick samples | `4.70s` to `4.90s` |
| `/chat` | 3 quick samples | `2.64s` to `2.94s` |
| `/chat` simpler question | 1 sample | `1.30s` |

Benchmark run against `/analyze`:

```json
{
  "totalRequests": 10,
  "successfulRequests": 10,
  "successRate": 1.0,
  "p50Ms": 4939.31,
  "p95Ms": 7341.21,
  "p99Ms": 7341.21,
  "errors": {}
}
```

### Analysis Samples

| Sample | Payload Chars | Latency | Input Tokens | Output Tokens | Estimated Hidden/Thinking Tokens | NOVA Group |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Simple oats | 90 | `3.88s` | 2817 | 160 | 457 | 1 |
| Cereal | 188 | `5.03s` | 2859 | 336 | 502 | 4 |
| Noisy OCR | 221 | `7.61s` | 2892 | 254 | 988 | 4 |
| Allergen dense | 195 | `6.22s` | 2865 | 323 | 666 | 4 |
| Non-food | 142 | `2.64s` | 2853 | 167 | 198 | 0 |

### 40-Product NOVA Corpus

The corpus in `backend/nova_benchmark_corpus.json` uses Open Food Facts products with populated
`nova_group` and ingredient text. Each row includes the Open Food Facts product code and product URL.
The expected NOVA value is the Open Food Facts `nova_group` value for that product.

Live benchmark command:

```bash
.venv/bin/python backend/benchmark_analyze.py \
  https://ultraprocessed-ai-proxy-894254677159.us-east1.run.app \
  --iterations 1 \
  --timeout 90 \
  --details
```

Result:

```json
{
  "corpusSize": 40,
  "totalRequests": 40,
  "successfulRequests": 40,
  "successRate": 1.0,
  "novaCorrect": 28,
  "novaAccuracy": 0.7,
  "p50Ms": 7727.49,
  "p95Ms": 14551.48,
  "p99Ms": 18015.0,
  "errors": {
    "nova_mismatch": 12
  }
}
```

By expected NOVA group:

| Expected Group | Samples | Correct | Accuracy | p50 | p95 |
| --- | ---: | ---: | ---: | ---: | ---: |
| NOVA 1 | 10 | 8 | `0.8` | `6684.11ms` | `14551.48ms` |
| NOVA 2 | 10 | 6 | `0.6` | `5425.98ms` | `9553.46ms` |
| NOVA 3 | 10 | 4 | `0.4` | `7943.25ms` | `14534.81ms` |
| NOVA 4 | 10 | 10 | `1.0` | `9057.90ms` | `18015.00ms` |

The real-product corpus is harder than the prior synthetic corpus. It includes multilingual ingredient
text, noisy ingredient formatting, and Open Food Facts labels that may reflect database-specific
classification choices. Treat mismatches as review cases, not automatically as model defects. NOVA 4
remains the slowest group because those examples generally produce larger outputs and more
hidden/thinking tokens.

Prompt optimization `backend-prompts` version `1.0.3` targets these benchmark findings:

- keep mineral-water labels classified as food/beverage instead of non-food,
- prevent weak ingredients such as citric acid, enrichment, anti-caking agents, cultures, enzymes, and unmodified starch from forcing NOVA 4,
- handle culinary ingredient products such as oils, salt, sugar, butter, nut butter, and spreadable fats before promoting to NOVA 3/4,
- treat simple processed foods such as bread, cheese, cream cheese, canned foods, jams, ketchup, and sweetened dairy as NOVA 3 unless strong NOVA 4 markers are present,
- use a first-match NOVA decision ladder for easier Gemini 3.5 Flash classification,
- keep output concise without artificial caps that would suppress real ingredients or ultra-processed markers.

The optimized prompt has not been measured on Cloud Run in this document yet. Local direct Vertex
testing was blocked by missing Application Default Credentials. Rerun the 40-product benchmark after
deploying the updated backend prompt.

### Chat Samples

| Question | Latency | Input Tokens | Output Tokens | Estimated Hidden/Thinking Tokens |
| --- | ---: | ---: | ---: | ---: |
| Which ingredient is most concerning? | `2.87s` | 543 | 70 | 239 |
| Why is this NOVA 4? | `2.62s` | 544 | 77 | 169 |
| Are there allergens? | `1.30s` | 541 | 29 | 68 |

## Interpretation

The slower path is not network transfer or Cloud Run routing. Time-to-first-byte and total request time were almost identical during testing, which means most latency occurs while the backend waits for the model response.

The `/analyze` endpoint is slower than `/chat` because it performs the full structured analysis in one Gemini call:

- food/non-food gate,
- cleaned ingredient list,
- ultra-processed marker detection,
- allergen detection,
- NOVA classification,
- short summaries and warnings.

Even tiny ingredient payloads produce about `2.8k` input tokens for `/analyze`, so the backend analysis prompt dominates the request. Chat uses about `540` input tokens for the tested questions, so it has a much smaller model workload.

The slowest `/analyze` samples also show higher estimated hidden/thinking tokens. The noisy OCR sample took `7.61s` and used about `988` hidden/thinking tokens, while the non-food sample took `2.64s` and used about `198`.

## Current Bottleneck

Primary bottleneck:

```text
/analyze -> Gemini full-analysis generation
```

The likely contributors are:

- long backend analysis prompt,
- structured JSON output requirement,
- multi-part reasoning in one call,
- hidden/thinking token budget,
- larger output for full ingredient/allergen/NOVA responses.

The current user-visible "NOVA classification" wait time is therefore the full analysis wait time, not only a standalone NOVA group classification.

## Recommended Backend Changes

### 1. Add Explicit Thinking Budget

Set Gemini thinking behavior explicitly for `/analyze`.

Candidate defaults:

```text
GEMINI_ANALYSIS_THINKING_BUDGET=0
GEMINI_CHAT_THINKING_BUDGET=0
```

If quality drops or the model rejects zero for the selected model, test small fixed budgets:

```text
128
256
```

Acceptance criteria:

- `/analyze` p95 improves against the benchmark corpus.
- JSON validity remains at `100%` for the synthetic corpus.
- NOVA group, allergen, and ultra-processed marker quality does not regress materially in manual review.

### 2. Add Privacy-Safe Timing Instrumentation

Expose backend timing without logging human data.

Recommended fields:

- `requestTotalMs`
- `inputNormalizeMs`
- `promptBuildMs`
- `modelCallMs`
- `jsonCoerceMs`
- `inputTokens`
- `outputTokens`
- `totalTokens`
- `estimatedHiddenThinkingTokens`

Do not log or persist:

- OCR text,
- ingredient text,
- prompts,
- model responses,
- chat questions,
- image paths,
- user identifiers.

Acceptance criteria:

- Engineers can tell whether latency is inside the model call or backend processing.
- Production logs remain free of human scan data and model payloads.
- Timing metadata is optional and can be disabled or hidden from the app UI.

### 3. Keep `/analyze` And `/chat` Tuned Separately

The split endpoints are correct because they have different latency, prompt, output, and safety requirements.

`/analyze` should optimize for:

- strict schema validity,
- ingredient and allergen completeness,
- NOVA correctness,
- bounded output.

`/chat` should optimize for:

- short answers,
- current-result scope,
- session-only chat history,
- prompt-injection resistance,
- low latency.

Acceptance criteria:

- `/chat` changes cannot accidentally expand `/analyze` prompt or output size.
- `/analyze` changes cannot weaken chat scoping.
- Both endpoints have independent prompt files and config budgets.

### 4. Revisit Prompt Size If p95 Remains Above 5s

If explicit thinking budget does not bring `/analyze` p95 below the target, the next lever is reducing prompt and output size.

Options:

- shorten examples in the full-analysis prompt,
- move repeated guidance into compact schema descriptions,
- cap per-field summaries more aggressively,
- return fewer low-value warnings,
- keep only user-visible fields in the response.

Acceptance criteria:

- `/analyze` input tokens drop meaningfully from the current `~2.8k`.
- p95 drops below the product SLO or the product explicitly accepts the tradeoff.
- The prompt remains backend-owned; Android must not send prompts.

### 5. Consider Progressive UX Only If Strict p95 Is Required

If the product requires visible NOVA classification under `5s` at p95, one single full-analysis call may not be enough under all OCR conditions.

An alternate UX can return a fast classification first and fill ingredient/allergen details later. This should be treated as a product decision because it changes the response lifecycle and visible UI behavior.

Acceptance criteria:

- The app clearly separates "classification ready" from "full analysis ready."
- No partial result is persisted beyond the active session.
- Backend prompts remain server-owned.

## Follow-Up Test Cases

### Latency

- Run at least 30 requests against `/analyze` using `backend/nova_benchmark_corpus.json`.
- Run at least 30 requests against `/chat` using representative result questions.
- Track p50, p90, p95, p99, success rate, and HTTP error codes.
- Compare cold-ish and warm Cloud Run behavior separately when possible.

### Correctness

- Use at least 10 synthetic products per expected NOVA group.
- Simple minimally processed food returns NOVA 1 or 3 where appropriate.
- Ultra-processed cereal returns NOVA 4 and identifies industrial markers.
- Noisy OCR still produces valid JSON and bounded warnings.
- Non-food input returns `containsConsumableFoodItem=false`.
- Allergen-dense input detects common allergens without merging them into ingredient coloring.

### Contract

- `/analyze` returns only the existing app contract fields plus optional timing metadata.
- `/chat` returns only the chat contract.
- Android does not send prompts, schemas, API keys, or model configuration.
- Backend prompt changes do not require an Android app release unless the response contract changes.

### Privacy And Logging

- No request body appears in Cloud Run logs.
- No prompt text appears in Cloud Run logs.
- No model response appears in Cloud Run logs.
- No chat question appears in Cloud Run logs.
- Timing and token metadata are safe to log because they do not contain human scan content.

## Current Recommendation

The first implementation step should be explicit Gemini thinking-budget control plus privacy-safe timing instrumentation. That directly targets the observed latency source while preserving the current backend-owned prompt architecture and the one-call `/analyze` contract.
