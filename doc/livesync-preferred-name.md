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

   Es lo único que necesita el live sync: medido en Koha dev con un usuario que solo tenía este
   GRANT, más SELECT sobre otra tabla para poder entrar a la base.
4. Nueva versión del conector **1.8.0**. midPoint cachea el esquema por bundle + versión: tras
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

| Escenario | Dónde | Resultado |
|---|---|---|
| Parseo de `info` en formato 25.11 y 26.05, `diff` de respaldo, filas ilegibles | Tests unitarios (`ActionLogChangeFilterTest`) | ✅ |
| Antieco por usuario; `api` de otro usuario, `intranet`, `opac` y `user=0` se emiten | Tests unitarios | ✅ |
| Cambio solo de `surname` (y de atributo extendido) ignorado aunque sea del mismo patron | Tests unitarios | ✅ |
| `preferred_name` vaciado → Koha pone `firstname` → se emite | Tests unitarios (el formato sale del código de Koha) | ✅ |
| Avance del token por eventos ignorados, lotes encadenados, ventana de seguridad, parada del handler, patron inexistente | Tests unitarios (`KohaLiveSyncTest`) | ✅ |
| Historia real de Koha dev (25.11): 30 550 filas `MEMBERS/MODIFY` (`api` 275, `intranet` 29 521, `opac` 754) | `ActionLogSyncDevIT` contra la BD de dev, solo lectura | ✅ 0 ilegibles, 0 con `preferred_name`, todas ignoradas; token final = `MAX(action_id)` |
| Sin `GRANT SELECT` sobre `action_logs` | `ActionLogSyncDevIT`, usuario MariaDB temporal (luego borrado) | ✅ `PermissionDeniedException` con el GRANT exacto |
| Con solo el GRANT de arriba | Ídem | ✅ lee `MAX(action_id)`, la ventana y los lotes |
| (a)–(e) con cambios reales por API/intranet/OPAC | Koha dev | ❌ **No ejecutado**: ninguna credencial de API de Koha dev funciona (Basic 401, OAuth2 `Unimplemented grant type`) |

`ActionLogSyncDevIT` se salta si no existe `KOHA_SYNC_IT_DB_HOST`. Para ejecutarlo, pasar las
credenciales con `--env-file` (nunca en argv) y lanzar
`mvn test -Dtest=ActionLogSyncDevIT -Dsurefire.failIfNoSpecifiedTests=false`.

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
