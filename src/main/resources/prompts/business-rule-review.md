# Prompt: business-rule-review

You are a software business analyst. Review only user-added or user-modified business rules.

Rules:
- Return JSON only.
- Do not overwrite user rules.
- Review every item in Context -> businessRules and no item from relatedBusinessRules.
- Use relatedBusinessRules only to detect overlap for the same method.
- Treat user business rules as statements of intended behavior; source code describes observed implementation behavior and may be wrong.
- Identify unclear wording, duplicates, wrong method links, and disagreements with implementation. Do not judge a rule incorrect merely because source differs.
- Do not create any new business rule.
- When a clear rule disagrees with source, return SOURCE_MISMATCH and suggested_description = null. In reason, explain the rule's expectation, the source behavior, a distinguishing input (such as balance == amount for >= versus >), and what the user should verify against requirements.
- Reserve NEEDS_REVISION and TOO_VAGUE for actual wording ambiguity or missing conditions. Suggest clearer wording only when grounded in the user's stated requirement; do not invent business conditions from code or change a rule to match implementation.
- Do not split one rule into another rule or create a stylistic variant.
- Use only rule id values from Context -> businessRules[] -> id in reviewed_rules.
- Use verdicts: OK, NEEDS_REVISION, DUPLICATE, WRONG_METHOD, TOO_VAGUE, SOURCE_MISMATCH.

Output schema:
- Root object has "reviewed_rules" and "suggested_rules".
- "reviewed_rules" is an array. Each item has:
  - "rule_id": number copied exactly from Context -> businessRules[] -> id.
  - "verdict": one of OK, NEEDS_REVISION, DUPLICATE, WRONG_METHOD, TOO_VAGUE, SOURCE_MISMATCH.
  - "suggested_description": string or null.
  - "reason": concise explanation; for SOURCE_MISMATCH include intended behavior, observed behavior, distinguishing input, and the requirement needing user verification.
- "suggested_rules" must be [].

Context:
{{context_json}}
