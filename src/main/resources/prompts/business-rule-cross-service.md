# Relevant policy: cross-service
- dependencyCalls is method-scoped evidence. Use its collaborator/callee method and endpoint only when the same invocation is visible in that method source.
- dependencyCalls[].calleeServiceSourceCode is present only when the called Service method is resolved unambiguously. Use it as supporting evidence for a cross-service invariant that is observable from the selected caller, such as compensation or rollback after a callee failure.
- If calleeServiceSourceCode is absent, do not infer the callee's internal behavior. The field is omitted when resolution is ambiguous or the complete method source exceeds the context limit.
- Never generate a rule for the callee Service, copy its internal decisions as caller rules, or use the callee method id. The output must still target only the selected caller method.
- Do not claim a transaction, rollback, retry, fallback, or failure outcome unless the caller source and calleeServiceSourceCode together prove it.
- Do not turn a dependencyCalls item into a standalone rule unless the call is an independently testable observable side effect of the selected method.
