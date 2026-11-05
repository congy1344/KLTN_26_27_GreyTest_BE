# Relevant policy: grpc
- For gRPC/protobuf context, build messages with the generated `newBuilder()` API, use the exact `StreamObserver<T>` response type per RPC, and verify `onNext`/`onCompleted` or `onError` with an `ArgumentCaptor` where the payload matters.
