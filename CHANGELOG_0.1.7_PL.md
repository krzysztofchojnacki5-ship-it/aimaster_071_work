# Wersja 0.1.7 — nowy interfejs w stylu ChatGPT

## Układ
- **Panel boczny z rozmowami** (przycisk ☰ lub zakładka z lewej): „Nowy czat", wyszukiwarka, sekcje „Przypięte" i „Ostatnie". Przy każdej rozmowie menu ⋯ (przypnij, zmień nazwę, usuń; to samo po przytrzymaniu).
- **Górny pasek**: wybór agenta i modelu pod tytułem (dotknij „MASTER ▾"), ikona nowego czatu i menu ⋯. Pod spodem zakładki: Czat · Modele · Agenci · Diagnostyka · Pamięć.
- **Czat**: odpowiedzi AI bez dymka, na całą szerokość; wiadomości użytkownika w zaokrąglonym dymku po prawej. Na tabletach treść jest wyśrodkowana (max ok. 760 dp).
- **Pole wpisywania**: zaokrąglona kapsuła z przyciskiem „+" (załączniki), wielowierszowym polem i okrągłym przyciskiem wysyłania. Podczas generowania przycisk zmienia się w „Stop".
- Przycisk **„↓ na dół"** pojawia się, gdy przewiniesz rozmowę w górę.

## Kopiowanie
- Ikona kopiowania pod **każdą** wiadomością (użytkownika i AI).
- **Bloki kodu** w ramce z nagłówkiem języka i przyciskiem „Kopiuj", przewijane poziomo.
- Cały tekst nadal można zaznaczać przytrzymaniem; „Kopiuj całą rozmowę" w menu ⋯.

## Formatowanie odpowiedzi
Nagłówki, listy punktowane i numerowane, **pogrubienie**, *kursywa*, `kod w linii`, cytaty, linie poziome, bloki kodu i tabele (tabele jako układ monospace z przewijaniem).

## Poprawki
- Tytuły rozmów: poprzednio każda nazywała się „Nowa rozmowa". Teraz tytuł powstaje z pierwszej wiadomości (także dla już zapisanych rozmów).
- Puste nowe czaty nie zaśmiecają już listy rozmów.
- Odpowiedź, która przyjdzie po przełączeniu na inną rozmowę, trafia do właściwej rozmowy.
- Szkic wiadomości nie znika po odświeżeniu ekranu.
- Klawiatura podnosi pole wpisywania; obsługa Androida 15 (tryb krawędź-do-krawędzi).

## Bez zmian
Dostawcy, klucze, agenci, kopie zapasowe i logika wysyłania działają jak w 0.1.6.

> Uwaga: zmiany nie zostały skompilowane w środowisku, w którym powstały (brak Android SDK). Zbuduj projekt w Android Studio (**Build → Make Project**) i zgłoś ewentualne błędy kompilacji.
