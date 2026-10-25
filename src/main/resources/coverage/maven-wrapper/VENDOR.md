# Apache Maven Wrapper fallback

Source: Apache Maven Wrapper Distribution 3.3.4, `only-script`:
https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper-distribution/3.3.4/maven-wrapper-distribution-3.3.4-only-script.zip

`mvnw` is unchanged. `mvnw.cmd` has a marked GreyTest modification: pass its own directory and filename through environment variables and read the script with `File.ReadAllText`, instead of interpolating paths into PowerShell string literals. This supports apostrophes in the exported module path. The temporary environment variables are cleared after bootstrap.

`LICENSE` and `NOTICE` come from the Apache `maven-wrapper-3.3.4` tag. Both are included in exported ZIPs. The fallback properties pin Maven 3.9.14, matching GreyTest's existing Maven wrapper version. User project wrappers and configuration are not overwritten.

When upgrading, reapply the marked Windows path fix and run `CoverageToolBootstrapTest`, including the actual download/cache tests with spaces, Unicode and apostrophes.
