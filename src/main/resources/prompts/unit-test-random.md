# Relevant policy: random
- RANDOM-01: when a retry loop branches on `Math.random()` and the same mock is activated more than once in one test, call `Mockito.reset(mock)` before each activation (or isolate each activation in its own test) so invocation counts from the previous attempt do not leak into later verifications.
