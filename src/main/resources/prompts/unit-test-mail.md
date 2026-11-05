# Relevant policy: mail
- Do not assert internal implementation details of third-party frameworks or libraries (such as `MimeMultipart` internal structures or transport layers); verify public method return values, state changes, and mock interactions (`verify(...)`).
- Choose public JavaMail assertions according to the approved expected MIME/content behavior. Current multipart construction is observed implementation behavior, not the business oracle. Use compatible public APIs and safe type inspection rather than invalid casts.
- Preserve Unicode subject, body and filename values specified by approved cases; never replace meaningful fixtures with ASCII just to make tests pass. Use public charset-aware APIs when inspecting MIME content.
- JavaMail `Part.getContent()` may return an `InputStream` depending on the active content handler. To assert attachment bytes, read `part.getInputStream()` with a Java-version-compatible loop; never cast `part.getContent()` to `byte[]`.
