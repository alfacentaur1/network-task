# CDN Log Anonymizer & Analytics Service

Téma jsem si vybral kvůli tomu, že jsem se něco chtěl dozvědět o kafce, byla to pro mě nová technologie a zároveň do míry hodně kompatibilní s javou, ve které jsem už pár věcí naprogramoval.



# Postup řešení, úvah
### Kafka, Clickhouse
Doteď jsem řešil pouze věci s row databázemi a komunikace přes REST rozhraní. Nejdříve jsem se tedy podíval na tutoriály na kafku, její komponenty a jak celkově funguje její messaging princip. U clickhouse jsem se seznamoval s celkově principem OLAP databází.
> 
### Dekódování HTTP logs zpráv
Data v topicu http_log jsou v binárním formátu capnproto. Musel jsem tedy najít způsob jak to přemapovat do databáze. Pomocí kompilátoru capnp a souboru http_log.capnp s pluginem pro javu jsem vygeneroval třídu HttpLog.java, ve které je Builder . V HttpLogRecordu je reader, který slouží k zero-copy čtení dat - metody readeru slouží postupně k separaci contentu log zprávy, bez konverze na javovské objekty - v hlavní třídě je dávám přímo do JSONu. 
Největší issues jsem měl při generování HttpLog třídy, nemohl jsem najít správnou verzi pluginu.
> 
### Odebírání zpráv z kafky
Při odebírání zpráv z kafky jsem použil standardní anotaci KafkaListener ve Springu, kde jsem musel nastavit, na jakém topicu naslouchá a její groupID. Zprávy jsem konvertoval do třídy, co obsahovala samotnou zprávu a ack, který pak manuálně potvrzuju při posílání přes nginx. Po každých 65 vteřinách odeberu zprávy z bufferu (mám tam 5s threshold). Deserializuji to pomocí readeru, convertuju na JSONEachRow a pošlu v jednom body HTTP entity, kde 1 řádek je 1 objekt, nastavím metadata do hlavičky, že se jedná o JSON content a pošlu to na nginx v jednom POST requestu, zároveň v URL specifikuji pro ClickHouse API, co má s daty udělat - který očekává SQL query. Pokud vše proběhne úspěšně, budu Kafce po jednom potvrzovat zprávy a ona si bude posouvat offset v topicu. Pokud se vrátí exception ze serveru nebo něco jiného, spadne to do catch bloku a celý batch se vrátí zpátky do queue a nic se nepotvrdí - tedy nezavolám ACK. pozn. celý ten batch se skládá z objektů, který má 2 parametry - ack a samotnou zprávu v bytech.

Tady jsem měl největší problém s rate limitem cache, kde jsem mohl posílat pouze 1 req/min, v configu jsem tedy upravil rate, pro lepší debuggování. Zároveň jsem přemýšlel, jak dostat data do db přes proxy, přemýšlel jsem nejdříve nad JPA, kde mi nakonec nevyhovovala kompatibilita s OLAPem. Poté jsem zkoušel JDBC spojení, které se ale před každým dotazem dotazovalo na druhou stranu, čímž si vyplýtvalo 1 req/minutu. 

Bottleneck tady vidím v handlování neposlání zpráv, v nějakých situacích může nastat uváznutí v kruhu. Jako řešení jsem viděl nechávat si u každé zprávy flag, který by limitoval, kolikrát se daná zprává může pokusit o to se odeslat, ale pak hrozilo riziko ztráty informací.
> 
### Datový model a optimalizace (ClickHouse)

Mám to realizované přes init SQL script, který vytváří tabulku podle dané struktury, poté tabulku pro agregaci a view pro rychlejší queries, ale o tom níže. První myšlenkový postup byl, že pošlu script na vytváření přes HTTP nginx, ale to by byly 3 separátní requesty a trvalo by 4 minuty, než by byly uložená první data. Potom jsem se to pokoušel odeslat jako 1 požadavek s multiquery, ale to mi odmítal clickhouse. Nakonec jsem vytvořil SQL script a dal to dockeru jako volume pro clickhouse server.
Zjistil jsem, že konfigurace volume dockeru vyžaduje relativní path, jinak mi docker vytvářel prázdné složky.
Enginy pro tabulky: 
MergeTree - rychlé seřazení, single source of truth
SummingMergeTree - kvůli optimalizaci, sečítá data dohromady - např při 2 stejných údajích.

Aby bylo mozne zajistit základní bezpečnost, přepsal jsem v dockerfile verzi clickhouse na bezpečnější, pro ilustraci jsem si vytvořil údaje na basic auth a posílám je pokaždé v hlavičce na nginx proxy.



### Graceful shutdown
Vytvořil jsem si KafkaConsumerLifecycleManager, který jsem měl jako component, aby to mohl spring spravovat. Nastavil jsem getPhase na největší prioritu, aby moje funkce začala jako první (aby se např před tím neshutdovnala servica) - implementoval jsem SmartLifecycle, kde jsem přepsal stop metodu, aby nejdříve poslala zbylá data v bufferu a až pak se shutdownul spring. V app.properties jsem nastavil, že na shutdown má spring 10s. Pokud něco selže, zprávy v kafce zůstanou nepotvrzené a nepřijdeme o ně - offset se neposune.

### Tabulky 
Aby bylo možné vykreslovat data v Grafaně rychle, místo provádění GROUP BY, používáme SummingTreeMerge, který automaticky sčítá hodnoty pro stejné kombinace klíčů. 
http_log_totals je tedy rychlý archiv
http_log_mv je trigger - kdykoliv pošle aplikace batch logů do hlavní tabulky, tak se view aktivuje a pošle data do http_log_totals

### Odhad místa na disku
###### http_logs

Předpoklad: 30 dní, 1000 msg/vteřina.

To je 2,6 miliardy zpráv za měsíc.
Předpokládejme, že každý log zabere 15 bajtů. To je 38,9 GB / měsíc.

###### http_log_totals

Předpoklad: agregační faktor 20:1

Což je 1,9 GB / měsíc.

### Závěrečné poznámky
Latence dat je v současné době 65 sekund, kvůli cooldownu na processing metodě a nginx rate. Riziko ztráty dat je díky manuálnímu potvrzování minimální, akorát je nebezpečí uváznutí v kruhu - možnost použití např. circuit breaker patternu nebo dead letter topic, kam by se ty zprávy odložily. Momentální přepisování funguje pouze pro IPv4.


