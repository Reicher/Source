# Source – projektdefinition

## Översikt

Source är ett privat, local-first system för personlig data, lagring och AI.

Systemet består huvudsakligen av:

* **Source Client** – den officiella klientapplikationen som användaren arbetar i.
* **Source Node** – en kraftigare lokal server som erbjuder lagring, backup, synk, AI och andra tjänster.
* **Source API** – det stabila gränssnittet mellan Client, Node och andra Source-kompatibla appar.

Source ska fungera helt utan externa molntjänster. Varken Client eller Node ska behöva kommunicera med internet för Sources kärnfunktioner.

Den viktigaste principen är att användarens data är **lokal, privat och krypterad**.

---

## Source Client

Source Client är användarens primära gränssnitt mot Source.

Den ska kunna installeras och användas helt självständigt utan någon Source Node.

Vid första start skapar användaren en lokal identitet med användarnamn och lösenord. Användaren autentiserar sig varje gång klienten öppnas.

Client ansvarar bland annat för:

* användarens identitet och nycklar
* lokal krypterad lagring
* lokal AI när Node inte finns
* offline-funktionalitet
* anslutning till betrodda Nodes
* synk och backup
* presentation och visualisering av data

All användarorienterad visualisering ska huvudsakligen ske i Client. Node kan bearbeta och sammanställa information, men Client bestämmer hur den visas.

---

## Source Node

Source Node är en kraftigare lokal Source-instans, normalt placerad på ett betrott lokalt nätverk.

Node erbjuder bland annat:

* större krypterad lagring
* backup av klientdata
* synk
* kraftigare AI
* sökning och analys över större datamängder
* personlig AI-kontext
* Source API för Client och andra appar

Node är inte användarens identitet och är inte ett krav för att använda Source.

En Node förväntas normalt kunna lagra betydligt mer data än en Client.

---

## Local-first och Node-preferred

Source Client ska alltid fungera även när ingen Node finns tillgänglig.

När en betrodd Node upptäcks på det lokala nätverket ska Client automatiskt kunna använda den för sådant som Node gör bättre:

* backup
* större lagring
* kraftigare AI
* omfattande sökning
* analys över användarens samlade data

Source är därför **local-first**, men **Node-preferred** när en Node finns tillgänglig.

En fungerande internetanslutning ska aldrig vara en förutsättning.

---

## Säkerhet och identitet

Client är den betrodda utgångspunkten för användarens identitet.

Användarens nycklar hör till Client och ska inte vara beroende av en specifik Node.

När en användare vill koppla sin Client till en Node skapas användaren lokalt via Nodes administrationsgränssnitt.

Node visar därefter en tillfällig QR-kod som skannas med Source Client.

QR-flödet etablerar förtroende mellan Client och Node.

Detta ska kräva lokal tillgång till Node och är avsiktligt utformat så att en ny Client inte kan registrera sig mot Node enbart över nätverket.

Efter etableringen ska Client och Node kunna autentisera varandra automatiskt.

---

## Data, backup och synk

Source skiljer mellan backup och den data som behöver finnas lokalt på Client.

Grundprincipen är:

**Client → Node:** så mycket av användarens data som möjligt säkerhetskopieras.

**Node → Client:** den information som är relevant att ha tillgänglig lokalt synkas tillbaka.

Node kan därför innehålla användarens fullständigare historik och större datamängder medan Client innehåller den information som behövs för daglig och offline användning.

Exakta regler för versionering, konflikter, historik och synkstrategi definieras separat.

---

## AI och personlig data

AI är en viktig tjänst i Source men Source ska inte vara beroende av någon specifik modell.

Client kan använda mindre lokala modeller.

Node kan använda kraftigare modeller och ska på sikt kunna resonera över användarens samlade Source-data, exempelvis:

* anteckningar
* konversationer
* bilder
* dokument
* filer
* metadata
* historik
* information från flera appar

All sådan bearbetning ska kunna ske inom användarens eget Source-system.

---

## Source API

Client och Node ska kommunicera genom ett stabilt Source API.

API:t ska representera Sources funktioner och inte dess interna implementation.

Client och andra appar ska exempelvis kunna begära:

* lagring och hämtning
* backup och synk
* sökning
* AI-bearbetning
* analys
* metadata
* underlag för visualisering

utan att behöva känna till hur Node internt implementerar lagring, AI eller andra tjänster.

---

## Source och andra appar

Source är hela systemet.

Den officiella implementationen består främst av **Source Client** och **Source Node**.

Source Client är den fullständiga användarapplikationen och kan för användaren helt enkelt presenteras som **Source**.

Utöver Source Client ska mindre, specialiserade appar kunna använda Source Node genom samma Source API.

Sådana appar ska kunna autentiseras mot Node enligt samma grundprinciper som Source Client och bör endast få tillgång till de delar av användarens data och Source API som de behöver.

Thoughts är den första sådana applikationen, men är ett separat projekt och en konsument av Source.

---

## Flera användare

En Source Node ska kunna användas av ett mindre antal separata användare, exempelvis personer i samma hushåll.

Varje användare ska ha separat:

* identitet
* autentisering
* nycklar
* lagring
* AI-kontext
* behörighet

Att flera personer delar fysisk Node ska inte innebära att de delar privat data.

---

## Grundprinciper

Source ska följa dessa principer:

* **Local first.**
* **Encrypted by default.**
* **No cloud dependency.**
* Client och Node ska kunna fungera utan internet.
* Användaren äger och kontrollerar sin data.
* Client bär användarens identitet och nycklar.
* Client fungerar utan Node.
* Node förbättrar Source men är inget krav.
* Ny tillit mellan Client och Node etableras lokalt.
* Node har normalt mer lagring och beräkningskraft än Client.
* Client säkerhetskopierar sin data till Node när det är möjligt.
* Client ansvarar för användargränssnitt och visualisering.
* Node ansvarar främst för lagring, bearbetning och AI.
* Client, Node och andra appar kommunicerar genom ett stabilt Source API.
* Flera appar ska kunna använda samma Source-infrastruktur.
* Flera användare ska kunna dela en Node utan att dela privat data.
* Intern implementation, hårdvara och AI-modeller ska kunna förändras utan att Sources grundarkitektur förändras.

## Vision

Source ska ge användaren ett privat digitalt hem för sin information.

Client följer med användaren och är platsen där informationen används.

Node finns i bakgrunden och erbjuder större lagring, backup, kraftigare AI och tillgång till användarens större informationssamling.

Tillsammans ska de skapa ett system där användaren kan lagra, förstå och arbeta med sin egen data utan att behöva lämna över den till någon extern tjänst.
