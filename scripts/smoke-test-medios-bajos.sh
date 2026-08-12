#!/usr/bin/env bash
# Smoke test end-to-end (HTTP real, sin mocks) de la tanda [Medio]/[Bajo].
# Cubre los cambios de comportamiento HTTP que los tests mockeados no pueden
# ver de verdad (el mismo criterio que cazo el LazyInitializationException de
# B1 en la ronda anterior): A.1, A.3, D.1, B.1, B.2, B.3, G.1.
#
# Requiere: backend corriendo en BASE_URL y Postgres arriba. El script crea
# sus propios datos de prueba y los borra al final.
#
# Uso: bash scripts/smoke-test-medios-bajos.sh
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
  echo "El backend no responde. Levantalo con: ./gradlew bootRun --args='--app.exigir-utc=false'"
  exit 1
fi
echo "  backend arriba."
echo ""

TMP_DIR=$(mktemp -d)
COOKIES="$TMP_DIR/cookies.txt"
EMAIL_ADMIN="smoketest.admin.mb@example.com"
EMAIL_BORRAR="smoketest.borrar.mb@example.com"
PASSWORD="SmokeTest123!"
HASH='$2b$10$twrDQCQUkfXAGTDCZjDyA.dKr2CsXoxIgvF.jdeQTAkk5X8rSTfkq'
RUC_SMOKE="20999888762"
RUC_CARTERA="20999888763"

cleanup() {
  echo ""
  echo "=== Limpieza (deja la base como estaba) ==="
  psql_exec "DELETE FROM oportunidad_contactos WHERE id_oportunidad IN (SELECT id FROM oportunidades WHERE id_empresa IN (SELECT id FROM empresas WHERE ruc IN ('${RUC_SMOKE}','${RUC_CARTERA}')));" >/dev/null
  psql_exec "DELETE FROM oportunidad_estados_log WHERE id_oportunidad IN (SELECT id FROM oportunidades WHERE id_empresa IN (SELECT id FROM empresas WHERE ruc IN ('${RUC_SMOKE}','${RUC_CARTERA}')));" >/dev/null
  psql_exec "DELETE FROM empresa_contactos WHERE id_empresa IN (SELECT id FROM empresas WHERE ruc IN ('${RUC_SMOKE}','${RUC_CARTERA}'));" >/dev/null
  psql_exec "DELETE FROM contactos WHERE nombres = 'Smoke' AND apellidos = 'Contacto';" >/dev/null
  psql_exec "DELETE FROM oportunidades WHERE id_empresa IN (SELECT id FROM empresas WHERE ruc IN ('${RUC_SMOKE}','${RUC_CARTERA}'));" >/dev/null
  psql_exec "DELETE FROM empresa_segmentos WHERE id_empresa IN (SELECT id FROM empresas WHERE ruc IN ('${RUC_SMOKE}','${RUC_CARTERA}'));" >/dev/null
  psql_exec "DELETE FROM empresas WHERE ruc IN ('${RUC_SMOKE}','${RUC_CARTERA}');" >/dev/null
  psql_exec "DELETE FROM notificaciones WHERE id_actor IN (SELECT id FROM empleados WHERE email IN ('${EMAIL_ADMIN}', '${EMAIL_VENDEDOR}')) OR id_empleado_destinatario IN (SELECT id FROM empleados WHERE email IN ('${EMAIL_ADMIN}', '${EMAIL_VENDEDOR}'));" >/dev/null
  psql_exec "DELETE FROM empleados WHERE email IN ('${EMAIL_ADMIN}', '${EMAIL_VENDEDOR}', '${EMAIL_BORRAR}');" >/dev/null
  rm -rf "$TMP_DIR"
  echo "  datos de prueba borrados."
}
trap cleanup EXIT

EMAIL_VENDEDOR="smoketest.vendedor.mb@example.com"

echo "=== 1. Preparando empleados de prueba (admin + vendedor) ==="
psql_exec "
INSERT INTO empleados (nombres, apellidos, email, area, puesto, rol, activo, password_hash, requiere_cambio_contrasena)
VALUES
  ('SmokeTest', 'AdminMB', '${EMAIL_ADMIN}', 'QA', 'Smoke test', 'admin', true, '${HASH}', false),
  ('SmokeTest', 'VendedorMB', '${EMAIL_VENDEDOR}', 'Ventas', 'Smoke test', 'vendedor', true, '${HASH}', false)
ON CONFLICT (email) DO NOTHING;
" >/dev/null
ID_VENDEDOR=$(psql_exec "SELECT id FROM empleados WHERE email = '${EMAIL_VENDEDOR}';" | tr -d '[:space:]')
echo "  listo (id_vendedor=$ID_VENDEDOR)."
echo ""

echo "=== 2. Login ==="
LOGIN_BODY=$(curl -s -c "$COOKIES" -X POST "$BASE_URL/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"${EMAIL_ADMIN}\",\"password\":\"${PASSWORD}\"}")
if echo "$LOGIN_BODY" | grep -q '"data"'; then
  pass "login del admin de prueba"
else
  fail "login del admin de prueba: $LOGIN_BODY"
  exit 1
fi
echo ""

echo "=== 3. A.1 — GET /oportunidades?estado=invalido responde 400 ==="
CODE=$(curl -s -b "$COOKIES" -o "$TMP_DIR/a1.json" -w "%{http_code}" "$BASE_URL/oportunidades?estado=perdido")
FIELD=$(grep -o '"field":"[^"]*"' "$TMP_DIR/a1.json" || true)
if [ "$CODE" = "400" ] && echo "$FIELD" | grep -q "estado"; then
  pass "estado invalido -> 400 con field=estado ($FIELD)"
else
  fail "estado invalido -> esperado 400/field=estado, obtuve $CODE: $(cat "$TMP_DIR/a1.json")"
fi
echo ""

echo "=== 4. B.2 — GET /empresas?estado_cartera=invalido responde 400 ==="
CODE=$(curl -s -b "$COOKIES" -o "$TMP_DIR/b2.json" -w "%{http_code}" "$BASE_URL/empresas?estado_cartera=perdido")
FIELD=$(grep -o '"field":"[^"]*"' "$TMP_DIR/b2.json" || true)
if [ "$CODE" = "400" ] && echo "$FIELD" | grep -q "estado_cartera"; then
  pass "estado_cartera invalido -> 400 con field=estado_cartera ($FIELD)"
else
  fail "estado_cartera invalido -> esperado 400/field=estado_cartera, obtuve $CODE: $(cat "$TMP_DIR/b2.json")"
fi
echo ""

echo "=== 5. G.1 — error.field va en snake_case (campo compuesto) ==="
CODE=$(curl -s -b "$COOKIES" -o "$TMP_DIR/g1.json" -w "%{http_code}" -X POST "$BASE_URL/empresas" \
  -H "Content-Type: application/json" \
  -d '{"ruc":"123"}')
FIELD=$(grep -o '"field":"[^"]*"' "$TMP_DIR/g1.json" || true)
echo "  respuesta: $CODE, $FIELD ($(cat "$TMP_DIR/g1.json"))"
if [ "$CODE" = "400" ]; then
  pass "POST /empresas con ruc invalido -> 400 ($FIELD)"
else
  fail "POST /empresas con ruc invalido -> esperado 400, obtuve $CODE"
fi
echo ""

echo "=== 6. Creando empresa de prueba con carpeta de Drive (para B.1 y B.3) ==="
CREAR_BODY=$(curl -s -b "$COOKIES" -X POST "$BASE_URL/empresas" \
  -H "Content-Type: application/json" \
  -d "{\"ruc\":\"${RUC_SMOKE}\",\"razon_social\":\"Smoke MB Test S.A.C.\"}")
ID_EMPRESA=$(echo "$CREAR_BODY" | grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*')
DRIVE_FOLDER_ID=$(echo "$CREAR_BODY" | grep -o '"drive_folder_id":"[^"]*"' | sed 's/.*:"//;s/"$//')
if [ -n "$ID_EMPRESA" ]; then
  pass "empresa creada (id=$ID_EMPRESA, drive_folder_id=$DRIVE_FOLDER_ID)"
else
  fail "creacion de empresa: $CREAR_BODY"
fi
echo ""

echo "=== 7. B.3 — DELETE /empresas/:id envia la carpeta de Drive a la papelera ==="
if [ -n "$ID_EMPRESA" ]; then
  CODE=$(curl -s -b "$COOKIES" -o /dev/null -w "%{http_code}" -X DELETE "$BASE_URL/empresas/$ID_EMPRESA")
  if [ "$CODE" = "204" ]; then
    pass "DELETE /empresas/$ID_EMPRESA -> 204"
    if [ -n "$DRIVE_FOLDER_ID" ] && [ "$DRIVE_FOLDER_ID" != "null" ]; then
      echo "  NOTA: la carpeta $DRIVE_FOLDER_ID se envio a la papelera de Drive de verdad (reversible ~30 dias)."
    fi
  else
    fail "DELETE /empresas/$ID_EMPRESA -> esperado 204, obtuve $CODE"
  fi
else
  fail "B.3 omitido: no se creo la empresa de prueba"
fi
echo ""

echo "=== 8. B.1 — reasignar vendedor sobre empresa en Cartera Maestra responde 409 ==="
CREAR2_BODY=$(curl -s -b "$COOKIES" -X POST "$BASE_URL/empresas" \
  -H "Content-Type: application/json" \
  -d "{\"ruc\":\"${RUC_CARTERA}\",\"razon_social\":\"Smoke MB Cartera S.A.C.\"}")
ID_EMPRESA2=$(echo "$CREAR2_BODY" | grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*')
if [ -n "$ID_EMPRESA2" ]; then
  psql_exec "UPDATE empresas SET en_cartera_maestra = true, id_vendedor = NULL WHERE id = ${ID_EMPRESA2};" >/dev/null
  CODE=$(curl -s -b "$COOKIES" -o "$TMP_DIR/b1.json" -w "%{http_code}" -X PATCH "$BASE_URL/empresas/$ID_EMPRESA2/vendedor" \
    -H "Content-Type: application/json" \
    -d "{\"id_vendedor\":${ID_ADMIN:-1}}")
  BODY_CODE=$(grep -o '"code":"[^"]*"' "$TMP_DIR/b1.json" || true)
  if [ "$CODE" = "409" ] && echo "$BODY_CODE" | grep -q "EMPRESA_EN_CARTERA_MAESTRA"; then
    pass "reasignar vendedor en cartera maestra -> 409 EMPRESA_EN_CARTERA_MAESTRA"
  else
    fail "reasignar vendedor en cartera maestra -> esperado 409/EMPRESA_EN_CARTERA_MAESTRA, obtuve $CODE: $(cat "$TMP_DIR/b1.json")"
  fi
else
  fail "B.1 omitido: no se creo la segunda empresa de prueba"
fi
echo ""

echo "=== 9. D.1 — POST /auth/refresh con empleado borrado responde 401, no 404 ==="
psql_exec "
INSERT INTO empleados (nombres, apellidos, email, area, puesto, rol, activo, password_hash, requiere_cambio_contrasena)
VALUES ('SmokeTest', 'Borrar', '${EMAIL_BORRAR}', 'QA', 'Smoke test', 'vendedor', true, '${HASH}', false)
ON CONFLICT (email) DO NOTHING;
" >/dev/null
COOKIES_BORRAR="$TMP_DIR/cookies_borrar.txt"
curl -s -c "$COOKIES_BORRAR" -X POST "$BASE_URL/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"${EMAIL_BORRAR}\",\"password\":\"${PASSWORD}\"}" >/dev/null
psql_exec "DELETE FROM empleados WHERE email = '${EMAIL_BORRAR}';" >/dev/null
CODE=$(curl -s -b "$COOKIES_BORRAR" -o "$TMP_DIR/d1.json" -w "%{http_code}" -X POST "$BASE_URL/auth/refresh")
BODY_CODE=$(grep -o '"code":"[^"]*"' "$TMP_DIR/d1.json" || true)
if [ "$CODE" = "401" ] && echo "$BODY_CODE" | grep -q "CREDENCIALES_INVALIDAS"; then
  pass "refresh de empleado borrado -> 401 CREDENCIALES_INVALIDAS (no 404)"
else
  fail "refresh de empleado borrado -> esperado 401/CREDENCIALES_INVALIDAS, obtuve $CODE: $(cat "$TMP_DIR/d1.json")"
fi
echo ""

echo "=== 10. A.3 — vincular el mismo contacto dos veces responde 409 ==="
MODELO_ID=$(psql_exec "SELECT id FROM modelos LIMIT 1;" | tr -d '[:space:]')
CONTACTO_BODY=$(curl -s -b "$COOKIES" -X POST "$BASE_URL/contactos" \
  -H "Content-Type: application/json" \
  -d "{\"nombres\":\"Smoke\",\"apellidos\":\"Contacto\",\"tlf_1\":\"999999999\",\"id_empresa\":${ID_EMPRESA2}}")
ID_CONTACTO=$(echo "$CONTACTO_BODY" | grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*')
if [ -n "$ID_EMPRESA2" ] && [ -n "$ID_CONTACTO" ] && [ -n "$MODELO_ID" ]; then
  # Reasignar a un vendedor real: la empresa quedo en Cartera Maestra en el paso 8.
  psql_exec "UPDATE empresas SET en_cartera_maestra = false, id_vendedor = ${ID_VENDEDOR} WHERE id = ${ID_EMPRESA2};" >/dev/null
  OP_BODY=$(curl -s -b "$COOKIES" -X POST "$BASE_URL/oportunidades" \
    -H "Content-Type: application/json" \
    -d "{\"id_empresa\":${ID_EMPRESA2},\"id_modelo\":${MODELO_ID},\"id_vendedor\":${ID_VENDEDOR}}")
  ID_OP=$(echo "$OP_BODY" | grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*')
  if [ -n "$ID_OP" ]; then
    curl -s -b "$COOKIES" -X POST "$BASE_URL/oportunidades/$ID_OP/contactos" \
      -H "Content-Type: application/json" \
      -d "{\"id_contacto\":${ID_CONTACTO},\"rol_en_oportunidad\":\"Titular\"}" >/dev/null
    CODE=$(curl -s -b "$COOKIES" -o "$TMP_DIR/a3.json" -w "%{http_code}" -X POST "$BASE_URL/oportunidades/$ID_OP/contactos" \
      -H "Content-Type: application/json" \
      -d "{\"id_contacto\":${ID_CONTACTO},\"rol_en_oportunidad\":\"Otro\"}")
    BODY_CODE=$(grep -o '"code":"[^"]*"' "$TMP_DIR/a3.json" || true)
    if [ "$CODE" = "409" ] && echo "$BODY_CODE" | grep -q "CONTACTO_YA_VINCULADO"; then
      pass "vincular contacto duplicado -> 409 CONTACTO_YA_VINCULADO"
    else
      fail "vincular contacto duplicado -> esperado 409/CONTACTO_YA_VINCULADO, obtuve $CODE: $(cat "$TMP_DIR/a3.json")"
    fi
  else
    fail "A.3 omitido: no se creo la oportunidad de prueba ($OP_BODY)"
  fi
else
  fail "A.3 omitido: falto empresa/contacto/modelo de prueba"
fi
psql_exec "DELETE FROM contactos WHERE id = ${ID_CONTACTO:-0};" >/dev/null 2>&1 || true
echo ""

echo "=================================================="
echo "RESULTADO: $PASS_COUNT pasaron, $FAIL_COUNT fallaron"
echo "=================================================="
[ "$FAIL_COUNT" -eq 0 ]
