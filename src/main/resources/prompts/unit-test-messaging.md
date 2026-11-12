# Relevant policy: messaging
    - When stubbing or verifying overloaded methods (such as Spring AMQP `RabbitTemplate.convertAndSend(...)` or `KafkaTemplate.send(...)`), NEVER pass untyped `any()` matchers for multiple arguments (e.g. `verify(rabbitTemplate, never()).convertAndSend(any(), any(), any())` causes compile error 'reference to convertAndSend is ambiguous').
    - If verifying that a collaborator was never invoked in an error branch, use the higher-level assertion: `verifyNoInteractions(rabbitTemplate);`.
    - If verifying a specific overload, use explicit typed matchers, e.g. `verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(AppointmentMessage.class));`.
