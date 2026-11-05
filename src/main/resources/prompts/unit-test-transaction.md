# Relevant policy: transaction
- TX-01: when a mocked `TransactionTemplate` executes `executeWithoutResult(...)`, stub it with `doAnswer(invocation -> { ((Consumer) invocation.getArgument(0)).accept(null); return null; }).when(transactionTemplate).executeWithoutResult(any());` so the production transaction lambda actually runs. Do not merely verify the call while leaving the callback unexecuted.
