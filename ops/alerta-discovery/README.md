# Alerta: Discovery solo cambia `preferred_name`

Mitigación 3 del nombre preferido (ver [`doc/livesync-preferred-name.md`](../../doc/livesync-preferred-name.md)).
La cuenta de API de Discovery tiene en Koha más permiso del que necesita (el `PUT /patrons` no se puede
limitar a un campo), así que se **vigila**: cada 5 minutos se revisa `action_logs` y toda acción de esa
cuenta que no sea un `MEMBERS/MODIFY` de exactamente `{preferred_name}` llega por Telegram.

## Qué hace

- Resuelve el `borrowernumber` de `DISCOVERY_USERID`. Si no existe, avisa (fail-closed).
- Primera pasada: pone el cursor en `MAX(action_id)` y no revisa la historia.
- Siguientes: lee las filas de esa cuenta con `action_id > cursor` y más de `RETRASO_SEG` segundos
  (para no saltarse transacciones en curso); avisa las que no son solo `preferred_name` y avanza el cursor.
- Si el aviso no sale (Telegram caído), **no avanza el cursor**: lo reintenta en la próxima pasada.
- Errores de base de datos: un aviso por racha de errores y otro al recuperarse.
- El mensaje lleva action_id, fecha, acción, borrowernumber, interfaz y **nombres** de campos. Nunca valores.
- Credenciales fuera de argv (`--defaults-extra-file` y `curl -K`, en un temporal 600). `flock` evita solapes.

## Instalar

```bash
git clone https://github.com/UPeU-Infra/connector-koha.git ~/connector-koha   # o git pull
mkdir -p ~/.config/alerta-discovery && chmod 700 ~/.config/alerta-discovery
# crear ~/.config/alerta-discovery/alerta.env a partir de alerta.env.example (chmod 600), sin scp
~/connector-koha/ops/alerta-discovery/alerta-discovery.sh ~/.config/alerta-discovery/alerta.env --prueba
~/connector-koha/ops/alerta-discovery/alerta-discovery.sh ~/.config/alerta-discovery/alerta.env   # inicializa el cursor
( crontab -l 2>/dev/null; echo '*/5 * * * * $HOME/connector-koha/ops/alerta-discovery/alerta-discovery.sh $HOME/.config/alerta-discovery/alerta.env >> $HOME/.config/alerta-discovery/alerta.log 2>&1' ) | crontab -
```

Necesita `mysql` (cliente MariaDB), `curl`, `flock` y salida a `api.telegram.org`. El usuario MariaDB
solo necesita `SELECT` en `action_logs` y `borrowers` (el del conector, con el `GRANT` del doc, más
`borrowers` que ya tiene).

## Limitaciones

- No ve cambios hechos con `BorrowersLog=0`, por SQL directo, ni los de Discovery con otra cuenta.
- Retraso máximo ≈ 5 min + `RETRASO_SEG`.
- Purgar `action_logs` (`cleanup_database.pl --logs`) por detrás del cursor no afecta.
