# Relevant policy: identity
- Preserve fixed approved identifiers. A constructor that creates a random UUID does not establish an input ID such as "user-1"; use a source-supported constructor/setter or an injectable mock getter for that exact ID. Use an automatically assigned ID only when the approved scenario permits it.
