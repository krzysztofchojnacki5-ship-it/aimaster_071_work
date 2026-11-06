# AI MASTER PRO 0.6.10 — Świadomy jakości routing modeli

- ModelHealthTracker udostępnia Snapshot z liczbą prób, sukcesów, awarii, średnią latencją, ostatnią kategorią błędu i oceną kondycji.
- Historia jest dopasowywana do dokładnego provider/host/model i okna 7 dni.
- Ranking stosuje wygładzanie bayesowskie oraz karę za awarie i wysoką latencję.
- Skonfigurowany model główny nie jest zastępowany na podstawie jednej awarii ani przez niezweryfikowany historią backup. Wymagane są co najmniej dwie obserwacje primary i przynajmniej jeden sukces backupu.
- Dobór agenta nadal uwzględnia dopasowanie kompetencji; ocena jakości treści jest tylko sygnałem rozstrzygającym remisy.
- Dodano testy odporności rankingu na pojedynczą awarię i nieznany backup.
