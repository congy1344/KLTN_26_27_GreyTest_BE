# Relevant policy: switch
- For SWITCH, include every supported case mapping and default behavior in the same rule when source proves them.
- For a normal Java enum/string SWITCH, never describe null as reaching the default branch. A null selector throws `NullPointerException` before case matching unless source explicitly handles null first.
