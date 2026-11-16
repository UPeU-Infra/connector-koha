# Live sync del «nombre preferido» (v1.8.0)

`KohaConnector` implementa `SyncOp` (`sync` + `getLatestSyncToken`). midPoint recoge por live sync
los cambios de `preferred_name` que otros sistemas (Discovery, el bibliotecario, el lector desde el
OPAC tras la aprobación) hacen en Koha. Diseño de origen: `productos/iga/canonico/docs/specs/nombre-preferido/01-diagnostico-y-propuesta.md`
(§4.3, §12 riesgos R5 y R12, §13 «opción 3»).

## Cómo funciona

1. **Cursor = `action_logs.action_id`.** `getLatestSyncToken` devuelve `MAX(action_id)`, así que la
   historia no se reprocesa. Si midPoint llama a `sync` sin token, el conector arranca en el
   presente y no emite nada.
2. Por cada pasada se leen por JDBC, en lotes de `syncBatchSize`, las filas
   `module='MEMBERS' AND action='MODIFY'` con `action_id > token`.
3. Una fila se **emite** solo si:
   - su `user` (borrowernumber de quien hizo el cambio) **no** está excluido (antieco por usuario,
     **no** por `interface`: Discovery también escribe por la API);
   - el cambio **toca** un campo de `syncTriggerFields` (por defecto solo `preferred_name`). Un cambio
     de `surname` del mismo patron se ignora (riesgo R5).
4. Por cada patron con eventos válidos en el lote se **relee su estado actual por REST**, igual que
   en `executeQuery`, y se emite un `SyncDelta` `CREATE_OR_UPDATE` con el objeto completo y el token
   = `action_id` de su último evento. Si un patron tiene varios eventos en el lote, se coalescen al
   último.
5. Al terminar, el token final es el último `action_id` leído, así que **los eventos ignorados también
   hacen avanzar el cursor** y no se releen. Se publica con `SyncTokenResultsHandler`.

### Formato de `action_logs.info` (verificado en el código fuente de Koha)

| Koha | `info` | `diff` |
|---|---|---|
| 25.11 (koha-common 25.11.03-2, Koha dev) | `{"campo":{"before":..,"after":..}}` (`Koha/Patron.pm:375-411`) | vacío |
| 26.05.05 (PROD `.136`) | `{"campo": valor_nuevo}` (`logaction(..., \%log_to, undef, \%log_from)`, `Koha/Patron.pm:404-427` en `v26.05.05-1`) | Struct::Diff `{"D":{"campo":{"O":..,"N":..}}}` (`C4/Log.pm:166`) |

En ambos casos las **claves** del JSON de `info` son los campos cambiados, y eso es lo único que se
lee (si `info` no es JSON, se usan las claves de `diff.D`). Los **valores** no se usan nunca: se
relee el patron.

### Robustez

- Fila con `info`/`diff` ilegible: se registra (`WARN`) y se salta, sin abortar la tarea (R12). El
  token la supera.
- Patron que ya no existe al releerlo: **se salta, no se emite `DELETE`**. Este feed solo transporta
  cambios de `preferred_name`. La existencia de la cuenta la gobierna midPoint (el recurso tiene la
  capacidad de borrado deshabilitada) y una baja real en Koha la detecta la reconciliación. Un
  `DELETE` aquí dispararía la reacción `deleted` de midPoint por un evento que no es una baja.
- Error al leer un patron que no sea «no existe» (Koha caído, 5xx): se propaga. La pasada se corta,
  midPoint conserva el token del último delta procesado y la siguiente pasada reintenta, sin perder
  cambios.
- **Ventana de seguridad** (`syncSafetyLagSeconds`, 10 s). Koha escribe el log dentro de la
  transacción del `store()`, así que un `action_id` menor puede hacerse visible después que uno
  mayor. El conector no lee más allá de la primera fila con menos de 10 s de antigüedad.
- Sin `GRANT SELECT` sobre `action_logs`: `PermissionDeniedException` con el GRANT que hay que
  ejecutar, nunca un NPE. Sin canal JDBC (`dbEnabled=false`): `ConfigurationException`.
- Un `syncExcludeUserLogins` que no existe en Koha es un **error**, no un aviso: seguir sin él
  emitiría los ecos de midPoint como cambios ajenos.

## Prerrequisitos

1. **`BorrowersLog = 1`** en las preferencias del sistema de Koha. Sin ella Koha no escribe
   `MEMBERS/MODIFY` y el live sync no ve nada.
2. **`dbEnabled = true`** (canal JDBC del conector; en PROD ya está activo para `flags`/`user_permissions`).
3. **GRANT** al usuario JDBC del conector (lo ejecuta un administrador de la BD de Koha):

   ```sql
   -- Comprobar antes con qué host está dado de alta el usuario:
   SELECT user, host FROM mysql.user WHERE user = 'koha_connector';
   GRANT SELECT ON `koha_upeu`.`action_logs` TO 'koha_connector'@'<host de la consulta anterior>';
   ```

   Es lo único que necesita el live sync: medido en el servidor MariaDB del dev (base `koha_bul`,
   ver «Qué base es el Koha de dev») con un usuario que solo tenía este GRANT, más SELECT sobre otra
   tabla para poder entrar a la base.
4. **Cuenta de API de Discovery** con el mínimo para su `PUT` (en 26.05, `borrowers → edit_borrowers`)
   y su `borrowernumber` en la alerta: ver «La escritura de Discovery» y «Alerta».
5. Nueva versión del conector **1.8.0**. midPoint cachea el esquema por bundle + versión: tras
   instalar el JAR hay que apuntar `connectorRef` al nuevo `ConnectorType` y refrescar el esquema del
   recurso. El esquema de atributos no cambia; sí cambia que ahora se anuncia `SyncApiOp` para
   `__ACCOUNT__` (no para `__GROUP__`).

## Propiedades nuevas

| Propiedad | Tipo | Por defecto | Uso |
|---|---|---|---|
| `syncExcludeUserIds` | String[] | vacío | borrowernumber cuyos cambios no se emiten (la cuenta de API de midPoint). Solo dígitos. |
| `syncExcludeUserLogins` | String[] | vacío | Igual, por `userid`; se resuelve por REST (`GET /patrons?userid=..&_match=exact`) una vez por instancia. |
| `syncTriggerFields` | String[] | `preferred_name` | Campos de `borrowers` cuyo cambio produce un delta. |
| `syncBatchSize` | int | 500 | `LIMIT` de cada consulta (1–10 000). |
| `syncSafetyLagSeconds` | int | 10 | Ventana de seguridad (0–3600; 0 la desactiva). |

Fragmento para `koha-upeu.xml` (dentro de `configurationProperties`, prefijo `ns2` del bundle):

```xml
<!-- Live sync del nombre preferido (conector >= 1.8.0). Antieco por USUARIO: la cuenta de API
     con la que escribe midPoint. NO excluir interface='api' (Discovery también escribe por la API). -->
<ns2:syncExcludeUserIds>__BORROWERNUMBER_SVC_MIDPOINT__</ns2:syncExcludeUserIds>
<ns2:syncTriggerFields>preferred_name</ns2:syncTriggerFields>
<ns2:syncBatchSize>500</ns2:syncBatchSize>
<ns2:syncSafetyLagSeconds>10</ns2:syncSafetyLagSeconds>
```

El borrowernumber es el dueño de la clave OAuth2 con la que entra midPoint
(`SELECT patron_id FROM api_keys WHERE client_id = '<clientId del recurso>'`). En lugar del
borrowernumber se puede usar `<ns2:syncExcludeUserLogins>userid</ns2:syncExcludeUserLogins>`.

## Escenarios medidos (16-nov-2026)

> **Qué base es el Koha de dev.** El Koha de desarrollo (`192.168.15.135`, instancia `upeu`,
> koha-common 25.11.03-2) usa la base **`koha_upeu`** en `192.168.15.130` (`consola-mariadb`,
> MariaDB 11.1.3). `koha_bul`, en el mismo servidor, es una **copia vieja que el Koha de dev no
> usa**. Las filas marcadas «`koha_bul`» se midieron ahí: valen como muestra del formato de 25.11
> y del GRANT, no como estado del dev. Las escrituras reales (más abajo) son todas en `koha_upeu`.

| Escenario | Dónde | Resultado |
|---|---|---|
| Parseo de `info` en formato 25.11 y 26.05, `diff` de respaldo, filas ilegibles | Tests unitarios (`ActionLogChangeFilterTest`) | ✅ |
| Antieco por usuario; `api` de otro usuario, `intranet`, `opac` y `user=0` se emiten | Tests unitarios | ✅ |
| Cambio solo de `surname` (y de atributo extendido) ignorado aunque sea del mismo patron | Tests unitarios | ✅ |
| `preferred_name` vaciado → Koha pone `firstname` → se emite | Tests unitarios | ✅ |
| Avance del token por eventos ignorados, lotes encadenados, ventana de seguridad, parada del handler, patron inexistente | Tests unitarios (`KohaLiveSyncTest`) | ✅ |
| Historia de 25.11: 30 550 filas `MEMBERS/MODIFY` (`api` 275, `intranet` 29 521, `opac` 754) | `ActionLogSyncDevIT` contra **`koha_bul`**, solo lectura | ✅ 0 ilegibles, 0 con `preferred_name`, todas ignoradas; token final = `MAX(action_id)` |
| Sin `GRANT SELECT` sobre `action_logs` | `ActionLogSyncDevIT` contra **`koha_bul`**, usuario MariaDB temporal (luego borrado) | ✅ `PermissionDeniedException` con el GRANT exacto |
| Con solo el GRANT de arriba | Ídem | ✅ lee `MAX(action_id)`, la ventana y los lotes |
| (a)–(g) con escrituras reales por API, intranet y OPAC | Koha dev, **`koha_upeu`** | ✅ ver la tabla siguiente |

### Escrituras reales en Koha dev (`koha_upeu`)

Montaje: patrons **sintéticos** (categoría `staff`, biblioteca `BUL`): `np_svc_midpoint` (bn 6, la
cuenta de API del conector), `np_discovery` (bn 7, la de Discovery), `np_objetivo_1` (bn 8) y
`np_objetivo_2` (bn 9), cada cuenta de servicio con su clave OAuth2. El conector 1.8.0 **completo**
(`KohaLiveSyncWriteDevIT`: REST por OAuth2 con `np_svc_midpoint` + JDBC) con
`syncExcludeUserLogins=np_svc_midpoint` (resuelto por REST a bn 6) y ventana de 10 s; tras cada
escritura se esperan 11 s y se lanza `sync` desde el token anterior. Token inicial (`getLatestSyncToken`
sin token) = 47, sin emitir la historia.

| # | Escritura | `action_id` | `user` | `interface` | Claves de `info` | Qué emitió el conector |
|---|---|---|---|---|---|---|
| a | `np_discovery`: `PUT /patrons/8` mínimo con `preferred_name` | 48 | 7 | `api` | `preferred_name` | ✅ 1 `CREATE_OR_UPDATE` uid 8, token 48, `preferred_name` nuevo; token final 48 |
| b | `np_svc_midpoint` (excluida): mismo PUT sobre el 8 | 49 | 6 | `api` | `preferred_name` | ✅ 0 deltas; token 48 → 49 |
| c | Bibliotecario: solo `surname` del 9 | 50 | 1 | `intranet` | `surname` | ✅ 0 deltas; token 49 → 50 |
| d1 | `Koha::Patron->store` en `koha-shell`, sin fijar interfaz ni sesión | 51 | **0** | **`opac`** | `preferred_name` | ✅ 1 delta uid 9, token 51 |
| d2 | Ídem con `C4::Context->interface('intranet')` + sesión del bibliotecario (bn 1) | 52 | 1 | `intranet` | `preferred_name` | ✅ 1 delta uid 9, token 52 (⚠️ simulado: no se pasó por `memberentry.pl`) |
| d3 | Solicitud de modificación del OPAC (`Koha::Patron::Modification`, `changed_fields=preferred_name`) aprobada con `->approve` por el bibliotecario | 53 | 1 | `intranet` | `preferred_name` | ✅ 1 delta uid 8, token 53. La solicitud del lector no deja fila; la deja la **aprobación**, a nombre del bibliotecario (⚠️ solicitud creada por script, no desde el formulario del OPAC) |
| e | `np_discovery`: `PUT /patrons/9` con `preferred_name: null` | 54 | 7 | `api` | `preferred_name` (`after` = `firstname`) | ✅ 1 delta uid 9, token 54, `preferred_name` = `firstname` |
| f | Lote: 55 bibliotecario cambia 19 campos del 9 sin `preferred_name`; 56 alta de atributo extendido (`user` 0); 57 `np_discovery` cambia `preferred_name` del 9 | 55–57 | 1 / 0 / 7 | `intranet` / `intranet` / `api` | 19 campos / `attribute.COD_UPEU` / `preferred_name` | ✅ 1 delta uid 9, token 57; 55 y 56 ignorados |
| g | `np_discovery`: solo `phone` del 8 (58); `mobile` + `preferred_name` del 9 (59) | 58, 59 | 7 | `api` | `phone` / `mobile`, `preferred_name` | ✅ 1 delta uid 9, token 59 (58 no toca `preferred_name`); **los dos salen en la alerta** (ver abajo) |

`info` real en 25.11, p. ej. la fila 48: `{"preferred_name":{"after":"…","before":"…"}}`; `diff`
vacío en todas. Hallazgos:

- **Un script en `koha-shell` sin `Koha::Script` registra `interface='opac'` y `user=0`**, no
  `commandline`: por interfaz no se distingue de un cambio del OPAC. Otra razón para que el antieco
  sea por usuario.
- En 25.11 los atributos extendidos también generan `MEMBERS/MODIFY`, con la clave
  `attribute.<CÓDIGO>` en `info`: el filtro los ignora (fila 56).
- Koha sustituye el `preferred_name` vacío por `firstname` **antes** de escribir el log (fila 54).

### La escritura de Discovery (`PUT` mínimo)

Discovery hará `GET /api/v1/patrons/{id}` y luego `PUT` con **`surname`, `library_id`,
`category_id`** (obligatorios en el `PUT` de 25.11: `required` de `definitions/patron.yaml`) **y
`preferred_name`**. `Patrons#update` hace `set_from_api($body)->store`: solo toca lo que viaja en
el cuerpo; la validación OpenAPI no inyecta valores por defecto.

Medido con foto de **toda** la fila de `borrowers` (84 columnas) antes y después:

| Patron | Estado previo | Columnas que cambiaron | `action_logs` |
|---|---|---|---|
| 8 | mínimo | `preferred_name`, `updated_on` | fila 48: solo `preferred_name` |
| 9 | con 19 campos más (correo, teléfonos, dirección, fecha de nacimiento, notas…) y un atributo extendido | `preferred_name`, `updated_on`; `borrower_attributes` intacto | fila 57: solo `preferred_name` |

**Permisos de la cuenta de Discovery** (medidos en 25.11 con `np_discovery`, OAuth2):

| Permisos | `GET /patrons/{id}` | `GET /patrons?cardnumber=` | `PUT /patrons/{id}` |
|---|---|---|---|
| solo `borrowers → edit_borrowers` (flags 0) | 200 | 403 (pide `list_borrowers`) | **403** `required_permissions: {"borrowers":"1"}` |
| `edit_borrowers` + `list_borrowers` | — | — | 403 |
| los 8 subpermisos de `borrowers` | — | — | 403 |
| módulo `borrowers` completo (flags = 16) | 200 | — | **200** |

En **25.11** el `PUT` declara `borrowers: "1"` y `C4::Auth::_dispatch` solo lo acepta con el
módulo completo: ningún conjunto de subpermisos basta. En **26.05.05-1 (PROD)** el spec upstream
(`api/v1/swagger/paths/patrons.yaml`) declara `borrowers: edit_borrowers`. ❌ **No medido en 26.05**
(no hay dev en esa versión).

**Recomendación para el CRAI (PROD 26.05):** cuenta de API de Discovery con **solo
`borrowers → edit_borrowers`** y sin otros módulos (OAuth2 funciona con flags 0 y el subpermiso; no
hace falta `catalogue`). Añadir `list_borrowers` solo si Discovery tiene que **buscar** por
`cardnumber`/`userid`; con el `patron_id` no lo necesita. **No** dar el módulo completo: incluye
`delete_borrowers`, `merge_borrowers`, envío de mensajes, `view_borrower_infos_from_any_libraries`
y `api_validate_password`. Comprobar en PROD con un `PUT` real antes de darlo por bueno. (En el dev
25.11, `np_discovery` se quedó con flags = 16 porque allí es lo único que permite el `PUT`.)

### Cambios hechos en el Koha de dev para poder medir

- `RESTOAuth2ClientCredentials = 1` en `koha_upeu` (estaba en 0), con `C4::Context->set_preference`
  (hay memcached; un `UPDATE` deja la caché vieja). `action_logs` 43. `RESTBasicAuth` sigue en 0.
- **Apache no pasaba el header `Authorization` a la API.** En el vhost de dev la API va por CGI (los
  `Include` de plack están comentados) y Apache no entrega `Authorization` a un CGI: todo Bearer
  daba `401 Authentication failure.` aunque el token fuera válido. Se añadió al vhost de la intranet
  (`:8001`) `SetEnvIf Authorization "(.+)" HTTP_AUTHORIZATION=$1` (copia previa
  `/etc/apache2/sites-available/upeu.conf.bak-20261116-np`) y `systemctl reload apache2`. El vhost
  del OPAC (`:8000`) no se tocó: ahí el Bearer sigue fallando. Las pruebas usan
  `http://192.168.15.135:8001/api/v1`.

### Cómo repetirlo

`ActionLogSyncDevIT` (solo JDBC) se salta si no existe `KOHA_SYNC_IT_DB_HOST`;
`KohaLiveSyncWriteDevIT` (conector completo, más la alerta) si no existe `KOHA_LIVE_IT_URL`. Pasar
las credenciales por fichero de entorno (`--env-file` en Docker, o `set -a; source fichero`; nunca
en argv) y lanzar `mvn test -Dtest=KohaLiveSyncWriteDevIT -Dsurefire.failIfNoSpecifiedTests=false`
con `KOHA_LIVE_IT_FROM=<token>`. Las escrituras se hacen fuera del test (API con las cuentas
sintéticas o `koha-shell`); el test solo lee. Credenciales en `~/.secrets/koha-dev.env`
(`KOHA_DEV_NP_SVC_*`, `KOHA_DEV_NP_DISCOVERY_*`; ojo: `KOHA_DEV_DB_NAME` apunta a `koha_bul`).

## Alerta: Discovery solo debe tocar `preferred_name` (mitigación 3)

Discovery escribe con `PUT` (y en 25.11 con el módulo `borrowers` completo), así que nada en Koha le
impide cambiar otro campo o borrar un patron. La mitigación es **vigilar**: toda fila de
`action_logs` de la cuenta de Discovery que **no** sea un `MEMBERS/MODIFY` de exactamente
`{preferred_name}` es una alerta.

```sql
-- ? 1 = borrowernumber de la cuenta de API de Discovery
-- ? 2 = último action_id ya revisado (cursor de la alerta)
SELECT action_id, `timestamp`, module, action, `object`, `interface`,
       IF(COALESCE(JSON_VALID(info), 0) = 1, JSON_KEYS(info), NULL) AS campos
FROM action_logs
WHERE `user` = ? AND action_id > ?
  AND NOT (module = 'MEMBERS' AND action = 'MODIFY'
           AND COALESCE(JSON_VALID(info), 0) = 1
           AND JSON_LENGTH(info) = 1
           AND JSON_CONTAINS_PATH(info, 'one', '$.preferred_name') = 1)
ORDER BY action_id;
```

- Cualquier fila devuelta = alerta: otro campo (`phone`), varios campos aunque uno sea
  `preferred_name`, `info` ilegible o nulo, y cualquier otra acción de esa cuenta (`MEMBERS/DELETE`,
  cambio de contraseña…).
- Vale para el `info` de 25.11 (`{"campo":{"before","after"}}`) y el de 26.05 (`{"campo":valor}`):
  en los dos las claves son los campos cambiados. El predicado se probó con literales de ambos
  formatos, `NULL` y texto no JSON en MariaDB 11.1.3; en 26.05 real ❌ no medido.
- Necesita el mismo `GRANT SELECT ON action_logs`. Emitir un token OAuth2 no deja fila: no hay ruido.
- No ve cambios hechos sin `BorrowersLog=1`, ni los de Discovery con otra cuenta.
- **Prueba (Koha dev, `koha_upeu`):** se sembraron con `np_discovery` un cambio de solo `phone`
  (fila 58) y otro de `mobile` + `preferred_name` (fila 59). La consulta desde 0 devuelve
  **exactamente 58 y 59**; las filas 48, 54 y 57 (solo `preferred_name`, de la misma cuenta) no salen.
  Está en `KohaLiveSyncWriteDevIT#alertaCambiosDeDiscoveryFueraDePreferredName`
  (`KOHA_LIVE_IT_ALERT_USER=<bn de Discovery>`).
- Es un **prototipo**: no está programado en ningún servidor. Dónde correrlo (tarea de midPoint,
  cron con aviso por correo/Telegram) queda por decidir.

## Limitaciones

- **Edición concurrente.** El delta lleva el estado del patron **en el momento de releerlo**, no el
  del evento: si el bibliotecario cambia `preferred_name` entre el cambio de Discovery y la lectura,
  midPoint recibe el del bibliotecario (gana el último). A la inversa, Discovery hace PUT
  leer-y-escribir: si el bibliotecario edita entre la lectura y la escritura de Discovery, el PUT
  de Discovery puede deshacer ese cambio (en `surname`, `library_id` y `category_id`, que reenvía).
  Eso es de Discovery, no del conector.
- **Quien use la cuenta excluida queda excluido**, también desde la intranet: el filtro es por usuario.
- Solo se ve lo que pasa por `Koha::Patron->store()` con `BorrowersLog=1`. Los cambios por SQL directo
  no dejan rastro.
- Una transacción de Koha más larga que `syncSafetyLagSeconds` podría saltarse.
- Si el CRAI pone `preferred_name` en `PatronSelfModificationBorrowerUnwantedField`, al aprobar en el
  OPAC un cambio de `firstname` Koha iguala `preferred_name` a `firstname`
  (`Koha/Patron/Modification.pm:101`). Eso genera un evento del bibliotecario que midPoint leerá como
  «borrar el preferido».
- Purgar `action_logs` (`cleanup_database.pl --logs`) por detrás del cursor no afecta; por delante,
  sí se perderían cambios.
