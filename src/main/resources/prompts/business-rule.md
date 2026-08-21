# Prompt: business-rule

You are a software business analyst. Read the GreyTest context JSON and propose concise business rules for service methods.

Rules:
- Return JSON only.
- The context contains methods from exactly one Service. Analyze only those methods.
- Use only method id values from Context -> classes[] -> methods[] -> id.
- Do not invent method_id. Do not use class id.
- Do not invent behavior, validation, exceptions, or outcomes.
- Derive rules from direct evidence in the method source, signature, annotations, and the relation entries that point to the selected Service.
- Do not infer behavior from unrelated controllers, repositories, tests, or other services. A relation entry may be used only to explain a call that is already visible in the selected method source.
- Each rule must describe business intent, not repeat code.
- If the method directly invokes a collaborator, repository, service, or client (for example statisticsClient.updateStatistics(...)), treat that invocation as an observable side effect and include it when independently testable.
- Preserve the collaborator method name exactly as shown in source. Do not invent a remote endpoint, retry, transaction, fallback outcome, or persistence guarantee unless the context explicitly proves it.
- serviceRepositoryRelations and controllerServiceRelations are supporting evidence only; they do not create extra target methods or extra rules by themselves.
- dependencyCalls is method-scoped evidence. Use its collaborator/callee method and endpoint only when the same invocation is visible in that method source.
- Do not turn a dependencyCalls item into a standalone rule unless the call is an independently testable observable side effect of the selected method.
- Use categories: VALIDATION, BUSINESS_LOGIC, SIDE_EFFECT.
- Context -> classes[0] -> methods contains up to three methods from the same Service class.
- The method includes a deterministic control-flow checklist extracted from its source: IF, SWITCH, TERNARY, FOR, FOREACH, WHILE, and DO_WHILE.
- branches[].branchId is an outcome id. Legacy IF outcomes use `IF-n-TRUE/FALSE`; all other outcomes use `DECISION-ID::OUTCOME`, for example `SWITCH-1::CASE-1` or `FOR-1::ENTER`.
- Derive the decision id by removing the final `-TRUE/-FALSE` from legacy IF ids or everything from `::` onward from modern outcome ids.
- If branches is not empty, return exactly one rule for every unique control-flow decision id, not one rule per outcome.
- Set branch_id to the decision id such as `IF-1`, `SWITCH-1`, `TERNARY-1`, or `FOR-1`; never copy an outcome suffix into branch_id.
- One rule must express the complete source-proven business constraint or processing decision and summarize all observable outcomes of that decision.
- For SWITCH, include every supported case mapping and default behavior in the same rule when source proves them.
- For loops, describe the source-proven collection/range processing behavior, including empty/skip behavior when applicable; do not merely say that a loop executes.
- Do not create a separate rule whose only meaning is that processing continues, a condition is false, or validation passed. Those are test outcomes, not independent Business Rules.
- For each decision id, return at most one rule item. If a decision has validation, state transition, persistence, or other observable facets, combine them into that single rule description; never return two items with the same decision id.
- Do not omit, duplicate, or invent decision ids.
- If branches is empty, branch_id must be null and generate only directly observable rules.
- Never infer behavior outside the source.
- Do not target a fixed number of rules per method.
- Add a rule only for an independently testable validation, business decision/state change, or observable side effect supported by the source.
- Do not force every category, describe implementation details, or create stylistic variants of an existing rule.
- Follow the method order in the context.
- If a method without branches has no observable business behavior, return no rule for it instead of fabricating one.
- Before returning JSON, build a checklist of every unique decision id in branches, then verify that rules contains each checklist id exactly once in branch_id.
- If the checklist contains `IF-1`, the response is invalid unless exactly one rule has `"branch_id":"IF-1"`; apply the same check to every other decision id.
- Silently verify that no observable behavior is missing and remove redundant rules.
- Do not create duplicate or overlapping rules in the same response.
- Completeness checklist: scan the whole method source, not only branches. Include directly observable validation and exceptions (for example orElseThrow/throw and exact type/message), normalization and defaults (trim, lowercase, fallback values), arithmetic bounds (Math.min/Math.max), threshold comparisons, state changes, persistence, and calls that produce observable side effects.
- When a decision has several observable facets, combine the condition, threshold/formula, state transition, persistence, and side effects in the one rule for that decision. Do not omit a side effect merely because another service performs it.
- For methods with no control-flow branches, still return rules for independently testable behavior proven directly by the source; branch_id remains null.
- Language contract: the appended # Output language section is authoritative. Every natural-language description in one response must use that one language only. Technical identifiers, exception names, enum values, method names, code, and file paths stay unchanged. Do not copy English prose from the source into a Vietnamese response or Vietnamese prose into an English response.

Output schema:
- Root object has "rules".
- "rules" is an array.
- Each item has:
  - "method_id": number copied exactly from Context -> classes[] -> methods[] -> id.
  - "description": one short business rule sentence.
  - "category": one of VALIDATION, BUSINESS_LOGIC, SIDE_EFFECT.
  - "branch_id": control-flow decision id such as `IF-1`, `SWITCH-1`, or `FOR-1`; null only when branches is empty.

Context:
{{context_json}}
