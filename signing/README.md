# APK signing key

`trilingo.p12` is the app's signing key. It is encrypted (PKCS#12, AES-256, PBKDF2) and useless
without its password, which is **not** in this repository. The password lives only in the GitHub
secret `SIGNING_PASSWORD` (and in the owner's password manager).

* Every published APK must be signed with this key, otherwise Android refuses to install it as an
  update. The release workflow fails if the secret is missing and checks the signature of the built
  APK against `cert-sha256.txt`.
* Never replace or regenerate this file: installed copies of the app could no longer be updated.
* Local builds without the secret are signed with the Android debug key (fine for testing only).
