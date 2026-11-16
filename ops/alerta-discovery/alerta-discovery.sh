#!/usr/bin/env bash
# Alerta: la cuenta de API de Discovery solo debe cambiar `preferred_name` en Koha (mitigación 3).
#
# Lee `action_logs` desde el último action_id revisado. Toda fila de la cuenta de Discovery que NO sea
# un MEMBERS/MODIFY de exactamente {preferred_name} se avisa por Telegram (sin valores, solo
# action_id, fecha, acción, borrowernumber y nombres de campos). Ver doc/livesync-preferred-name.md.
#
# Uso:   alerta-discovery.sh /ruta/alerta.env          (cron cada 5 min, ver README.md)
#        alerta-discovery.sh /ruta/alerta.env --prueba  (manda un mensaje de prueba y sale)
#
# Variables (en el .env, chmod 600; ver alerta.env.example):
#   DB_HOST DB_PORT DB_NAME DB_USER DB_PASS   usuario MariaDB con SELECT en action_logs y borrowers
#   DISCOVERY_USERID                          userid de Koha de la cuenta de API de Discovery
#   TELEGRAM_BOT_TOKEN TELEGRAM_CHAT_ID       destino del aviso
#   ETIQUETA        (opc.) texto que identifica la instancia, p. ej. «Koha PROD»
#   ESTADO_DIR      (opc.) dónde se guarda el cursor; por defecto junto al .env
#   RETRASO_SEG     (opc.) solo mira filas con más de N segundos (transacciones en curso); def. 60
set -euo pipefail
umask 077

ENV_FILE=${1:?uso: alerta-discovery.sh /ruta/alerta.env [--prueba]}
MODO=${2:-}
[ -r "$ENV_FILE" ] || { echo "no puedo leer $ENV_FILE" >&2; exit 2; }
# shellcheck disable=SC1090
set -a; . "$ENV_FILE"; set +a

: "${DB_HOST:?}" "${DB_NAME:?}" "${DB_USER:?}" "${DB_PASS:?}" "${DISCOVERY_USERID:?}"
: "${TELEGRAM_BOT_TOKEN:?}" "${TELEGRAM_CHAT_ID:?}"
DB_PORT=${DB_PORT:-3306}
ETIQUETA=${ETIQUETA:-Koha}
ESTADO_DIR=${ESTADO_DIR:-$(dirname "$ENV_FILE")}
RETRASO_SEG=${RETRASO_SEG:-60}
CURSOR_FILE="$ESTADO_DIR/cursor"
ERROR_FILE="$ESTADO_DIR/error-avisado"
mkdir -p "$ESTADO_DIR"

# Un solo proceso a la vez.
exec 9>"$ESTADO_DIR/lock"
flock -n 9 || exit 0

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# Credenciales fuera de argv: archivo de opciones de MariaDB y config de curl.
cat > "$TMP/my.cnf" <<EOF
[client]
host=$DB_HOST
port=$DB_PORT
user=$DB_USER
password=$DB_PASS
EOF
printf 'url = "https://api.telegram.org/bot%s/sendMessage"\n' "$TELEGRAM_BOT_TOKEN" > "$TMP/curl.cfg"

telegram() {
  curl -sS --max-time 20 -o /dev/null -w '%{http_code}' -K "$TMP/curl.cfg" \
    --data-urlencode "chat_id=$TELEGRAM_CHAT_ID" --data-urlencode "text=$1" \
    --data-urlencode "disable_web_page_preview=true"
}

sql() {  # $1 = consulta; salida TSV sin cabecera
  mysql --defaults-extra-file="$TMP/my.cnf" --batch --skip-column-names --raw "$DB_NAME" -e "$1"
}

fallo() {  # avisa una sola vez por racha de errores
  echo "ERROR: $1" >&2
  if [ ! -e "$ERROR_FILE" ]; then
    [ "$(telegram "🔴 $ETIQUETA — la alerta de Discovery no pudo revisar action_logs: $1")" = 200 ] \
      && date -u +%FT%TZ > "$ERROR_FILE"
  fi
  exit 1
}

if [ "$MODO" = "--prueba" ]; then
  r=$(telegram "🧪 $ETIQUETA — prueba de la alerta de Discovery (no es una alerta real).")
  echo "Telegram HTTP $r"; [ "$r" = 200 ]; exit
fi

# Validar el userid con un patrón simple (se interpola en SQL).
[[ "$DISCOVERY_USERID" =~ ^[A-Za-z0-9._@-]+$ ]] || fallo "DISCOVERY_USERID con caracteres no válidos"

BN=$(sql "SELECT borrowernumber FROM borrowers WHERE userid = '$DISCOVERY_USERID'" 2>"$TMP/err") \
  || fallo "consulta a borrowers falló ($(head -c 200 "$TMP/err"))"
[ -n "$BN" ] || fallo "no existe en Koha el userid $DISCOVERY_USERID"

# Primer arranque: empezar desde el final, sin revisar la historia.
if [ ! -s "$CURSOR_FILE" ]; then
  MAXID=$(sql "SELECT COALESCE(MAX(action_id),0) FROM action_logs" 2>"$TMP/err") \
    || fallo "consulta a action_logs falló ($(head -c 200 "$TMP/err"))"
  echo "$MAXID" > "$CURSOR_FILE"
  echo "Cursor inicializado en $MAXID (bn Discovery = $BN)."
  exit 0
fi
CURSOR=$(cat "$CURSOR_FILE")
[[ "$CURSOR" =~ ^[0-9]+$ ]] || fallo "cursor corrupto en $CURSOR_FILE"

# Todas las filas nuevas de la cuenta; la columna «ok» dice si es exactamente {preferred_name}.
sql "SELECT action_id,
            DATE_FORMAT(\`timestamp\`, '%Y-%m-%d %H:%i'),
            CONCAT(module, '/', action),
            COALESCE(\`object\`, '-'),
            COALESCE(\`interface\`, '-'),
            IF(COALESCE(JSON_VALID(info),0)=1, COALESCE(JSON_KEYS(info), '[]'), '(info no JSON)'),
            (module='MEMBERS' AND action='MODIFY' AND COALESCE(JSON_VALID(info),0)=1
             AND JSON_LENGTH(info)=1 AND JSON_CONTAINS_PATH(info,'one','\$.preferred_name')=1) AS ok
     FROM action_logs
     WHERE \`user\` = $BN AND action_id > $CURSOR
       AND \`timestamp\` <= NOW() - INTERVAL $RETRASO_SEG SECOND
     ORDER BY action_id" > "$TMP/filas" 2>"$TMP/err" \
  || fallo "consulta a action_logs falló ($(head -c 200 "$TMP/err"))"

# Se recuperó tras un error: avisar una vez.
if [ -e "$ERROR_FILE" ]; then
  telegram "✅ $ETIQUETA — la alerta de Discovery vuelve a revisar action_logs." >/dev/null || true
  rm -f "$ERROR_FILE"
fi

[ -s "$TMP/filas" ] || exit 0
NUEVO_CURSOR=$(tail -n1 "$TMP/filas" | cut -f1)
awk -F'\t' '$7 != 1' "$TMP/filas" > "$TMP/malas"

if [ -s "$TMP/malas" ]; then
  N=$(wc -l < "$TMP/malas" | tr -d ' ')
  {
    echo "⚠️ $ETIQUETA — la cuenta de Discovery ($DISCOVERY_USERID) hizo $N acción(es) fuera de preferred_name:"
    head -n 20 "$TMP/malas" | awk -F'\t' '{printf "• #%s %s %s patron %s (%s) campos %s\n", $1, $2, $3, $4, $5, $6}'
    [ "$N" -gt 20 ] && echo "… y $((N-20)) más."
    echo "Revisar en action_logs (user = $BN). Si no es esperado: desactivar la clave de API de Discovery."
  } > "$TMP/msg"
  r=$(telegram "$(cat "$TMP/msg")")
  # Si el aviso no sale, no se avanza el cursor: se reintenta en la próxima pasada.
  [ "$r" = 200 ] || { echo "Telegram HTTP $r; cursor sin avanzar" >&2; exit 1; }
  echo "$(date -u +%FT%TZ) aviso enviado: $N fila(s) fuera de preferred_name (ids $(cut -f1 "$TMP/malas" | paste -sd, -))"
fi

echo "$NUEVO_CURSOR" > "$CURSOR_FILE"
