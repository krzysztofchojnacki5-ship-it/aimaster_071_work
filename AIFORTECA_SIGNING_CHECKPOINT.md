# AiFORTeca 0.2 — release signing checkpoint

The release signing keystore was generated locally by the project owner and configured as GitHub Actions repository secrets. No keystore bytes, passwords, aliases beyond the public signing alias, or other credentials are stored in this repository.

The CI workflow on `work/aiforteca-0.2-master-agent-foundation` is responsible for validating the configured secrets, building the release APK, and uploading the signed artifact. A successful CI run and APK certificate verification are required before marking release signing complete.
