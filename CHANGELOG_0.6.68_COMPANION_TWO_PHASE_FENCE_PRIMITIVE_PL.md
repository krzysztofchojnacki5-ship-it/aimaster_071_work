# 0.6.68-SOURCE — Companion two-phase revocation primitive

- Dodano `CompanionConnectionRegistry.beginRevocation()` oraz idempotentny `RevocationBatch`.
- Oddzielono atomową zmianę epoki/odłączenie uchwytów od potencjalnie blokującego zamykania socketów.
- `revoke()` zachowuje kompatybilną semantykę i korzysta z nowego mechanizmu dwufazowego.
- Test `beginRevocationFencesAdmissionBeforeDeferredSocketClose` weryfikuje natychmiastowe odrzucenie starego ticketu i odroczone zamknięcie.
- 26/26 metod testowych registry PASS w lokalnym harnessie JVM.
- Store nie jest jeszcze spięty z fence; atomowość na granicy commit→epoch pozostaje OPEN.
