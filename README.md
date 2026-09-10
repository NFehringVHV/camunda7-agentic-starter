# camunda7-agentic-starter

A vendor-neutral Spring Boot starter that adds **agentic (tool-calling loop) orchestration** to
[Camunda 7](https://docs.camunda.org/manual/7.24/) using [Spring AI](https://docs.spring.io/spring-ai/reference/).

The starter ships two [external task](https://docs.camunda.org/manual/7.24/user-guide/process-engine/external-tasks/)
workers that, together with a small BPMN convention, let a BPMN process run an LLM in a loop:
the model reasons, picks a tool, the tool (a BPMN sub-process) runs, its result is fed back, and
the loop repeats until the model produces a final answer or a budget is exhausted.

It is **provider-neutral**: the starter depends only on the generic Spring AI `ChatModel` API. You
bring your own model by adding any Spring AI model starter (OpenAI, Azure, AWS Bedrock, Ollama, …)
to your application — see [`camunda7-agentic-examples`](../camunda7-agentic-examples) for a runnable
AWS Bedrock worker and a matching Camunda 7 process app.

> **License:** Apache-2.0. **Status:** early (`0.x`) — APIs may still change.

---

## How it works

![The agentic-demo process: an LLM Agentic service task loops through a gateway and a tool-correlation task; two event sub-processes (getWeather, sendEmail) act as tools.](docs/agentic-demo.png)

```
                    ┌──────────────────────── BPMN process ─────────────────────────┐
                    │                                                                │
   start ─▶ (set userPrompt) ─▶ [Service Task: topic llm-agentic] ─▶ <gateway>      │
                    │                                        agenticDone == true ────┼──▶ end
                    │                                        agenticDone == false    │
                    │                                             │                  │
                    │           [Service Task: topic agentic-tool-correlation]       │
                    │                        │ correlates a message to start the     │
                    │                        ▼ chosen tool (event sub-process)       │
                    │                 (tool runs, sets toolCallResult) ──────────────┘ (loop back)
                    └────────────────────────────────────────────────────────────────┘
```

1. **`llm-agentic` worker (`LlmAgenticWorker`)** — builds the system prompt (business context +
   the tools discovered in the BPMN), sends the conversation history to the LLM, parses the JSON
   response, and writes the outcome (`agenticDone`, next tool call, updated history, token counters)
   back onto the process instance.
2. **`agentic-tool-correlation` worker (`AgenticToolCorrelationWorker`)** — takes the tool the LLM
   picked and correlates a Camunda message to trigger the corresponding tool sub-process.

Tools are **discovered from the BPMN itself** — no separate tool registry. See
[Tool convention](#tool-convention).

---

## Requirements

- Java 21+
- Spring Boot 4.x
- A running Camunda 7 engine reachable over the REST API (the workers are external-task clients).
- One Spring AI model starter on your application classpath (provides the `ChatModel` bean).

## Installation

```xml
<dependency>
  <groupId>io.github.camunda7-agentic</groupId>
  <artifactId>camunda7-agentic-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>

<!-- Bring your own provider, e.g. AWS Bedrock: -->
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-starter-model-bedrock-converse</artifactId>
</dependency>
```

The starter auto-configures itself (`AgenticC7AutoConfiguration`) as soon as it is on the classpath
and a `ChatModel` bean exists.

---

## Configuration

All properties use the `agentic.c7` prefix.

### Connection

| Property | Default | Description |
| --- | --- | --- |
| `agentic.c7.client.base-url` | `http://localhost:8080/engine-rest` | REST endpoint the external-task client fetches from. |
| `agentic.c7.client.username` / `.password` | – | Optional basic auth for fetch-and-lock. |
| `agentic.c7.client.enabled` | `true` | Start the subscriptions. |
| `agentic.c7.client.agentic-topic` | `llm-agentic` | Topic of the agentic turn worker. |
| `agentic.c7.client.tool-correlation-topic` | `agentic-tool-correlation` | Topic of the tool-correlation worker. |
| `agentic.c7.client.agentic-min-lock-ms` | `300000` | Minimum lock for the (slow) LLM topic. |
| `agentic.c7.camunda.base-url` | `http://localhost:8080/engine-rest` | REST endpoint used to load BPMN XML and correlate tool messages. |
| `agentic.c7.camunda.username` / `.password` / `.bearer-token` | – | Optional auth for those REST calls. |

### Behaviour

| Property | Default | Description |
| --- | --- | --- |
| `agentic.c7.default-tool-call-variable-name` | `toolCall` | Process variable holding the tool-call arguments. |
| `agentic.c7.history-max-turns` | `20` | Max history turns sent to the LLM (`0` = unlimited). |
| `agentic.c7.tool-call-result-max-chars` | `8000` | Max chars of a tool result embedded in the prompt (`0` = unlimited). |
| `agentic.c7.default-max-iterations` | `10` | Loop iteration cap (`0` = unlimited). |
| `agentic.c7.default-max-tokens` | `0` | Cumulative token budget (`0` = unlimited). |
| `agentic.c7.prompt-full-tool-result-turns` | `2` | Most-recent turns whose full tool result is embedded. |
| `agentic.c7.business-error-mode` | `incident` | How business-level failures are surfaced: `incident` or `bpmn-error` (see [Error handling](#error-handling)). |

Most behaviour settings can be overridden **per process instance** via process variables
(`toolCallVariableName`, `maxIterations`, `maxTokens`, `model`, `temperature`).

### Conversation history storage

The loop keeps a growing conversation/tool-result history. Because Camunda 7 caps `String` process
variables at `varchar(4000)`, the starter offers a small history SPI with **three tiers**, selected
with `agentic.c7.history.store`:

| `store` | Where it lives | Size limit | Extra deps | Cockpit-readable |
| --- | --- | --- | --- | --- |
| `inline` | Camunda `String` variable | ~4000 chars | none | yes |
| `camunda-bytearray` **(default)** | Camunda `ACT_GE_BYTEARRAY` (as `Bytes` or `Json`) | DB/transaction limited (MB range) | `json` mode needs camunda-spin on the engine | `Bytes`: download only · `Json`: yes |
| `external` | Your own store (S3, blob service, …) | your choice | your `AgenticBlobStore` bean | your choice |

`camunda-bytearray` wire type is set with `agentic.c7.history.byte-array-type`:

- `bytes` **(default)** — `byte[]` process variable, zero extra dependencies, Cockpit shows a
  download link.
- `json` — SPIN JSON value, **readable in Cockpit**, but requires `camunda-spin-dataformat-json`
  on the *engine* (standard in Camunda Run and the Spring Boot distributions).

> **Why `camunda-bytearray`/`bytes` is the default:** it removes the 4000-char limit with **zero**
> extra dependencies and works fully offline. Switch to `json` if you want the history to be
> human-readable in Cockpit and your engine already has camunda-spin. Switch to `inline` only for
> very small demos.

**Bring your own store (`external`).** Implement the `AgenticBlobStore` SPI and publish it as a
Spring bean; it automatically replaces the built-in default. Selecting `store: external` **without**
such a bean fails fast at startup.

```java
@Bean
AgenticBlobStore s3BlobStore(S3Client s3) {
    return new AgenticBlobStore() {
        @Override public String write(String content) { /* PUT, return an id/key */ }
        @Override public String read(String id)       { /* GET by id/key */ }
    };
}
```

```yaml
agentic:
  c7:
    history:
      store: external
```

**Trade-offs to keep in mind:** the full history is loaded into memory each turn and re-persisted;
with `camunda-bytearray` it inflates the engine database and backups. For high-volume or
long-running processes, prefer the `external` tier to offload payloads out of the engine.

---

## Tool convention

A **tool** is a BPMN **event sub-process** started by a **message start event**. The workers
discover tools by scanning the deployed BPMN; no separate registration is needed.

- **Tool name** = the message name of the sub-process's message start event.
- **Description** (what the LLM sees) = the sub-process `bpmn:documentation`.
- **Arguments** = `camunda:property` entries whose value is
  `fromAi("<toolCallVariable>.<argName>", "<argDescription>")`.

```xml
<bpmn:subProcess id="Tool_getWeather" name="Tool: getWeather" triggeredByEvent="true">
  <bpmn:documentation>Returns the current weather for a location.</bpmn:documentation>
  <bpmn:extensionElements>
    <camunda:properties>
      <camunda:property value='fromAi("toolCall.location", "the city to look up")'/>
    </camunda:properties>
  </bpmn:extensionElements>
  <bpmn:startEvent id="Start_getWeather">
    <bpmn:messageEventDefinition messageRef="Message_getWeather"/>
  </bpmn:startEvent>
  ...
</bpmn:subProcess>
```

---

## Process contract

**Input** (set before the first `llm-agentic` service task):

| Variable | Required | Description |
| --- | --- | --- |
| `userPrompt` | yes* | The user request / task for the agent. |
| `userPromptBlobId` | – | Alternative to `userPrompt` for large inputs (needs an `AgenticBlobStore`). |
| `systemPromptUseCase` / `systemPromptUseCaseBlobId` | – | Extra business context prepended to the system prompt. |
| `model` | – | Per-instance model id override. |
| `temperature` | – | Per-instance temperature override. |
| `maxIterations` / `maxTokens` | – | Per-instance budget overrides. |

\* Either `userPrompt` or `userPromptBlobId` must be present.

**Output** (written by the workers):

| Variable | Description |
| --- | --- |
| `agenticDone` | `true` when the loop finished — use it on the gateway after the `llm-agentic` task. |
| `agenticFinalAnswer` | The model's final answer. |
| `agenticAbortReason` | Set when the loop stopped due to a budget/limit. |
| `agenticIteration`, `agenticTokensUsed`, `agenticInputTokensUsed`, `agenticOutputTokensUsed` | Running counters. |
| `agenticReasoning`, `agenticNextStepPlan` | Diagnostics for the last turn. |
| `agenticHistory` | The serialized conversation/tool-result history, when `store` is `inline` (String) or `camunda-bytearray` (`Bytes`/`Json`). |
| `agenticHistoryBlobId` | Stable id of the externally stored history, when `store` is `external`. |

## Error handling

The workers distinguish **technical** from **business** failures:

- **Technical failures** (LLM call, Camunda REST, blob store throwing an exception) are always
  reported via `handleFailure` with `retries = 0`, i.e. they become a Camunda **incident**
  immediately. Retrying the same task would not help, and an operator should see it.
- **Business failures** (`LLM_INPUT_MISSING`, `LLM_INPUT_BLOB_UNREADABLE`, `AGENTIC_NO_TOOL`,
  `LLM_TOOLCALL_MISSING`) are surfaced according to `agentic.c7.business-error-mode`:

| `business-error-mode` | Behaviour |
| --- | --- |
| `incident` **(default)** | Reported via `handleFailure` (retries = 0) → **incident**. The error code is prefixed onto the incident message. No BPMN modelling required. |
| `bpmn-error` | Raised via `handleBpmnError` so the process can catch it on a boundary / event sub-process **error event** and route to a business fallback. Unhandled BPMN errors still become incidents. |

> The default is `incident` because it works without any error-catch events on your BPMN. Switch to
> `bpmn-error` once you model explicit fallback paths for these conditions. In `bpmn-error` mode the
> concrete reason is additionally written to `agenticWorkerErrorCode` / `agenticWorkerErrorMessage`
> process variables so it stays visible in Cockpit.

---

## Roadmap / ideas for extension

These are not implemented yet — they are directions the starter could grow in. Contributions and
design discussions are welcome (see [CONTRIBUTING.md](CONTRIBUTING.md)).

- **MCP (Model Context Protocol) integration** — model an [MCP](https://modelcontextprotocol.io/)
  server as a *single* BPMN event sub-process ("tool"). The agent decides to call that MCP
  sub-process and picks the concrete MCP tool to invoke; the sub-process forwards the call to an MCP
  client. In the BPMN you then see *that the MCP tool was called*, but not which concrete MCP tool —
  that selection happens dynamically at runtime (the argument resolver would carry the chosen MCP
  tool name + arguments). Keeps the process model stable while the MCP server's tool catalogue can
  evolve independently.
- **Parallel tool calls** — let the model request several tool invocations in one turn and fan them
  out concurrently: emit multiple correlation messages, then collect the results with a parallel
  multi-instance receive before the next LLM turn. Sketch:

  ```xml
  <receiveTask id="waitForResults" name="Wait for tool results" camunda:variableName="result">
    <multiInstanceLoopCharacteristics isParallel="true">
      <completionCondition>${false}</completionCondition>
      <loopCardinality>${expectedResults.size()}</loopCardinality>
      <loopDataInputRef>expectedResults</loopDataInputRef>
      <inputDataItem name="currentKey" />
    </multiInstanceLoopCharacteristics>
    <messageEventDefinition messageRef="subprocess_result" />
  </receiveTask>
  ```

  The interesting part is the **receive** side: aggregating N tool results (one message correlation
  per instance) back into a single history update before looping back to `llm-agentic`.
- **Distinct abort reasons for budget limits** — today a limit-triggered stop is signalled via
  `agenticAbortReason`. Add dedicated flags/codes so a stop caused by the **iteration budget**
  (`maxIterations`) or the **token budget** (`maxTokens`) can be told apart from a "normal" model-
  initiated abort. This lets the process branch differently (e.g. escalate on budget exhaustion vs.
  accept a model-declared no-op).
- **Vector DB / RAG integration** — enrich the system prompt (or expose a `retrieve` tool) with
  context fetched from a vector store via Spring AI's `VectorStore` / retrieval-augmented-generation
  support, so the agent can ground its answers in your own documents.
- **Revisit & document the prompt/history strategy** — the current behaviour (how much history is
  sent to the model) is a hybrid controlled by three properties: `history-max-turns` (how many past
  turns are included at all), `prompt-full-tool-result-turns` (for how many of the *most recent*
  turns the full tool result is embedded) and `tool-call-result-max-chars` (per-result truncation).
  This windowing logic should be thought through cleanly (token cost vs. loss of context, what
  "a turn" means, interaction with the token budget) and documented in a dedicated section, so users
  understand exactly what the LLM sees and why.
- **UI / cockpit for agent runs** — a small viewer to inspect an agent run more comfortably:
  the agent history (per-turn reasoning, tool calls, tool results), cumulative and per-turn token
  usage, iteration count, and the abort reason. Could be a Camunda Cockpit plugin or a standalone
  web view reading the history store / process variables.
- **Freshness / TTL for tool-call results** — consider recording *when* each `toolCallResult` was
  produced (timestamp per history entry), so the validity of an observation can be controlled: e.g.
  mark or drop results older than X days/hours, or force a re-fetch of stale data before the model
  relies on it. Useful for long-running processes where a cached tool answer may no longer be valid.

---

## Building

```bash
mvn verify
```

Java 21 and Maven are required. The build is fully offline-capable (no LLM provider needed to
compile or test).

## Examples

See the [`camunda7-agentic-examples`](../camunda7-agentic-examples) repository for an end-to-end,
runnable setup (`example-process` + `example-worker`).

## Contributing / Security

See [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md).
