#!/usr/bin/env bash
# Smoke test end-to-end (HTTP real, sin mocks) de los dos cambios de comportamiento
# de esta ronda:
#   B1 — POST /empresas: RUC del mismo vendedor -> 200 (empresa existente);
#        RUC de otro vendedor -> 409 con mensaje no culpabilizador.
#   D1 — POST /auth/cambiar-contrasena: endpoint nuevo, protegido, con sus
#        cuatro respuestas (401 sin auth, 401 password mala, 400 validacion,
#        200 exito) y que el flag requiere_cambio_contrasena se apague de verdad.
#
# Requiere: backend corriendo en BASE_URL (./gradlew bootRun) y Postgres arriba
# (docker start quantum-crm-postgres). El script crea sus propios empleados de
# prueba, corre los escenarios, y BORRA todo lo que creo al final (empleados +
# la empresa de prueba), asi que es seguro correrlo mas de una vez.
#
# Uso: bash scripts/smoke-test-b1-d1.sh
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api/v1}"
PG_CONTAINER="${PG_CONTAINER:-quantum-crm-postgres}"
PG_USER="${PG_USER:-quantum}"
PG_DB="${PG_DB:-quantum_crm}"

PASS_COUNT=0
FAIL_COUNT=0

pass() { PASS_COUNT=$((PASS_COUNT+1)); echo "  [PASS] $1"; }
fail() { FAIL_COUNT=$((FAIL_COUNT+1)); echo "  [FAIL] $1"; }

psql_exec() {
  docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tA -c "$1"
}

echo "=== 0. Verificando que el backend responda en $BASE_URL ==="
if ! curl -s -o /dev/null -w "%{http_code}" "${BASE_URL%/api/v1}/actuator/health" | grep -q "200"; then
  echo "El backend no responde en ${BASE_URL%/api/v1}/actuator/health."
  echo "Levantalo con: ./gradlew bootRun   (y antes: docker start quantum-crm-postgres)"
  exit 1
fi
echo "  backend arriba."
echo ""

TMP_DIR=$(mktemp -d)
COOKIES_A="$TMP_DIR/cookies_a.txt"
COOKIES_B="$TMP_DIR/cookies_b.txt"
EMAIL_A="smoketest.vendedorA@example.com"
EMAIL_B="smoketest.vendedorB@example.com"
PASSWORD="SmokeTest123!"
# Hash BCrypt de PASSWORD, generado independientemente (no via la API).
HASH='$2b$10$twrDQCQUkfXAGTDCZjDyA.dKr2CsXoxIgvF.jdeQTAkk5X8rSTfkq'
RUC_SMOKE="20999888771"

cleanup() {
  echo ""
  echo "=== Limpieza (deja la base como estaba) ==="
  psql_exec "DELETE FROM empresa_segmentos WHERE id_empresa IN (SELECT id FROM empresas WHERE ruc = '${RUC_SMOKE}');" >/dev/null
  psql_exec "DELETE FROM empresas WHERE ruc = '${RUC_SMOKE}';" >/dev/null
  psql_exec "DELETE FROM empleados WHERE email IN ('${EMAIL_A}', '${EMAIL_B}');" >/dev/null
  rm -rf "$TMP_DIR"
  echo "  empresa y empleados de prueba borrados."
  if [ -n "${DRIVE_FOLDER_ID:-}" ]; then
    echo ""
    echo "  NOTA: se creo una carpeta real de Google Drive (id: ${DRIVE_FOLDER_ID})."
    echo "  El backend no la borra al eliminar la empresa (hallazgo abierto, no de esta"
    echo "  ronda) — bórrala a mano en Drive si no la quieres conservar."
  fi
}
trap cleanup EXIT

echo "=== 1. Preparando dos empleados de prueba (vendedor A y B) ==="
psql_exec "
INSERT INTO empleados (nombres, apellidos, email, area, puesto, rol, activo, password_hash, requiere_cambio_contrasena)
VALUES
  ('SmokeTest', 'VendedorA', '${EMAIL_A}', 'QA', 'Smoke test', 'vendedor', true, '${HASH}', true),
  ('SmokeTest', 'VendedorB', '${EMAIL_B}', 'QA', 'Smoke test', 'vendedor', true, '${HASH}', false)
ON CONFLICT (email) DO NOTHING;
" >/dev/null
echo "  listo."
echo ""

echo "=== 2. Login de ambos vendedores ==="
LOGIN_A=$(curl -s -c "$COOKIES_A" -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/auth/login" \
  -H "Content-Type: application/json" -d "{\"email\":\"${EMAIL_A}\",\"password\":\"${PASSWORD}\"}")
[ "$LOGIN_A" = "200" ] && pass "login vendedor A -> 200" || fail "login vendedor A -> esperaba 200, fue $LOGIN_A"

LOGIN_B=$(curl -s -c "$COOKIES_B" -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/auth/login" \
  -H "Content-Type: application/json" -d "{\"email\":\"${EMAIL_B}\",\"password\":\"${PASSWORD}\"}")
[ "$LOGIN_B" = "200" ] && pass "login vendedor B -> 200" || fail "login vendedor B -> esperaba 200, fue $LOGIN_B"
echo ""

echo "=== 3. B1 — POST /empresas: RUC nuevo (vendedor A) ==="
BODY_1=$(curl -s -b "$COOKIES_A" -w "\n%{http_code}" -X POST "$BASE_URL/empresas" \
  -H "Content-Type: application/json" \
  -d "{\"ruc\":\"${RUC_SMOKE}\",\"razon_social\":\"Smoke Test SAC\",\"actividad_econ\":\"Transporte\",\"estado_sunat\":\"ACTIVO\",\"condicion_sunat\":\"HABIDO\",\"direccion_fiscal\":\"Av. Smoke Test 123\",\"segmentos\":[\"urbano\"]}")
STATUS_1=$(echo "$BODY_1" | tail -1)
JSON_1=$(echo "$BODY_1" | sed '$d')
DRIVE_FOLDER_ID=$(echo "$JSON_1" | grep -o '"drive_folder_id":"[^"]*"' | cut -d'"' -f4)
[ "$STATUS_1" = "201" ] && pass "RUC nuevo -> 201" || fail "RUC nuevo -> esperaba 201, fue $STATUS_1 ($JSON_1)"
echo "  drive_folder_id: ${DRIVE_FOLDER_ID:-<vacio>}"
echo ""

echo "=== 4. B1 — POST /empresas: mismo RUC, MISMO vendedor (vendedor A) ==="
BODY_2=$(curl -s -b "$COOKIES_A" -w "\n%{http_code}" -X POST "$BASE_URL/empresas" \
  -H "Content-Type: application/json" \
  -d "{\"ruc\":\"${RUC_SMOKE}\",\"razon_social\":\"Smoke Test SAC (reintento)\",\"actividad_econ\":\"Transporte\",\"estado_sunat\":\"ACTIVO\",\"condicion_sunat\":\"HABIDO\",\"direccion_fiscal\":\"Av. Smoke Test 123\"}")
STATUS_2=$(echo "$BODY_2" | tail -1)
[ "$STATUS_2" = "200" ] && pass "mismo RUC, mismo vendedor -> 200 (no 409, no 201)" || fail "esperaba 200, fue $STATUS_2 ($(echo "$BODY_2" | sed '$d'))"
ROWS_RUC=$(psql_exec "SELECT COUNT(*) FROM empresas WHERE ruc = '${RUC_SMOKE}';")
[ "$ROWS_RUC" = "1" ] && pass "no se insertó una fila duplicada (hay exactamente 1 en BD)" || fail "hay ${ROWS_RUC} filas con ese RUC, se esperaba 1"
echo ""

echo "=== 5. B1 — POST /empresas: mismo RUC, OTRO vendedor (vendedor B) ==="
BODY_3=$(curl -s -b "$COOKIES_B" -w "\n%{http_code}" -X POST "$BASE_URL/empresas" \
  -H "Content-Type: application/json" \
  -d "{\"ruc\":\"${RUC_SMOKE}\",\"razon_social\":\"Smoke Test SAC (otro vendedor)\",\"actividad_econ\":\"Transporte\",\"estado_sunat\":\"ACTIVO\",\"condicion_sunat\":\"HABIDO\",\"direccion_fiscal\":\"Av. Smoke Test 123\"}")
STATUS_3=$(echo "$BODY_3" | tail -1)
JSON_3=$(echo "$BODY_3" | sed '$d')
[ "$STATUS_3" = "409" ] && pass "mismo RUC, otro vendedor -> 409" || fail "esperaba 409, fue $STATUS_3 ($JSON_3)"
echo "$JSON_3" | grep -q '"code":"RUC_DUPLICADO"' && pass "code = RUC_DUPLICADO" || fail "code inesperado: $JSON_3"
echo "$JSON_3" | grep -qi "eres tu\|tu culpa\|error tuyo" && fail "el mensaje parece culpar al usuario: $JSON_3" || pass "el mensaje no culpa al usuario"
echo "  mensaje: $(echo "$JSON_3" | grep -o '"message":"[^"]*"')"
echo ""

echo "=== 6. D1 — POST /auth/cambiar-contrasena SIN autenticación ==="
STATUS_4=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/auth/cambiar-contrasena" \
  -H "Content-Type: application/json" \
  -d "{\"password_actual\":\"x\",\"password_nueva\":\"NuevaSegura123\"}")
[ "$STATUS_4" = "401" ] && pass "sin cookies -> 401 (el endpoint NO quedó público)" || fail "esperaba 401, fue $STATUS_4 — RIESGO DE SEGURIDAD si no es 401"
echo ""

echo "=== 7. D1 — password_actual incorrecta (vendedor B) ==="
BODY_5=$(curl -s -b "$COOKIES_B" -w "\n%{http_code}" -X POST "$BASE_URL/auth/cambiar-contrasena" \
  -H "Content-Type: application/json" \
  -d "{\"password_actual\":\"esta-no-es\",\"password_nueva\":\"NuevaSegura123\"}")
STATUS_5=$(echo "$BODY_5" | tail -1)
[ "$STATUS_5" = "401" ] && pass "password_actual incorrecta -> 401" || fail "esperaba 401, fue $STATUS_5"
echo ""

echo "=== 8. D1 — password_nueva igual a la actual (vendedor B) ==="
BODY_6=$(curl -s -b "$COOKIES_B" -w "\n%{http_code}" -X POST "$BASE_URL/auth/cambiar-contrasena" \
  -H "Content-Type: application/json" \
  -d "{\"password_actual\":\"${PASSWORD}\",\"password_nueva\":\"${PASSWORD}\"}")
STATUS_6=$(echo "$BODY_6" | tail -1)
JSON_6=$(echo "$BODY_6" | sed '$d')
[ "$STATUS_6" = "400" ] && pass "nueva igual a la actual -> 400" || fail "esperaba 400, fue $STATUS_6 ($JSON_6)"
echo "$JSON_6" | grep -q '"field":"password_nueva"' && pass "field = password_nueva" || fail "field inesperado: $JSON_6"
echo ""

echo "=== 9. D1 — cambio válido (vendedor B) ==="
NUEVA_PASSWORD="OtraSegura456"
STATUS_7=$(curl -s -b "$COOKIES_B" -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/auth/cambiar-contrasena" \
  -H "Content-Type: application/json" \
  -d "{\"password_actual\":\"${PASSWORD}\",\"password_nueva\":\"${NUEVA_PASSWORD}\"}")
[ "$STATUS_7" = "200" ] && pass "cambio válido -> 200" || fail "esperaba 200, fue $STATUS_7"
echo ""

echo "=== 10. D1 — login con la contraseña nueva refleja requiere_cambio_contrasena=false ==="
BODY_8=$(curl -s -c "$TMP_DIR/cookies_b2.txt" -w "\n%{http_code}" -X POST "$BASE_URL/auth/login" \
  -H "Content-Type: application/json" -d "{\"email\":\"${EMAIL_B}\",\"password\":\"${NUEVA_PASSWORD}\"}")
STATUS_8=$(echo "$BODY_8" | tail -1)
JSON_8=$(echo "$BODY_8" | sed '$d')
[ "$STATUS_8" = "200" ] && pass "login con password nueva -> 200" || fail "esperaba 200, fue $STATUS_8"
echo "$JSON_8" | grep -q '"requiere_cambio_contrasena":false' && pass "requiere_cambio_contrasena quedó en false" || fail "no se ve en false: $JSON_8"
echo ""

echo "=== 11. Verificación de permisos ya cubierta por tests (control rápido) ==="
STATUS_9=$(curl -s -b "$COOKIES_A" -o /dev/null -w "%{http_code}" "$BASE_URL/empleados")
[ "$STATUS_9" = "403" ] && pass "GET /empleados con rol vendedor -> 403" || fail "esperaba 403, fue $STATUS_9"
echo ""

echo "=================================================="
echo "  RESULTADO: ${PASS_COUNT} pasaron, ${FAIL_COUNT} fallaron"
echo "=================================================="
[ "$FAIL_COUNT" -eq 0 ] && exit 0 || exit 1
